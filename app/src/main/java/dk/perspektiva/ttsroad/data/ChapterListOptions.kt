package dk.perspektiva.ttsroad.data

/**
 * Client-side view options for a fiction's chapter list.
 *
 * The whole chapter list is already in memory once a fiction is loaded, so filtering, sorting and
 * bulk played-state updates all happen here rather than costing another round trip to the server.
 */
/**
 * Which rows the chapter list shows, as two independent switches: hide played chapters, and hide
 * chapters with no audio yet.
 *
 * An enum rather than two booleans because it is what is persisted and synced, and the stored name
 * of every value that existed before the switches could be combined has to keep reading back as
 * the same filter. [UnplayedReady] is the combination (#349): "what is left that I can play now" is
 * the list someone working through a serial actually wants.
 */
enum class ChapterFilter(
    val label: String,
    /** Hide chapters already marked played. The server's `hide_played`. */
    val unplayedOnly: Boolean,
    /** Hide chapters the pipeline has not produced audio for yet. Local only. */
    val readyOnly: Boolean,
) {
    All("All", unplayedOnly = false, readyOnly = false),
    Unplayed("Unplayed", unplayedOnly = true, readyOnly = false),
    Ready("Ready", unplayedOnly = false, readyOnly = true),
    UnplayedReady("Unplayed + ready", unplayedOnly = true, readyOnly = true),
    ;

    /** This filter with the played switch set to [on] and the ready switch left alone. */
    fun withUnplayedOnly(on: Boolean): ChapterFilter = of(unplayedOnly = on, readyOnly = readyOnly)

    /** This filter with the ready switch set to [on] and the played switch left alone. */
    fun withReadyOnly(on: Boolean): ChapterFilter = of(unplayedOnly = unplayedOnly, readyOnly = on)

    companion object {
        fun of(unplayedOnly: Boolean, readyOnly: Boolean): ChapterFilter =
            entries.first { it.unplayedOnly == unplayedOnly && it.readyOnly == readyOnly }
    }
}

/** A chapter is "ready" once the pipeline has produced audio for it — the same test the rows use. */
private val ChapterSummary.hasAudio: Boolean
    get() = audio != null

private val ChapterSummary.isPlayed: Boolean
    get() = playback?.isPlayed == true

fun ChapterFilter.matches(chapter: ChapterSummary): Boolean =
    (!unplayedOnly || !chapter.isPlayed) && (!readyOnly || chapter.hasAudio)

/**
 * Order by [ChapterSummary.displayNumber]. Chapters without a number keep the server's order and
 * always sort last, so flipping the direction never strands them in the middle of the list.
 */
fun List<ChapterSummary>.sortedByDisplayNumber(ascending: Boolean): List<ChapterSummary> {
    val (numbered, unnumbered) = partition { it.displayNumber != null }
    val byNumber = compareBy<ChapterSummary> { it.displayNumber ?: 0.0 }
    return numbered.sortedWith(if (ascending) byNumber else byNumber.reversed()) + unnumbered
}

/**
 * A chapter number as it is written: "12" rather than "12.0", but "12.5" kept — chapter numbers are
 * not always whole. Null when the chapter has no number.
 *
 * Lives here rather than in the UI because the find-a-chapter field matches against it. Typing what
 * the row shows has to find that row, and two implementations of "how a number is written" would
 * eventually disagree about it.
 */
fun chapterNumberText(number: Double?): String? = number?.let {
    if (it % 1.0 == 0.0) it.toLong().toString() else it.toString()
}

/**
 * Whether a row matches what was typed into a find-a-chapter field.
 *
 * A several-hundred-chapter serial is unpleasant to scroll, and the two places that list one — the
 * fiction screen and the player's queue sheet — both need this. Deliberately dumb compared to the
 * server-side search in `/api/mobile/search`: this filters rows that are already on screen, works
 * offline, and has no lag. It does not look at chapter *text*; that is what the server search is for.
 *
 * @param title the row's title.
 * @param numberLabel the chapter number as written, or null if it has none.
 * @param query what the user typed. Blank matches everything, so an empty field hides nothing.
 */
fun matchesChapterQuery(title: String, numberLabel: String?, query: String): Boolean {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return true
    if (title.lowercase().contains(needle)) return true
    // Prefix rather than substring on the number: someone typing "17" wants chapter 17 and the
    // chapters just past it, not every chapter with a 17 buried in it — 117, 170, 217 and so on.
    // The title match above still covers a number that appears in the words of the title.
    return numberLabel != null && needle.isNotEmpty() && numberLabel.startsWith(needle)
}

/** Whether this chapter matches what was typed; see [matchesChapterQuery]. */
fun ChapterSummary.matchesQuery(query: String): Boolean = matchesChapterQuery(
    title = resolvedTitle,
    numberLabel = chapterNumberText(displayNumber),
    query = query,
)

/**
 * The rows to draw for the current filter, sort direction and find-a-chapter text.
 *
 * [query] defaults to blank, which matches everything — the list with no text typed is the list
 * exactly as it was before there was a field to type into.
 */
fun List<ChapterSummary>.chapterView(
    filter: ChapterFilter,
    ascending: Boolean,
    query: String = "",
): List<ChapterSummary> =
    this.filter { filter.matches(it) && it.matchesQuery(query) }.sortedByDisplayNumber(ascending)

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

/**
 * Ids of [chapterId] and every chapter after it in reading order, regardless of the direction the
 * list is currently sorted in. Empty when [chapterId] is not in the list.
 *
 * The undo for falling asleep with autoplay on: the player carried on through chapters nobody
 * heard and marked them played, and the fix is "from here on, I have not heard these" (#349).
 */
fun List<ChapterSummary>.chapterIdsFrom(chapterId: Int): List<Int> {
    val ordered = sortedByDisplayNumber(ascending = true)
    val index = ordered.indexOfFirst { it.resolvedChapterId == chapterId }
    if (index < 0) return emptyList()
    return ordered.drop(index).mapNotNull { it.resolvedChapterId.takeIf { id -> id > 0 } }
}

/**
 * Ids of the chapters numbered from [from] to [to], both inclusive, in either order — "400 to 650"
 * and "650 to 400" are the same request (#512). Unnumbered chapters never match a range.
 */
fun List<ChapterSummary>.chapterIdsInRange(from: Double, to: Double): List<Int> {
    val low = minOf(from, to)
    val high = maxOf(from, to)
    return sortedByDisplayNumber(ascending = true)
        .filter { it.displayNumber?.let { n -> n in low..high } == true }
        .mapNotNull { it.resolvedChapterId.takeIf { id -> id > 0 } }
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
