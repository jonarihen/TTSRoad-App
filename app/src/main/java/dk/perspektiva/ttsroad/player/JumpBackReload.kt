package dk.perspektiva.ttsroad.player

import kotlinx.coroutines.CancellationException

/**
 * Jump to [snap]: seek inside the loaded queue when it is there, otherwise reload the fiction and
 * start at the historical position (the queue was cleared overnight).
 *
 * Returns null on success, or a message for the listener. The reload can fail — the server
 * unreachable in the morning, a fiction since removed — and a swallowed failure used to leave an
 * empty player with no word of why (#341). The callers keep their sheet or banner up and offer a
 * retry instead.
 */
suspend fun jumpBackTo(
    snap: HistorySnapshot,
    seekInQueue: (mediaId: String, positionMs: Long) -> Boolean,
    reload: suspend (HistorySnapshot) -> Boolean,
): String? {
    if (seekInQueue(snap.mediaId, snap.positionMs)) return null
    if (snap.fictionId <= 0 || snap.chapterId <= 0) return "This moment can no longer be reloaded"
    return try {
        if (reload(snap)) null else "Could not start playback of ${snap.title}"
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        "Could not load ${snap.fictionTitle ?: snap.title}: ${failure.message ?: "server unreachable"}"
    }
}
