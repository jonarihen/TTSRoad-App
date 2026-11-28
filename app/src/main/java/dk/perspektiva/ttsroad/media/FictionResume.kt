package dk.perspektiva.ttsroad.media

import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.parseServerInstant

/**
 * Where a fiction picks up when it is started as a whole — the RESUME button on its screen, a
 * fiction played from the car, or its "Resume" row (#328). Null means "from the beginning".
 *
 * [chapters] must be in reading order. Pass only playable ones where the caller is going to play
 * the answer: a chapter with no audio yet is not somewhere playback can resume.
 *
 * The server's continue-listening entry wins when it names a chapter in [chapters]. Without one,
 * the chapter list itself says where the listener is, in two steps:
 *
 * 1. **A chapter left part-way through**, the one heard most recently — the rule the web player
 *    and the server's continue-listening list use. Played chapters are never candidates: one keeps
 *    the position it finished at, a few seconds short of its end, and choosing by that position
 *    is what sent RESUME to the longest chapter ever heard, ticked off weeks ago (#558).
 * 2. **Otherwise the chapter after the furthest one heard.** When the listener is caught up on an
 *    ongoing serial it stays on that last chapter rather than restarting at chapter 1.
 *
 * Only a fiction never started is null, and begins at chapter 1.
 */
internal fun resumeChapterIn(chapters: List<ChapterSummary>, continueChapterId: Int? = null): ChapterSummary? {
    continueChapterId?.let { id -> chapters.firstOrNull { it.resolvedChapterId == id }?.let { return it } }
    chapters.withIndex()
        .filter { (_, chapter) -> chapter.isPartlyHeard }
        // Ties — an older server that stamps nothing — go to the furthest chapter.
        .maxWithOrNull(compareBy({ it.value.heardAtMillis ?: Long.MIN_VALUE }, { it.index }))
        ?.let { return it.value }
    val furthest = chapters.indexOfLast { it.resolvedIsPlayed || it.resolvedPositionSeconds > 0.0 }
    if (furthest < 0) return null
    return chapters.getOrNull(furthest + 1) ?: chapters[furthest]
}

/**
 * Started and not finished: a position playback would actually resume from. A played chapter, and
 * one stopped inside its last twenty seconds, start from the top instead (#451), so they count as
 * heard rather than as somewhere to pick up.
 */
private val ChapterSummary.isPartlyHeard: Boolean
    get() = startPositionSeconds > 0.0

private val ChapterSummary.heardAtMillis: Long?
    get() = parseServerInstant(playback?.lastListenedAt)?.toEpochMilli()
