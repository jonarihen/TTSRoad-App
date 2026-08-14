package dk.perspektiva.ttsroad.download

import dk.perspektiva.ttsroad.data.ChapterSummary

/**
 * What keep-ahead should do right now: fetch these, let go of those.
 *
 * Both halves are needed together. A window that only ever adds is not a window — it is "download
 * the rest of the book, slowly", and on a long serial that is tens of gigabytes nobody asked for.
 */
data class AutoDownloadPlan(
    /** Chapters inside the window with nothing on disk or in the queue yet. */
    val download: List<ChapterSummary> = emptyList(),
    /** Chapter ids keep-ahead fetched earlier that the window has now moved past. */
    val release: List<Int> = emptyList(),
) {
    val isEmpty: Boolean get() = download.isEmpty() && release.isEmpty()
}

/**
 * Keep the next [keepAhead] chapters of the fiction being listened to on disk, and only those.
 *
 * The window starts at the chapter playing rather than after it, because the common case for this
 * feature is losing signal mid-chapter — a window that began at the next one would leave the
 * chapter actually in progress streaming.
 *
 * ### What it is allowed to delete
 *
 * Only [autoDownloaded] ids, which the caller has already restricted to chapters keep-ahead fetched
 * itself (see [DownloadOrigin]). A chapter the user downloaded by hand is never released, even when
 * it sits well behind the window — [OfflineDownloads] promises that downloads do not vanish behind
 * the user's back, and that promise is worth more than the disk space.
 *
 * Releases are also scoped to the fiction in [chapters], because the caller passes one fiction's
 * chapters at a time. Someone alternating between two books keeps a window in each rather than
 * having the one they are not currently playing repeatedly deleted and re-fetched.
 *
 * ### When it does nothing
 *
 * An unknown [currentChapterId] — null, or an id not in [chapters] — yields an empty plan rather
 * than a guess. Guessing would be wrong twice over: it would download from the top of the book, and
 * an empty window would compute *every* auto chapter as releasable and delete the lot. A chapter
 * list that has not loaded must not be read as "the user is nowhere".
 *
 * @param chapters the fiction in reading order, as the API returned it.
 * @param currentChapterId the chapter playing now.
 * @param keepAhead window size, from Settings. Zero or less switches the feature off, which
 *   releases what it previously fetched — its downloads are its own cache, and leaving them behind
 *   would make turning it off fail to give the space back.
 * @param handled chapter ids the download manager already has, whatever their origin: complete,
 *   downloading, or queued. Re-adding one is a no-op for Media3, but it is also pointless traffic.
 * @param autoDownloaded chapter ids of *this fiction* that keep-ahead fetched.
 */
fun autoDownloadPlan(
    chapters: List<ChapterSummary>,
    currentChapterId: Int?,
    keepAhead: Int,
    handled: Set<Int> = emptySet(),
    autoDownloaded: Set<Int> = emptySet(),
): AutoDownloadPlan {
    if (keepAhead <= 0) return AutoDownloadPlan(release = autoDownloaded.sorted())

    val startIndex = currentChapterId
        ?.let { id -> chapters.indexOfFirst { it.resolvedChapterId == id } }
        ?.takeIf { it >= 0 }
        ?: return AutoDownloadPlan()

    // A chapter still being synthesised has no audio object, so it cannot be downloaded — but it
    // must not silently consume a slot either, or a fiction whose tail is still converting would
    // keep a window of nothing.
    val window = chapters.asSequence()
        .drop(startIndex)
        .filter { it.audio != null }
        .distinctBy { it.resolvedChapterId }
        .take(keepAhead)
        .toList()

    val windowIds = window.mapTo(mutableSetOf()) { it.resolvedChapterId }
    return AutoDownloadPlan(
        download = window.filterNot { it.resolvedChapterId in handled },
        release = autoDownloaded.filterNot { it in windowIds }.sorted(),
    )
}
