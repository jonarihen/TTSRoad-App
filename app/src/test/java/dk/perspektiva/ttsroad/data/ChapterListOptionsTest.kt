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
    fun `range is inclusive and order of the bounds does not matter`() {
        assertEquals(listOf(2, 3), chapters.chapterIdsInRange(2.0, 3.0))
        assertEquals(listOf(2, 3), chapters.chapterIdsInRange(3.0, 2.0))
        assertEquals(listOf(3), chapters.chapterIdsInRange(3.0, 3.0))
        assertEquals(emptyList<Int>(), chapters.chapterIdsInRange(10.0, 20.0))
    }

    @Test
    fun `range skips unnumbered chapters and returns reading order`() {
        val list = listOf(chapter(9, 9.0), chapter(7, 7.0), chapter(8, null), chapter(5, 5.0))

        assertEquals(listOf(7, 9), list.chapterIdsInRange(0.0, 9.5).filter { it != 5 })
        assertEquals(listOf(5, 7, 9), list.chapterIdsInRange(0.0, 9.5))
    }

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
    fun `unplayed and ready combine`() {
        val view = chapters.chapterView(ChapterFilter.UnplayedReady, ascending = true)

        assertEquals(listOf(3), view.map { it.resolvedChapterId })
    }

    @Test
    fun `the two filter switches toggle independently`() {
        assertEquals(ChapterFilter.UnplayedReady, ChapterFilter.Unplayed.withReadyOnly(true))
        assertEquals(ChapterFilter.UnplayedReady, ChapterFilter.Ready.withUnplayedOnly(true))
        assertEquals(ChapterFilter.Ready, ChapterFilter.UnplayedReady.withUnplayedOnly(false))
        assertEquals(ChapterFilter.Unplayed, ChapterFilter.UnplayedReady.withReadyOnly(false))
        assertEquals(ChapterFilter.All, ChapterFilter.Ready.withReadyOnly(false))
        for (unplayed in listOf(true, false)) {
            for (ready in listOf(true, false)) {
                val filter = ChapterFilter.of(unplayed, ready)
                assertEquals(unplayed, filter.unplayedOnly)
                assertEquals(ready, filter.readyOnly)
            }
        }
    }

    @Test
    fun `ids from a chapter run to the end in reading order whatever the sort`() {
        val shuffled = chapters.reversed()

        assertEquals(listOf(3, 4), shuffled.chapterIdsFrom(3))
        assertEquals(listOf(1, 2, 3, 4), shuffled.chapterIdsFrom(1))
        assertEquals(listOf(4), shuffled.chapterIdsFrom(4))
        assertEquals(emptyList<Int>(), shuffled.chapterIdsFrom(99))
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

    private fun titled(id: Int, number: Double?, title: String) =
        ChapterSummary(id = id, displayNumber = number, title = title)

    private val serial = listOf(
        titled(id = 1, number = 1.0, title = "The Gate Opens"),
        titled(id = 17, number = 17.0, title = "Ashes"),
        titled(id = 18, number = 17.5, title = "Interlude: The Lighthouse"),
        titled(id = 170, number = 170.0, title = "A Long Walk"),
        titled(id = 217, number = 217.0, title = "The Gate Closes"),
    )

    private fun found(query: String) =
        serial.chapterView(ChapterFilter.All, ascending = true, query = query)
            .map { it.resolvedChapterId }

    @Test
    fun `an empty query hides nothing`() {
        // The list with nothing typed has to be the list exactly as it was before there was a
        // field to type into, or every existing caller quietly changes behaviour.
        assertEquals(listOf(1, 17, 18, 170, 217), found(""))
        assertEquals(listOf(1, 17, 18, 170, 217), found("   "))
    }

    @Test
    fun `a title match is a case-insensitive substring`() {
        assertEquals(listOf(1, 217), found("gate"))
        assertEquals(listOf(1, 217), found("GATE"))
        assertEquals(listOf(18), found("lighthouse"))
    }

    @Test
    fun `a number match is a prefix, so typing a chapter number finds that chapter`() {
        // "17" wants chapter 17 and the ones just past it — not 117 or 217, which is what a
        // substring match would drag in and is the reason this is not one.
        assertEquals(listOf(17, 18, 170), found("17"))
        assertEquals(listOf(170), found("170"))
    }

    @Test
    fun `a number typed as written matches, including a half chapter`() {
        // The row shows "17.5", so typing "17.5" has to find it.
        assertEquals(listOf(18), found("17.5"))
    }

    @Test
    fun `the query and the filter both apply`() {
        val played = listOf(
            titled(id = 1, number = 1.0, title = "The Gate Opens")
                .copy(playback = PlaybackInfo(isPlayed = true)),
            titled(id = 217, number = 217.0, title = "The Gate Closes"),
        )

        val view = played.chapterView(ChapterFilter.Unplayed, ascending = true, query = "gate")

        assertEquals(listOf(217), view.map { it.resolvedChapterId })
    }

    @Test
    fun `a query matching nothing gives an empty list rather than everything`() {
        assertEquals(emptyList<Int>(), found("nothing here"))
    }

    @Test
    fun `a chapter with no number is still findable by title`() {
        val unnumbered = listOf(titled(id = 5, number = null, title = "Epilogue"))

        assertEquals(listOf(5), unnumbered.chapterView(ChapterFilter.All, true, "epi").map { it.resolvedChapterId })
        assertEquals(emptyList<Int>(), unnumbered.chapterView(ChapterFilter.All, true, "5").map { it.resolvedChapterId })
    }

    @Test
    fun `chapter numbers are written the way the rows show them`() {
        // The field matches against this, so "what you see is what you type" only holds while the
        // two agree — which is why the UI's label delegates here rather than reimplementing it.
        assertEquals("12", chapterNumberText(12.0))
        assertEquals("12.5", chapterNumberText(12.5))
        assertEquals(null, chapterNumberText(null))
    }

    @Test
    fun `row keys follow the chapter not its position`() {
        val asc = chapters.chapterView(ChapterFilter.All, ascending = true)
        val desc = chapters.chapterView(ChapterFilter.All, ascending = false)

        val byChapter = asc.map { it.resolvedChapterId }.zip(asc.chapterRowKeys()).toMap()
        desc.map { it.resolvedChapterId }.zip(desc.chapterRowKeys()).forEach { (id, key) ->
            assertEquals(byChapter[id], key)
        }
    }

    @Test
    fun `a repeated chapter still gets a unique key`() {
        val keys = (chapters + chapter(id = 2, number = 2.0)).chapterRowKeys()

        assertEquals(keys.size, keys.toSet().size)
    }
}
