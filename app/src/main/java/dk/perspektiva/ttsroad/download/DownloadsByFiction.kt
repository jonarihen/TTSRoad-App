package dk.perspektiva.ttsroad.download

import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.media.TtsRoadMediaIds

/** What one fiction has fully on disk. [fictionId] 0 gathers downloads whose record names no fiction. */
data class FictionDownloads(
    val fictionId: Int,
    val chapterIds: List<Int>,
    val bytes: Long,
)

/** Complete downloads grouped by fiction, biggest first: the cross-library overview's rows. */
fun downloadsByFiction(downloads: Map<String, ChapterDownload>): List<FictionDownloads> =
    downloads.entries
        .filter { it.value.state.isAvailableOffline }
        .mapNotNull { (mediaId, download) -> TtsRoadMediaIds.chapterId(mediaId)?.let { it to download } }
        .groupBy { (_, download) -> download.fictionId }
        .map { (fictionId, rows) ->
            FictionDownloads(
                fictionId = fictionId,
                chapterIds = rows.map { it.first }.sorted(),
                bytes = downloadedBytes(rows.map { it.second }),
            )
        }
        .sortedByDescending { it.bytes }

/**
 * Of [chapterIds], the ones [chapters] says have been played. An unknown list (not loaded, offline
 * with nothing stored) yields nothing: never delete on a guess.
 */
fun playedAmong(chapterIds: List<Int>, chapters: List<ChapterSummary>?): List<Int> {
    val played = chapters?.filter { it.resolvedIsPlayed }?.mapTo(HashSet()) { it.resolvedChapterId } ?: return emptyList()
    return chapterIds.filter { it in played }
}
