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
    /**
     * How much of the ceiling on an end-of-chapter timer is left, or null when it has none.
     *
     * "Finish this chapter, then stop" is the mode people arm at night, and it has one bad case: a
     * chapter with fifty minutes left is not a sleep timer, it is a promise to be awake at one in
     * the morning. A ceiling makes the mode mean *whichever comes first*.
     *
     * Tracked here rather than resolved when the timer is armed because the boundary moves: an
     * end-of-chapter timer follows the real playback position, so rewinding twenty minutes would
     * otherwise push the stop twenty minutes past a ceiling the user asked for.
     */
    val capRemainingMs: Long? = null,
) {
    val isArmed: Boolean get() = mode != SleepTimerMode.Off

    /** True when this timer will stop at a ceiling if the chapter has not ended by then. */
    val isCapped: Boolean get() = capRemainingMs != null

    /** True when the ceiling, rather than the chapter's own end, is what will stop playback. */
    val willStopAtCap: Boolean get() = capRemainingMs != null && capRemainingMs <= remainingMs
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

    /** When the player was first seen paused with this timer armed, or null while it plays. */
    private var pausedSinceMs: Long? = null

    /**
     * The tick clock's reading at the listener's last touch of this timer (arm or extend), or null
     * until the first tick after that touch stamps it. Arming has no clock of its own.
     */
    private var interactionTickMs: Long? = null

    /**
     * How long before the most recent [SleepTimerAction.Expire] the listener last touched the timer
     * — the last moment they were known to be awake. Read by the service when it applies the
     * expiry; null before any timer has expired.
     */
    var lastExpirySinceInteractionMs: Long? = null
        private set

    // Arming, cancelling, and extending only move the state; [tick] is the single place that
    // decides what the player's volume should be, so the UI can call these without a player.

    /** Arm a plain countdown, e.g. 30 minutes. */
    fun armDuration(durationMs: Long) {
        if (durationMs <= 0L) {
            cancel()
            return
        }
        lastTickMs = null
        pausedSinceMs = null
        interactionTickMs = null
        _state.value = SleepTimerState(mode = SleepTimerMode.Duration, remainingMs = durationMs)
    }

    /**
     * Stop at the end of the chapter being played. [chapterRemainingMs] seeds the countdown shown
     * in the UI; the service keeps it in step with the real playback position on every tick.
     *
     * [capMs] is the ceiling for the hybrid mode — "finish this chapter, unless that is another
     * fifty minutes, in which case stop sooner". Null is the plain boundary timer, which is what
     * every caller before 0.13.0 wanted and still gets by default.
     */
    fun armEndOfChapter(chapterRemainingMs: Long, capMs: Long? = null) {
        lastTickMs = null
        pausedSinceMs = null
        interactionTickMs = null
        val cap = capMs?.coerceAtLeast(0L)
        val toChapterEnd = chapterRemainingMs.coerceAtLeast(0L)
        _state.value = SleepTimerState(
            mode = SleepTimerMode.EndOfChapter,
            remainingMs = if (cap != null) minOf(toChapterEnd, cap) else toChapterEnd,
            capRemainingMs = cap,
        )
    }

    fun cancel() {
        lastTickMs = null
        pausedSinceMs = null
        _state.value = SleepTimerState()
    }

    /**
     * Add more time — the half-awake shake during the fade-out. An "end of chapter" timer becomes
     * a plain countdown, since past the chapter boundary there is nothing left to count to.
     *
     * The ceiling goes with it, and deliberately: someone who has just asked for five more minutes
     * has answered the question the ceiling was there to ask, and re-applying it would take the
     * five minutes straight back.
     */
    fun extend(extraMs: Long) {
        val current = _state.value
        if (!current.isArmed) return
        pausedSinceMs = null
        interactionTickMs = null
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
        if (interactionTickMs == null) interactionTickMs = nowMs

        // Reaching the chapter boundary *is* this timer firing, so it counts even though the
        // player has already stopped itself there via pauseAtEndOfMediaItems.
        val atChapterEnd = current.mode == SleepTimerMode.EndOfChapter &&
            chapterRemainingMs != null && chapterRemainingMs <= 0L

        // A timer left paused for longer than PausedExpiryMs belongs to a night that is over.
        // Checked on the playing branch too: the tick can stall while the device dozes, so the
        // first tick after a long pause may already see the player running again.
        val pausedSince = pausedSinceMs
        if (pausedSince != null && nowMs - pausedSince >= PausedExpiryMs) {
            lastTickMs = null
            pausedSinceMs = null
            _state.value = SleepTimerState()
            return restoreVolumeIfFaded()
        }

        if (!isPlaying && !atChapterEnd) {
            // A manual pause freezes the countdown rather than spending it, and calls off any
            // fade in progress — pressing play again should not be met with silence. A short
            // pause (the bathroom, a sip of water) keeps the timer; a long one drops it above.
            if (pausedSinceMs == null) pausedSinceMs = nowMs
            return restoreVolumeIfFaded()
        }
        pausedSinceMs = null

        val elapsedMs = previousTickMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L
        // The ceiling is wall-clock, like a plain countdown, so it is spent by playing rather than
        // by where the position happens to be. That is what makes it survive a seek.
        val capRemainingMs = current.capRemainingMs?.let { (it - elapsedMs).coerceAtLeast(0L) }
        val remainingMs = when (current.mode) {
            // Track the real position: seeking within the chapter moves the boundary.
            SleepTimerMode.EndOfChapter -> {
                val toChapterEnd = chapterRemainingMs ?: (current.remainingMs - elapsedMs)
                if (capRemainingMs != null) minOf(toChapterEnd, capRemainingMs) else toChapterEnd
            }
            else -> current.remainingMs - elapsedMs
        }

        if (remainingMs <= 0L) {
            lastExpirySinceInteractionMs = (nowMs - (interactionTickMs ?: nowMs)).coerceAtLeast(0L)
            lastTickMs = null
            pausedSinceMs = null
            interactionTickMs = null
            appliedVolume = 1f
            _state.value = SleepTimerState()
            return SleepTimerAction.Expire
        }

        _state.value = current.copy(
            remainingMs = remainingMs,
            isFading = remainingMs <= FadeMs,
            capRemainingMs = capRemainingMs,
        )
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

    /**
     * One press of the sleep-timer button on the notification, lockscreen or car (#337): off arms
     * [defaultMinutes]; an armed countdown steps up to the next longer preset; past the longest —
     * or on an end-of-chapter timer, which has no preset to step to — the timer is cancelled. So
     * a few presses from bed reach any preset and one more turns it off, with no app needed.
     */
    fun cyclePreset(defaultMinutes: Int) {
        val current = _state.value
        val next = nextPresetMinutes(current, defaultMinutes)
        if (next == null) cancel() else armDuration(next * 60_000L)
    }

    companion object {
        /** See [cyclePreset]; null means "turn it off". */
        fun nextPresetMinutes(state: SleepTimerState, defaultMinutes: Int): Int? = when (state.mode) {
            SleepTimerMode.Off -> defaultMinutes.takeIf { it > 0 } ?: ButtonFallbackMinutes
            SleepTimerMode.EndOfChapter -> null
            SleepTimerMode.Duration -> DurationOptionsMinutes.firstOrNull { it * 60_000L > state.remainingMs }
        }

        /**
         * The button's name, which is the only place its state shows on the notification or car:
         * whole minutes rounded up, so a fresh 30-minute timer reads "30 min", not "29 min".
         */
        fun buttonLabel(state: SleepTimerState): String = when (state.mode) {
            SleepTimerMode.Off -> "Sleep timer: off"
            SleepTimerMode.EndOfChapter -> "Sleep timer: end of chapter"
            SleepTimerMode.Duration -> "Sleep timer: ${(state.remainingMs + 59_999L) / 60_000L} min"
        }

        /**
         * What the button arms when the default is "ask me every time" (0): there is nobody to
         * ask on a lockscreen, and half an hour is the middle of the presets.
         */
        const val ButtonFallbackMinutes = 30

        /** Ramp the volume down over the last stretch, so audio doesn't cut mid-sentence. */
        const val FadeMs = 30_000L

        /**
         * How long a paused timer survives before it is dropped. A pause freezes the countdown
         * so a short break does not spend it, but a timer paused at night must not wake up the
         * next afternoon and fade out the chores. An hour is longer than any break a listener
         * means to come back from, and shorter than a night.
         */
        const val PausedExpiryMs = 60 * 60_000L

        /** How much a shake during the fade buys you. */
        const val ExtendMs = 5 * 60_000L

        /** Offered in the player's sleep-timer sheet, in minutes. */
        val DurationOptionsMinutes = listOf(5, 15, 30, 45, 60)

        /**
         * The ceiling on the hybrid "end of chapter, or this, whichever is sooner" option.
         *
         * Thirty minutes because that is what the mode is for. Anyone still awake after half an
         * hour did not want a sleep timer, and the plain end-of-chapter option is one row up for
         * the nights when finishing the chapter is the point.
         */
        const val ChapterEndCapMs = 30 * 60_000L
    }
}
