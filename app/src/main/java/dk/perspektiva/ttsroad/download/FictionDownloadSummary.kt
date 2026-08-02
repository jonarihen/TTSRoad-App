package dk.perspektiva.ttsroad.download

import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.media.TtsRoadMediaIds

/** How many chapters one "download next N" queues. */
const val DownloadBatchSize = 10

/** The fiction header's one-line account of what is on disk. */
data class FictionDownloadSummary(
    val downloaded: Int = 0,
    val inFlight: Int = 0,
    val remaining: Int = 0,
    val bytes: Long = 0L,
)

/**
 * Chapters the download manager is already dealing with, so the batch action neither re-queues them
 * nor counts them against its limit.
 *
 * A failed download is deliberately *not* handled: the batch action is the natural place to retry
 * one, and leaving it out would strand a chapter that failed once.
 */
fun handledChapterIds(
    chapters: List<ChapterSummary>,
    downloads: Map<String, ChapterDownload>,
): Set<Int> = chapters.asSequence()
    .map { it.resolvedChapterId }
    .filter { id ->
        val state = downloads[TtsRoadMediaIds.chapter(id)]?.state ?: return@filter false
        state.isAvailableOffline || state.isBusy
    }
    .toSet()

/** Count this fiction's chapters by download state, ignoring downloads from other fictions. */
fun fictionDownloadSummary(
    chapters: List<ChapterSummary>,
    downloads: Map<String, ChapterDownload>,
): FictionDownloadSummary {
    val mine = chapters.asSequence()
        .filter { it.audio != null }
        .distinctBy { it.resolvedChapterId }
        .map { downloads[TtsRoadMediaIds.chapter(it.resolvedChapterId)] }
        .toList()

    return FictionDownloadSummary(
        downloaded = mine.count { it?.state?.isAvailableOffline == true },
        inFlight = mine.count { it?.state?.isBusy == true },
        remaining = mine.count { it?.state?.let { s -> s.isAvailableOffline || s.isBusy } != true },
        bytes = downloadedBytes(mine.filterNotNull()),
    )
}
