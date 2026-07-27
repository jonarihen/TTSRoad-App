package dk.perspektiva.ttsroad.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SleepTimerMode {
    Off,
    Duration,
    EndOfChapter,
}

data class SleepTimerState(
    val mode: SleepTimerMode = SleepTimerMode.Off,
    val remainingMs: Long = 0L,
    val isFading: Boolean = false,
) {
    val isArmed: Boolean get() = mode != SleepTimerMode.Off
}

/** What the media service should do to the player after a [SleepTimerController] call. */
sealed interface SleepTimerAction {
    data object None : SleepTimerAction

    /** Ramp the player to [volume] — the fade-out, or the restore back to full. */
    data class SetVolume(val volume: Float) : SleepTimerAction

    /** The timer ran out: pause playback and restore full volume. */
    data object Expire : SleepTimerAction
}

/**
 * The sleep timer's state machine. Owned by [dk.perspektiva.ttsroad.media.TtsRoadMediaService] so
 * it keeps counting with the app backgrounded and the screen off — the same reason
 * [PlaybackHistoryStore] lives service-side — and observed by the UI through [state].
 *
 * It holds no player reference and no Android dependencies: the service ticks it and applies the
 * returned [SleepTimerAction]. That keeps the "when do we fade, when do we stop" decisions in one
 * testable place.
 */
class SleepTimerController {
    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private var lastTickMs: Long? = null
    private var appliedVolume: Float = 1f

    // Arming, cancelling, and extending only move the state; [tick] is the single place that
    // decides what the player's volume should be, so the UI can call these without a player.

    /** Arm a plain countdown, e.g. 30 minutes. */
    fun armDuration(durationMs: Long) {
        if (durationMs <= 0L) {
            cancel()
            return
        }
        lastTickMs = null
        _state.value = SleepTimerState(mode = SleepTimerMode.Duration, remainingMs = durationMs)
    }

    /**
     * Stop at the end of the chapter being played. [chapterRemainingMs] seeds the countdown shown
     * in the UI; the service keeps it in step with the real playback position on every tick.
     */
    fun armEndOfChapter(chapterRemainingMs: Long) {
        lastTickMs = null
        _state.value = SleepTimerState(
            mode = SleepTimerMode.EndOfChapter,
            remainingMs = chapterRemainingMs.coerceAtLeast(0L),
        )
    }

    fun cancel() {
        lastTickMs = null
        _state.value = SleepTimerState()
    }

    /**
     * Add more time — the half-awake shake during the fade-out. An "end of chapter" timer becomes
     * a plain countdown, since past the chapter boundary there is nothing left to count to.
     */
    fun extend(extraMs: Long) {
        val current = _state.value
        if (!current.isArmed) return
        _state.value = SleepTimerState(
            mode = SleepTimerMode.Duration,
            remainingMs = current.remainingMs + extraMs,
        )
    }

    /**
     * Advance the timer. Called by the service on a short tick with the player's current
     * [isPlaying] and, when a duration is known, how much of the chapter is left.
     */
    fun tick(nowMs: Long, isPlaying: Boolean, chapterRemainingMs: Long?): SleepTimerAction {
        val current = _state.value
        if (!current.isArmed) {
            // Covers a cancel (or a shake-extend) landing mid-fade: undo the ducking.
            lastTickMs = null
            return restoreVolumeIfFaded()
        }

        val previousTickMs = lastTickMs
        lastTickMs = nowMs

        // Reaching the chapter boundary *is* this timer firing, so it counts even though the
        // player has already stopped itself there via pauseAtEndOfMediaItems.
        val atChapterEnd = current.mode == SleepTimerMode.EndOfChapter &&
            chapterRemainingMs != null && chapterRemainingMs <= 0L

        if (!isPlaying && !atChapterEnd) {
            // A manual pause freezes the countdown rather than spending it, and calls off any
            // fade in progress — pressing play again should not be met with silence.
            return restoreVolumeIfFaded()
        }

        val elapsedMs = previousTickMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L
        val remainingMs = when (current.mode) {
            // Track the real position: seeking within the chapter moves the boundary.
            SleepTimerMode.EndOfChapter -> chapterRemainingMs ?: (current.remainingMs - elapsedMs)
            else -> current.remainingMs - elapsedMs
        }

        if (remainingMs <= 0L) {
            lastTickMs = null
            appliedVolume = 1f
            _state.value = SleepTimerState()
            return SleepTimerAction.Expire
        }

        _state.value = current.copy(remainingMs = remainingMs, isFading = remainingMs <= FadeMs)
        return if (remainingMs <= FadeMs) {
            applyVolume(remainingMs.toFloat() / FadeMs)
        } else {
            restoreVolumeIfFaded()
        }
    }

    private fun applyVolume(volume: Float): SleepTimerAction {
        val clamped = volume.coerceIn(0f, 1f)
        appliedVolume = clamped
        return SleepTimerAction.SetVolume(clamped)
    }

    private fun restoreVolumeIfFaded(): SleepTimerAction =
        if (appliedVolume < 1f) applyVolume(1f) else SleepTimerAction.None

    companion object {
        /** Ramp the volume down over the last stretch, so audio doesn't cut mid-sentence. */
        const val FadeMs = 30_000L

        /** How much a shake during the fade buys you. */
        const val ExtendMs = 5 * 60_000L

        /** Offered in the player's sleep-timer sheet, in minutes. */
        val DurationOptionsMinutes = listOf(5, 15, 30, 45, 60)
    }
}
