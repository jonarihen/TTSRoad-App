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
    suspend fun scan(fictionId: Int, downloaded: Set<Int>) {
        if (downloaded.isEmpty()) return
        val response = fetchHashes(fictionId) ?: return
        val check = staleDownloadCheck(
            downloaded = downloaded,
            recorded = record.current(),
            server = response.chapters,
        )
        record.merge(check.adopt)
        // Every ordinary delete already forgets its hash through OfflineDownloads.remove, but a
        // download can also leave the index without passing through it — a cache upgrade, a row
        // the store drops, an install restored onto a phone whose files did not come with it. The
        // scan is the one place that sees the download store and the record side by side, so it is
        // where a record for bytes that are not there gets dropped.
        record.prune(downloaded)
        // Replace this fiction's verdict rather than merging into it: a chapter that was stale and
        // has since been updated has to be able to stop being stale, and a chapter whose download
        // was deleted must not stay in the set.
        val scanned = response.chapters.mapTo(mutableSetOf()) { it.chapterId }
        _staleChapters.value = (_staleChapters.value - scanned) + check.stale
    }

    /**
     * The user has asked for these to be fetched again.
     *
     * Forgetting the recorded hash is what makes this work rather than merely look like it: the next
     * scan then has nothing to compare against, adopts whatever the server now has, and the chapter
     * is fresh by the same rule that made it stale. Clearing the flag optimistically means the row
     * stops nagging the moment the download is queued rather than after the next scan.
     */
    fun markUpdating(chapterIds: Collection<Int>) {
        chapterIds.forEach(record::forget)
        _staleChapters.value = _staleChapters.value - chapterIds
    }

    /** A download that is gone cannot be stale, and its hash describes bytes no longer on disk. */
    fun forget(chapterId: Int) {
        record.forget(chapterId)
        _staleChapters.value = _staleChapters.value - chapterId
    }

    fun clear() {
        record.clear()
        _staleChapters.value = emptySet()
    }
}
