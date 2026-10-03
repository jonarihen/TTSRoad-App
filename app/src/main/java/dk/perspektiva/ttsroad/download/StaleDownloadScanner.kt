package dk.perspektiva.ttsroad.download

import dk.perspektiva.ttsroad.data.AudioHashesResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Notices when a downloaded chapter has stopped being the chapter the server has (#109).
 *
 * The app could not notice before, and the reason is structural rather than an oversight: the
 * download index keys on the media URL, the URL does not change when a chapter is re-converted, and
 * nothing ever asked the server what the current bytes hash to. So a chapter re-narrated after a
 * voice change, a retag or a text-rule fix keeps playing the old audio until someone deletes the
 * download by hand — and nothing gives them a reason to.
 *
 * The check is deliberately cheap and deliberately quiet. It costs one small request per fiction
 * screen that has downloads, it is gated on the `audio_content_hash` capability, and every way it
 * can fail leaves the download exactly as it was. Nothing here re-downloads anything on its own:
 * bytes over a mobile connection are the user's to spend, so this reports and offers.
 */
class StaleDownloadScanner(
    private val record: AudioHashRecord,
    /** Null when the server cannot answer, which is an ordinary outcome and not an error. */
    private val fetchHashes: suspend (Int) -> AudioHashesResponse?,
) {
    private val _staleChapters = MutableStateFlow<Set<Int>>(emptySet())
    private val serverHashes = mutableMapOf<Int, String>()
    private val completedReplacements = mutableSetOf<Int>()
    private var generation = 0L

    /** Chapter ids whose downloaded audio is provably not what the server has now. */
    val staleChapters: StateFlow<Set<Int>> = _staleChapters.asStateFlow()

    /**
     * Check one fiction's downloads against the server.
     *
     * [downloaded] is **every** chapter currently on disk, not this fiction's. The comparison
     * intersects it with the server's answer, which only names this fiction's, so filtering first
     * would buy nothing and add a second place to get it wrong — and the prune below needs the
     * whole store to tell a record with no file from a record for another book.
     */
    suspend fun scan(fictionId: Int, downloaded: Set<Int>, retained: Set<Int> = downloaded) {
        val present = retained + _staleChapters.value + completedReplacements
        if (present.isEmpty()) return
        val scanGeneration = generation
        val response = fetchHashes(fictionId) ?: return
        if (scanGeneration != generation) return
        val check = staleDownloadCheck(
            downloaded = present,
            recorded = record.current(),
            server = response.chapters,
        )
        response.chapters.forEach { entry ->
            entry.audioSha256?.takeIf { it.isNotBlank() }?.let { serverHashes[entry.chapterId] = it }
        }
        record.merge(check.adopt.filterKeys { it in downloaded })
        // Every ordinary delete already forgets its hash through OfflineDownloads.remove, but a
        // download can also leave the index without passing through it — a cache upgrade, a row
        // the store drops, an install restored onto a phone whose files did not come with it. The
        // scan is the one place that sees the download store and the record side by side, so it is
        // where a record for bytes that are not there gets dropped.
        record.prune(present)
        _staleChapters.value = _staleChapters.value + check.stale
    }

    fun replacementHash(chapterId: Int): String? = serverHashes[chapterId]?.takeIf { chapterId in _staleChapters.value }

    fun replacementCompleted(chapterId: Int, hash: String) {
        if (record.current()[chapterId] == hash) return
        generation++
        completedReplacements += chapterId
        record.merge(mapOf(chapterId to hash))
        _staleChapters.value = _staleChapters.value - chapterId
    }

    /** A download that is gone cannot be stale, and its hash describes bytes no longer on disk. */
    fun forget(chapterId: Int) {
        generation++
        serverHashes.remove(chapterId)
        completedReplacements -= chapterId
        record.forget(chapterId)
        _staleChapters.value = _staleChapters.value - chapterId
    }

    fun clear() {
        generation++
        serverHashes.clear()
        completedReplacements.clear()
        record.clear()
        _staleChapters.value = emptySet()
    }
}
