package dk.perspektiva.ttsroad.data

/**
 * Client-side view options for a fiction's chapter list.
 *
 * The whole chapter list is already in memory once a fiction is loaded, so filtering, sorting and
 * bulk played-state updates all happen here rather than costing another round trip to the server.
 */
enum class ChapterFilter(val label: String) {
    All("All"),
    Unplayed("Unplayed"),
    Ready("Ready"),
}

/** A chapter is "ready" once the pipeline has produced audio for it — the same test the rows use. */
private val ChapterSummary.hasAudio: Boolean
    get() = audio != null

private val ChapterSummary.isPlayed: Boolean
    get() = playback?.isPlayed == true

fun ChapterFilter.matches(chapter: ChapterSummary): Boolean = when (this) {
    ChapterFilter.All -> true
    ChapterFilter.Unplayed -> !chapter.isPlayed
    ChapterFilter.Ready -> chapter.hasAudio
}

/**
 * Order by [ChapterSummary.displayNumber]. Chapters without a number keep the server's order and
 * always sort last, so flipping the direction never strands them in the middle of the list.
 */
fun List<ChapterSummary>.sortedByDisplayNumber(ascending: Boolean): List<ChapterSummary> {
    val (numbered, unnumbered) = partition { it.displayNumber != null }
    val byNumber = compareBy<ChapterSummary> { it.displayNumber ?: 0.0 }
    return numbered.sortedWith(if (ascending) byNumber else byNumber.reversed()) + unnumbered
}

/** The rows to draw for the current filter and sort direction. */
fun List<ChapterSummary>.chapterView(filter: ChapterFilter, ascending: Boolean): List<ChapterSummary> =
    this.filter { filter.matches(it) }.sortedByDisplayNumber(ascending)

/**
 * Ids of every chapter that comes before [chapterId] in reading order, regardless of the direction
 * the list is currently sorted in. Empty when [chapterId] is the first chapter or is not in the list.
 */
fun List<ChapterSummary>.chapterIdsBefore(chapterId: Int): List<Int> {
    val ordered = sortedByDisplayNumber(ascending = true)
    val index = ordered.indexOfFirst { it.resolvedChapterId == chapterId }
    if (index <= 0) return emptyList()
    return ordered.take(index).mapNotNull { it.resolvedChapterId.takeIf { id -> id > 0 } }
}

/** Every markable chapter id in the list. */
fun List<ChapterSummary>.allChapterIds(): List<Int> =
    mapNotNull { it.resolvedChapterId.takeIf { id -> id > 0 } }

/**
 * Apply a played/unplayed change locally so the affected rows update in place — marking a few
 * hundred chapters should not cost a full reload of the list.
 */
fun List<ChapterSummary>.withPlayed(chapterIds: Collection<Int>, played: Boolean): List<ChapterSummary> {
    if (chapterIds.isEmpty()) return this
    val ids = chapterIds.toSet()
    return map { chapter ->
        if (chapter.resolvedChapterId !in ids) {
            chapter
        } else {
            chapter.copy(playback = (chapter.playback ?: PlaybackInfo()).copy(isPlayed = played))
        }
    }
}
