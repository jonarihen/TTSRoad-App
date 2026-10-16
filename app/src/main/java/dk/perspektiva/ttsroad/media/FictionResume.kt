package dk.perspektiva.ttsroad.media

import dk.perspektiva.ttsroad.data.ChapterSummary

/**
 * Where a fiction picks up when it is started as a whole — a fiction played from the car, or its
 * "Resume" row (#328). Null means "from the beginning".
 *
 * The server's continue-listening entry wins when it names a chapter in [chapters]: it is the one
 * place that knows which chapter was heard last. Without one, the chapter list itself still says
 * how far the listener got: the furthest chapter that has been started or finished. A started one
 * is resumed; a finished one hands over to the chapter after it. A fiction listened to the end has
 * nowhere left to resume, and one never started begins at chapter 1 — both are null.
 */
internal fun resumeChapterIn(chapters: List<ChapterSummary>, continueChapterId: Int? = null): ChapterSummary? {
    continueChapterId?.let { id -> chapters.firstOrNull { it.resolvedChapterId == id }?.let { return it } }
    val furthest = chapters.indexOfLast { it.resolvedIsPlayed || it.resolvedPositionSeconds > 0.0 }
    if (furthest < 0) return null
    val chapter = chapters[furthest]
    return if (chapter.resolvedIsPlayed) chapters.getOrNull(furthest + 1) else chapter
}
