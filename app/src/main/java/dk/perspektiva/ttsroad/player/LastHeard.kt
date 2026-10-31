package dk.perspektiva.ttsroad.player

/**
 * Picks the snapshot to offer as "you fell asleep at 23:49" on the library screen.
 *
 * The app already records where playback was at every wall-clock moment, so the morning workflow —
 * open the app, open the jump-back sheet, hunt for the right time — is information it already has.
 * This turns it into a single button.
 *
 * Returns null when the newest snapshot is too recent to be interesting: the point of the banner is
 * to catch up after a night, not to offer a rewind to something you were listening to on the way in
 * from the car.
 */
fun lastHeardSnapshot(
    history: List<HistorySnapshot>,
    now: Long,
    minimumAgeMs: Long = DefaultMinimumAgeMs,
): HistorySnapshot? {
    val newest = history.lastOrNull() ?: return null
    if (now - newest.timestamp < minimumAgeMs) return null

    // Playback can keep logging the same position — a stalled stream, or a chapter that ran out
    // while the phone sat on the nightstand. The *first* snapshot of that trailing run is the last
    // moment the audio was actually moving, which is closer to when the listener stopped following
    // than the final repeat is.
    var index = history.lastIndex
    while (index > 0) {
        val previous = history[index - 1]
        if (previous.mediaId != newest.mediaId || previous.positionMs != newest.positionMs) break
        index--
    }
    return history[index]
}

/** Why the banner's point was chosen, which decides how it is labelled. */
enum class LastHeardKind {
    /** No better estimate: this is where the audio stopped, and the banner says exactly that. */
    AudioStopped,

    /**
     * No timer, but the trail shows an unbroken stretch of listening at one pace ending at the stop:
     * nothing was seeked, paused or skipped after this moment, so this is the last time the phone
     * was plausibly touched (#502).
     */
    LastTouched,

    /** A sleep timer cut the audio; this is where the listener last touched it, awake. */
    TimerSet,
}

data class LastHeard(
    val snapshot: HistorySnapshot,
    val kind: LastHeardKind,
    /** When the audio stopped — the snapshot's own time unless [kind] is [LastHeardKind.TimerSet]. */
    val stoppedAtMs: Long,
)

/**
 * The banner's suggestion, estimated rather than read off the newest snapshot (#332).
 *
 * The newest snapshot is when the audio *stopped*, which after a sleep timer is the timer's length
 * past anything the listener heard — RESUME THERE would land them thirty minutes, or hours, past
 * the last part they followed. When [sleepStop] says a timer cut this very stretch of playback, the
 * suggestion moves back to the first snapshot from when they last armed or extended that timer:
 * the last moment they are known to have been awake. Re-hearing a few minutes is cheap; skipping
 * an hour of story is not.
 *
 * Without a matching timer stop there is no better estimate, and the result says so with
 * [LastHeardKind.AudioStopped] so the banner can label the time honestly.
 */
fun estimateLastHeard(
    history: List<HistorySnapshot>,
    now: Long,
    sleepStop: SleepTimerStop?,
    minimumAgeMs: Long = DefaultMinimumAgeMs,
): LastHeard? {
    val stopped = lastHeardSnapshot(history, now, minimumAgeMs) ?: return null
    val untouched = untouchedSince(history, stopped)
    val audioStopped = if (untouched == null) {
        LastHeard(stopped, LastHeardKind.AudioStopped, stopped.timestamp)
    } else {
        LastHeard(untouched, LastHeardKind.LastTouched, stopped.timestamp)
    }
    if (sleepStop == null) return audioStopped
    // The timer's stop has to be *this* stop: a later session that ended some other way must not
    // be rewound to a timer from the night before.
    val newest = history.last()
    if (kotlin.math.abs(newest.timestamp - sleepStop.stoppedAtMs) > SleepStopMatchMs) return audioStopped
    val awake = history.firstOrNull {
        it.timestamp >= sleepStop.lastInteractionAtMs - SleepStopMatchMs
    } ?: return audioStopped
    if (awake.timestamp >= stopped.timestamp) return audioStopped
    return LastHeard(awake, LastHeardKind.TimerSet, newest.timestamp)
}

/** Slack between the service's clocks and its 15s history tick when matching a timer stop. */
const val SleepStopMatchMs: Long = 2 * 60_000L

/** Older than this and the banner is worth showing — roughly "not the same sitting". */
const val DefaultMinimumAgeMs: Long = 30 * 60_000L

/** A gap longer than this between snapshots (15s ticks) means playback paused or the app was away. */
private const val MaxTickGapMs: Long = 45_000L

/** Shorter than this and an unbroken stretch is just a listening session, not a doze. */
const val MinUntouchedMs: Long = 20 * 60_000L

/**
 * Where the unbroken stretch of listening that ends at [stopped] began, or null when it is shorter
 * than [MinUntouchedMs] (#502). Without a timer nothing records the last touch, so it is read off
 * the trail: while untouched, media time advances at one steady multiple of wall-clock time (the
 * playback speed). A seek, skip, pause or chapter pick breaks that ratio or leaves a gap.
 * Chapters advancing on their own are not a break.
 */
internal fun untouchedSince(history: List<HistorySnapshot>, stopped: HistorySnapshot): HistorySnapshot? {
    val end = history.indexOf(stopped).takeIf { it > 0 } ?: return null
    fun ratio(a: HistorySnapshot, b: HistorySnapshot): Double? =
        if (a.mediaId == b.mediaId) {
            (b.positionMs - a.positionMs).toDouble() / (b.timestamp - a.timestamp).coerceAtLeast(1L)
        } else {
            null
        }
    // The pace the sleeper's phone was last running at: the newest same-chapter pair before the stop.
    val pace = (end downTo 1).firstNotNullOfOrNull { i ->
        ratio(history[i - 1], history[i])?.takeIf { it > 0.0 }
    } ?: return null
    var start = end
    while (start > 0) {
        val a = history[start - 1]
        val b = history[start]
        if (b.timestamp - a.timestamp > MaxTickGapMs) break
        val r = ratio(a, b)
        if (r != null && kotlin.math.abs(r - pace) > pace * 0.15) break
        start--
    }
    return history[start].takeIf { stopped.timestamp - it.timestamp >= MinUntouchedMs }
}
