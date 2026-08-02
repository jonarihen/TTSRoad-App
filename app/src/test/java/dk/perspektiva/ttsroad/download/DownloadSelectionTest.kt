package dk.perspektiva.ttsroad.download

import dk.perspektiva.ttsroad.data.AudioInfo
import dk.perspektiva.ttsroad.data.ChapterSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun chapter(id: Int, playable: Boolean = true) = ChapterSummary(
    id = id,
    fictionId = 1,
    title = "Chapter $id",
    audio = if (playable) AudioInfo(url = "/audio/f/%04d.mp3".format(id)) else null,
)

private fun List<ChapterSummary>.ids() = map { it.resolvedChapterId }

class DownloadSelectionTest {
    private val fiction = (1..10).map { chapter(it) }

    @Test
    fun `takes the next N in reading order`() {
        val picked = nextChaptersToDownload(fiction, alreadyHandled = emptySet(), limit = 3)

        assertEquals(listOf(1, 2, 3), picked.ids())
    }

    @Test
    fun `starts at the chapter the listener is on, not at the beginning of the book`() {
        val picked = nextChaptersToDownload(
            chapters = fiction,
            alreadyHandled = emptySet(),
            limit = 3,
            startChapterId = 5,
        )

        assertEquals(listOf(5, 6, 7), picked.ids())
    }

    @Test
    fun `an unknown start chapter falls back to the beginning rather than downloading nothing`() {
        val picked = nextChaptersToDownload(
            chapters = fiction,
            alreadyHandled = emptySet(),
            limit = 2,
            startChapterId = 9999,
        )

        assertEquals(listOf(1, 2), picked.ids())
    }

    @Test
    fun `skips chapters that are already downloaded or queued`() {
        val picked = nextChaptersToDownload(
            chapters = fiction,
            alreadyHandled = setOf(1, 2, 4),
            limit = 3,
        )

        assertEquals(listOf(3, 5, 6), picked.ids())
    }

    @Test
    fun `skips chapters with no audio yet`() {
        val mixed = listOf(
            chapter(1, playable = false),
            chapter(2),
            chapter(3, playable = false),
            chapter(4),
        )

        val picked = nextChaptersToDownload(mixed, alreadyHandled = emptySet(), limit = 4)

        assertEquals(listOf(2, 4), picked.ids())
    }

    @Test
    fun `asking for more than remains returns everything that is left`() {
        val picked = nextChaptersToDownload(fiction, alreadyHandled = (1..8).toSet(), limit = 25)

        assertEquals(listOf(9, 10), picked.ids())
    }

    @Test
    fun `nothing left to download returns empty`() {
        val picked = nextChaptersToDownload(fiction, alreadyHandled = (1..10).toSet(), limit = 5)

        assertTrue(picked.isEmpty())
    }

    @Test
    fun `a non-positive limit downloads nothing`() {
        assertTrue(nextChaptersToDownload(fiction, emptySet(), limit = 0).isEmpty())
        assertTrue(nextChaptersToDownload(fiction, emptySet(), limit = -4).isEmpty())
    }

    @Test
    fun `an empty fiction returns empty`() {
        assertTrue(nextChaptersToDownload(emptyList(), emptySet(), limit = 5).isEmpty())
    }

    @Test
    fun `the same chapter is never selected twice`() {
        val duplicated = listOf(chapter(1), chapter(1), chapter(2))

        assertEquals(listOf(1, 2), nextChaptersToDownload(duplicated, emptySet(), limit = 5).ids())
    }

    @Test
    fun `how many chapters are still missing, for the header label`() {
        assertEquals(7, remainingToDownload(fiction, alreadyHandled = setOf(1, 2, 3)))
        assertEquals(0, remainingToDownload(fiction, alreadyHandled = (1..10).toSet()))
        assertEquals(
            2,
            remainingToDownload(
                listOf(chapter(1), chapter(2, playable = false), chapter(3)),
                alreadyHandled = emptySet(),
            ),
        )
    }
}
