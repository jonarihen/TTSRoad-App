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

/** Older than this and the banner is worth showing — roughly "not the same sitting". */
const val DefaultMinimumAgeMs: Long = 30 * 60_000L
