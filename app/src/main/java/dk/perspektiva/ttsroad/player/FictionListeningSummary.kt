package dk.perspektiva.ttsroad.player

import dk.perspektiva.ttsroad.data.ChapterSummary
import kotlin.math.roundToLong

/**
 * The fiction header's account of what is left to *listen to*, as opposed to what is on disk.
 *
 * The screen already answers the storage question — "0 offline · 73 not downloaded". This answers
 * the one asked before starting a 400-chapter serial, which is a different question with a
 * different answer.
 *
 * Every field is a sum over the chapter list the screen has already loaded; nothing here needs a
 * request.
 */
data class FictionListeningSummary(
    /** Chapters with audio. Chapters still being generated are not something to listen to yet. */
    val playable: Int = 0,
    val played: Int = 0,
    val unplayed: Int = 0,
    val remainingSeconds: Double = 0.0,
    /**
     * Whether [remainingSeconds] rests on anything. False when no playable chapter reported a
     * duration, where a "0m remaining" would be a confident lie rather than a total.
     */
    val hasRemaining: Boolean = false,
)

/**
 * Total up a fiction's chapters by listening state.
 *
 * A chapter marked played counts as nothing remaining even when its position is zero. Marking
 * played without listening is a real action here (`/playback/mark`), and the alternative — trusting
 * the position — would report a fiction the user has explicitly finished as entirely unheard.
 */
fun fictionListeningSummary(chapters: List<ChapterSummary>): FictionListeningSummary {
    val playable = chapters.asSequence()
        .filter { it.audio != null }
        .distinctBy { it.resolvedChapterId }
        .toList()
    if (playable.isEmpty()) return FictionListeningSummary()

    var played = 0
    var remaining = 0.0
    var hasRemaining = false
    for (chapter in playable) {
        if (chapter.resolvedIsPlayed) {
            played++
            continue
        }
        chapter.resolvedRemainingSeconds?.let {
            remaining += it
            hasRemaining = true
        }
    }

    return FictionListeningSummary(
        playable = playable.size,
        played = played,
        unplayed = playable.size - played,
        remainingSeconds = remaining,
        hasRemaining = hasRemaining,
    )
}

/**
 * A span of listening time as the header shows it — "54h 38m", "38m", "<1m".
 *
 * Hours and minutes only. Seconds are noise at this scale, and the totals this formats are usually
 * measured in days of audio.
 */
fun formatListeningSpan(seconds: Double): String {
    if (seconds.isNaN() || seconds <= 0.0) return "0m"
    val totalMinutes = (seconds / 60.0).toLong()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        // Rounded away, but there is still audio there — say so rather than claiming none.
        else -> "<1m"
    }
}

/**
 * How long [seconds] of audio actually takes at [speed] — the number that answers "will I finish
 * this on the drive", which raw remaining does not once someone listens at 1.75x.
 *
 * A non-positive or non-finite speed yields the unscaled span; the player should never report one,
 * and dividing by it would produce an infinity on screen.
 */
fun listeningSpanAtSpeed(seconds: Double, speed: Float): Double {
    if (!speed.isFinite() || speed <= 0f) return seconds
    return seconds / speed
}

/** Milliseconds left in a chapter of [durationMs] played to [positionMs], never negative. */
fun remainingMs(positionMs: Long, durationMs: Long): Long =
    (durationMs - positionMs).coerceIn(0L, durationMs.coerceAtLeast(0L))

/** [remainingMs] scaled by playback speed, for the player's finish estimate. */
fun remainingMsAtSpeed(positionMs: Long, durationMs: Long, speed: Float): Long {
    val remaining = remainingMs(positionMs, durationMs)
    if (!speed.isFinite() || speed <= 0f) return remaining
    return (remaining / speed.toDouble()).roundToLong()
}
