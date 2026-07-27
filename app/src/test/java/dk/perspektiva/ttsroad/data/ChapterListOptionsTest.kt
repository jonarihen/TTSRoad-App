package dk.perspektiva.ttsroad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterListOptionsTest {

    private fun chapter(
        id: Int,
        number: Double?,
        played: Boolean = false,
        hasAudio: Boolean = true,
    ) = ChapterSummary(
        id = id,
        displayNumber = number,
        audio = if (hasAudio) AudioInfo(url = "https://example.test/$id.mp3") else null,
        playback = PlaybackInfo(isPlayed = played),
    )

    private val chapters = listOf(
        chapter(id = 1, number = 1.0, played = true),
        chapter(id = 2, number = 2.0, played = true),
        chapter(id = 3, number = 3.0),
        chapter(id = 4, number = 4.0, hasAudio = false),
    )

    @Test
    fun `all filter keeps every chapter in reading order`() {
        val view = chapters.chapterView(ChapterFilter.All, ascending = true)

        assertEquals(listOf(1, 2, 3, 4), view.map { it.resolvedChapterId })
    }

    @Test
    fun `unplayed filter hides played chapters`() {
        val view = chapters.chapterView(ChapterFilter.Unplayed, ascending = true)

        assertEquals(listOf(3, 4), view.map { it.resolvedChapterId })
    }

    @Test
    fun `ready filter keeps only chapters with audio`() {
        val view = chapters.chapterView(ChapterFilter.Ready, ascending = true)

        assertEquals(listOf(1, 2, 3), view.map { it.resolvedChapterId })
    }

    @Test
    fun `descending sort reverses the list`() {
        val view = chapters.chapterView(ChapterFilter.All, ascending = false)

        assertEquals(listOf(4, 3, 2, 1), view.map { it.resolvedChapterId })
    }

    @Test
    fun `chapters without a display number sort last in both directions`() {
        val mixed = listOf(
            chapter(id = 9, number = null),
            chapter(id = 1, number = 1.0),
            chapter(id = 2, number = 2.0),
        )

        assertEquals(listOf(1, 2, 9), mixed.sortedByDisplayNumber(ascending = true).map { it.resolvedChapterId })
        assertEquals(listOf(2, 1, 9), mixed.sortedByDisplayNumber(ascending = false).map { it.resolvedChapterId })
    }

    @Test
    fun `ids before a chapter are everything earlier in reading order`() {
        assertEquals(listOf(1, 2), chapters.chapterIdsBefore(chapterId = 3))
    }

    @Test
    fun `ids before is unaffected by the list order it is called on`() {
        val descending = chapters.sortedByDisplayNumber(ascending = false)

        assertEquals(listOf(1, 2), descending.chapterIdsBefore(chapterId = 3))
    }

    @Test
    fun `ids before is empty for the first chapter and for an unknown chapter`() {
        assertEquals(emptyList<Int>(), chapters.chapterIdsBefore(chapterId = 1))
        assertEquals(emptyList<Int>(), chapters.chapterIdsBefore(chapterId = 999))
    }

    @Test
    fun `all chapter ids skips chapters without a usable id`() {
        val withUnidentified = chapters + chapter(id = 0, number = 5.0)

        assertEquals(listOf(1, 2, 3, 4), withUnidentified.allChapterIds())
    }

    @Test
    fun `withPlayed updates only the targeted chapters`() {
        val updated = chapters.withPlayed(listOf(3, 4), played = true)

        assertTrue(updated.single { it.resolvedChapterId == 3 }.playback?.isPlayed == true)
        assertTrue(updated.single { it.resolvedChapterId == 4 }.playback?.isPlayed == true)
        // Untouched rows keep their identity, so Compose does not redraw them.
        assertEquals(chapters[0], updated[0])
    }

    @Test
    fun `withPlayed can clear played state`() {
        val updated = chapters.withPlayed(listOf(1), played = false)

        assertFalse(updated.single { it.resolvedChapterId == 1 }.playback?.isPlayed == true)
    }

    @Test
    fun `withPlayed fills in missing playback info`() {
        val withoutPlayback = listOf(ChapterSummary(id = 7, displayNumber = 7.0))

        val updated = withoutPlayback.withPlayed(listOf(7), played = true)

        assertTrue(updated.single().playback?.isPlayed == true)
    }

    @Test
    fun `withPlayed returns the same list when nothing is selected`() {
        assertEquals(chapters, chapters.withPlayed(emptyList(), played = true))
    }
}
