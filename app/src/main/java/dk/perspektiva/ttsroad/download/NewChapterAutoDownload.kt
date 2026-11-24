package dk.perspektiva.ttsroad.download

import dk.perspektiva.ttsroad.data.ChapterNotificationEntry
import dk.perspektiva.ttsroad.data.ChapterSummary

/**
 * Per fiction, the chapters auto-download should fetch out of [fresh] (#497): the notices the
 * new-chapter poll just saw go pulled → ready, so the backlog an account already had is never
 * fetched. `playable` is the server's word, read off the wire like everywhere else. Off, nothing.
 */
fun newChapterDownloadTargets(
    fresh: List<ChapterNotificationEntry>,
    enabled: Boolean,
): Map<Int, Set<Int>> {
    if (!enabled) return emptyMap()
    return fresh
        .filter { it.playable && it.fiction.id > 0 && it.chapter.id > 0 }
        .groupBy({ it.fiction.id }, { it.chapter.id })
        .mapValues { (_, ids) -> ids.toSet() }
}

/**
 * Of one fiction's [chapters], the [wanted] ones that have audio and that the download manager is
 * not already dealing with — so a chapter downloaded by hand is neither re-queued nor re-tagged.
 */
fun newChaptersToDownload(
    chapters: List<ChapterSummary>,
    wanted: Set<Int>,
    downloads: Map<String, ChapterDownload>,
): List<ChapterSummary> {
    val handled = handledChapterIds(chapters, downloads)
    return chapters
        .filter { it.audio != null && it.resolvedChapterId in wanted && it.resolvedChapterId !in handled }
        .distinctBy { it.resolvedChapterId }
}
