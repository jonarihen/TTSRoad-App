package dk.perspektiva.ttsroad.player

import dk.perspektiva.ttsroad.data.PlaybackSyncItem
import dk.perspektiva.ttsroad.data.PlaybackSyncState
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A rejection the client should stop retrying.
 *
 * Only `stale` means "someone else got there first with something newer" — that item lost fairly
 * and the server's state supersedes it. The others mean the server could not use what was sent, so
 * resending it unchanged would fail identically and keep the queue from ever draining.
 */
private val TerminalRejectReasons = setOf(
    "stale",
    "not_found",
    "empty",
    "missing_client_updated_at",
    "invalid_client_updated_at",
)

/** What a flush did, for the caller that wants to react to reconciled state. */
data class ProgressFlushResult(
    val sent: Int = 0,
    val accepted: Int = 0,
    /** Items the server declined *because it held something newer*. */
    val overriddenByServer: List<PlaybackSyncState> = emptyList(),
    /** True when everything queued has now been dealt with one way or the other. */
    val drained: Boolean = false,
)

/**
 * Drains [PendingProgressStore] to the server.
 *
 * The ordering problem this solves is the server's, not the client's: `/playback/progress` writes
 * whatever it is handed, so a phone reconnecting after two hours offline overwrites a position the
 * browser reached in the meantime. `/playback/sync` takes a `client_updated_at` per item and
 * applies only a strictly newer one, which is why every queued entry carries the wall-clock moment
 * it was *recorded* rather than the moment it is sent.
 *
 * On a server without `batch_progress` this falls back to the single-item endpoint, unchanged and
 * still unordered. That is deliberate: the backend keeps `/playback/progress` working precisely for
 * clients that cannot stamp their writes, and an older server needs it. The queue still helps
 * there — a position recorded offline is retried rather than dropped — it just cannot be ordered.
 *
 * ## On trusting the device clock
 *
 * `client_updated_at` is this phone's wall clock, and a phone whose clock is wrong could in
 * principle stamp a write into the future and win every comparison until real time catches up. The
 * backend already defends the forward direction — it clamps each stamp with `min(stamp, now)`
 * before storing it, so a fast clock cannot push a watermark past the server's own time. A *slow*
 * clock loses conflicts it should have won, which costs a position rather than corrupting one. For
 * a single-user private install that is the right trade, and it matches the contract as written, so
 * the app trusts the device clock rather than trying to detect jumps.
 */
class ProgressSync(
    private val repository: TtsRoadRepository,
    private val store: PendingProgressStore,
) {
    // One flush at a time. The 15s ticker, the pause listener and a reconnect can all ask at once,
    // and two overlapping flushes would send the same entries twice and race on resolving them.
    private val flushing = Mutex()

    /**
     * Send everything waiting, in batches the server will accept.
     *
     * Never throws: this runs from the media service, where a failure has no user to report it to
     * and the correct behaviour is to leave the entry queued for next time.
     */
    suspend fun flush(): ProgressFlushResult = flushing.withLock {
        val queued = store.pending()
        if (queued.isEmpty()) return@withLock ProgressFlushResult(drained = true)

        return@withLock if (repository.currentCapabilities.value.batchProgress) {
            flushBatched(queued)
        } else {
            flushOneAtATime(queued)
        }
    }

    private suspend fun flushBatched(queued: List<PendingProgress>): ProgressFlushResult {
        val limit = repository.playbackSyncBatchLimit()
        var accepted = 0
        var sent = 0
        val overridden = mutableListOf<PlaybackSyncState>()

        for (batch in queued.chunked(limit)) {
            val byChapter = batch.associateBy { it.chapterId }
            val response = runCatching {
                repository.syncProgress(batch.map { it.asSyncItem() })
            }.getOrNull() ?: break // Offline again, or the call failed: keep the rest queued.

            sent += batch.size
            accepted += response.accepted.size

            // Anything the server accepted is done with. So is anything it rejected for a reason
            // that will not change on a retry — leaving those queued would block the queue forever.
            val settled = buildList {
                response.accepted.forEach { byChapter[it.chapterId]?.let(::add) }
                response.rejected
                    .filter { it.reason in TerminalRejectReasons }
                    .forEach { rejected -> byChapter[rejected.chapterId]?.let(::add) }
            }
            store.resolve(settled)

            // A `stale` rejection is the case this whole mechanism exists for: the phone's write
            // lost to a newer one. Hand back what the server actually holds so the caller can show
            // it, rather than letting the phone keep displaying a position that no longer exists.
            val staleChapters = response.rejected
                .filter { it.reason == "stale" }
                .map { it.chapterId }
                .toSet()
            response.serverState
                .filter { it.chapterId in staleChapters }
                .forEach(overridden::add)
        }

        return ProgressFlushResult(
            sent = sent,
            accepted = accepted,
            overriddenByServer = overridden,
            drained = store.isEmpty(),
        )
    }

    /**
     * The `/playback/progress` path, for a server without `batch_progress`.
     *
     * One call per chapter and no ordering, exactly as before this queue existed. The gain here is
     * only that a position recorded while offline is retried instead of discarded.
     */
    private suspend fun flushOneAtATime(queued: List<PendingProgress>): ProgressFlushResult {
        var accepted = 0
        for (entry in queued) {
            val ok = runCatching {
                repository.saveProgress(
                    fictionId = entry.fictionId,
                    chapterId = entry.chapterId,
                    positionSeconds = entry.positionSeconds,
                    isPlayed = entry.isPlayed,
                )
            }.isSuccess
            if (!ok) break // Still offline; keep this and everything after it.
            store.resolve(listOf(entry))
            accepted++
        }
        return ProgressFlushResult(
            sent = accepted,
            accepted = accepted,
            drained = store.isEmpty(),
        )
    }
}

fun PendingProgress.asSyncItem(): PlaybackSyncItem = PlaybackSyncItem(
    chapterId = chapterId,
    positionSeconds = positionSeconds,
    isPlayed = isPlayed,
    clientUpdatedAt = clientUpdatedAt,
)
