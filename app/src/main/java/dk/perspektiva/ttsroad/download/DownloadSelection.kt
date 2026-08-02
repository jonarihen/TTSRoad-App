package dk.perspektiva.ttsroad.download

import dk.perspektiva.ttsroad.data.ChapterSummary

/**
 * Which chapters the fiction header's "download next N" should actually queue.
 *
 * Deliberately knows nothing about whether a chapter has been played. Whether a played chapter is
 * worth keeping on disk is an eviction-policy question that has not been decided, and guessing it
 * here would quietly bake the answer in. The caller decides where the run starts by passing
 * [startChapterId]; everything from there forward is fair game.
 *
 * @param chapters the fiction in reading order, as the API returned it.
 * @param alreadyHandled chapter ids the download manager already has — complete, downloading, or
 *   queued. Re-adding those would be a no-op for Media3 but would eat into [limit].
 * @param limit how many chapters to queue. Non-positive queues nothing.
 * @param startChapterId begin at this chapter (inclusive); unknown or null starts at the top, since
 *   downloading from the beginning is far better than downloading nothing.
 */
fun nextChaptersToDownload(
    chapters: List<ChapterSummary>,
    alreadyHandled: Set<Int>,
    limit: Int,
    startChapterId: Int? = null,
): List<ChapterSummary> {
    if (limit <= 0) return emptyList()
    val startIndex = startChapterId
        ?.let { id -> chapters.indexOfFirst { it.resolvedChapterId == id } }
        ?.takeIf { it >= 0 }
        ?: 0

    return chapters.asSequence()
        .drop(startIndex)
        .filter { it.audio != null }
        .filterNot { it.resolvedChapterId in alreadyHandled }
        .distinctBy { it.resolvedChapterId }
        .take(limit)
        .toList()
}

/** How many chapters a "download all" would still have to fetch — the header's subtitle. */
fun remainingToDownload(chapters: List<ChapterSummary>, alreadyHandled: Set<Int>): Int =
    nextChaptersToDownload(chapters, alreadyHandled, limit = Int.MAX_VALUE).size
