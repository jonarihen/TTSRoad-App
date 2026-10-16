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
    val audioStopped = LastHeard(stopped, LastHeardKind.AudioStopped, stopped.timestamp)
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
