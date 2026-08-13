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

class AutoDownloadPlanTest {
    private val fiction = (1..20).map { chapter(it) }

    @Test
    fun `window starts at the chapter playing, not the one after it`() {
        val plan = autoDownloadPlan(fiction, currentChapterId = 5, keepAhead = 3)

        // 5 is included because the common case for this feature is losing signal part way
        // through a chapter, which a window starting at 6 would leave streaming.
        assertEquals(listOf(5, 6, 7), plan.download.ids())
        assertTrue(plan.release.isEmpty())
    }

    @Test
    fun `does not re-queue what is already downloaded or in flight`() {
        val plan = autoDownloadPlan(
            fiction,
            currentChapterId = 5,
            keepAhead = 4,
            handled = setOf(5, 7),
        )

        assertEquals(listOf(6, 8), plan.download.ids())
    }

    @Test
    fun `a chapter already handled still occupies its slot in the window`() {
        // The window is four chapters wide, so it ends at 8 whether or not 5 and 7 are on disk.
        // Sliding it forward to backfill would quietly download further ahead than was asked for.
        val plan = autoDownloadPlan(
            fiction,
            currentChapterId = 5,
            keepAhead = 4,
            handled = setOf(5, 6, 7),
        )

        assertEquals(listOf(8), plan.download.ids())
    }

    @Test
    fun `releases its own downloads once the window has moved past them`() {
        val plan = autoDownloadPlan(
            fiction,
            currentChapterId = 5,
            keepAhead = 3,
            handled = setOf(1, 2, 3, 5, 6, 7),
            autoDownloaded = setOf(1, 2, 3),
        )

        assertTrue(plan.download.isEmpty())
        assertEquals(listOf(1, 2, 3), plan.release)
    }

    @Test
    fun `never releases a chapter the user downloaded by hand`() {
        // 1 and 2 are far behind the window but are not in autoDownloaded, so they were asked for
        // by name. OfflineDownloads promises those do not disappear behind the user's back.
        val plan = autoDownloadPlan(
            fiction,
            currentChapterId = 10,
            keepAhead = 2,
            handled = setOf(1, 2, 10, 11),
            autoDownloaded = emptySet(),
        )

        assertTrue(plan.release.isEmpty())
    }

    @Test
    fun `keeps what is still inside the window when it slides`() {
        val plan = autoDownloadPlan(
            fiction,
            currentChapterId = 6,
            keepAhead = 3,
            handled = setOf(5, 6, 7),
            autoDownloaded = setOf(5, 6, 7),
        )

        assertEquals(listOf(8), plan.download.ids())
        assertEquals(listOf(5), plan.release)
    }

    @Test
    fun `switching the feature off gives back the space it took`() {
        val plan = autoDownloadPlan(
            fiction,
            currentChapterId = 5,
            keepAhead = 0,
            autoDownloaded = setOf(5, 6, 7),
        )

        assertTrue(plan.download.isEmpty())
        assertEquals(listOf(5, 6, 7), plan.release)
    }

    @Test
    fun `switching it off still leaves hand-picked downloads alone`() {
        val plan = autoDownloadPlan(
            fiction,
            currentChapterId = 5,
            keepAhead = 0,
            handled = setOf(1, 2, 3),
            autoDownloaded = emptySet(),
        )

        assertTrue(plan.isEmpty)
    }

    @Test
    fun `an unknown chapter deletes nothing and downloads nothing`() {
        // The dangerous case: an empty window would compute every auto chapter as releasable. A
        // chapter list that has not loaded must not read as "the user is nowhere".
        val plan = autoDownloadPlan(
            fiction,
            currentChapterId = 9999,
            keepAhead = 5,
            autoDownloaded = setOf(1, 2, 3),
        )

        assertTrue(plan.isEmpty)
    }

    @Test
    fun `a null chapter deletes nothing and downloads nothing`() {
        val plan = autoDownloadPlan(
            fiction,
            currentChapterId = null,
            keepAhead = 5,
            autoDownloaded = setOf(1, 2, 3),
        )

        assertTrue(plan.isEmpty)
    }

    @Test
    fun `an empty chapter list is treated as unknown, not as nowhere`() {
        val plan = autoDownloadPlan(
            chapters = emptyList(),
            currentChapterId = 5,
            keepAhead = 5,
            autoDownloaded = setOf(1, 2, 3),
        )

        assertTrue(plan.isEmpty)
    }

    @Test
    fun `chapters still being synthesised do not consume a slot`() {
        val partlyConverted = listOf(
            chapter(1),
            chapter(2),
            chapter(3, playable = false),
            chapter(4, playable = false),
            chapter(5),
        )

        val plan = autoDownloadPlan(partlyConverted, currentChapterId = 1, keepAhead = 3)

        assertEquals(listOf(1, 2, 5), plan.download.ids())
    }

    @Test
    fun `a window running off the end of the book is not an error`() {
        val plan = autoDownloadPlan(fiction, currentChapterId = 19, keepAhead = 10)

        assertEquals(listOf(19, 20), plan.download.ids())
    }

    @Test
    fun `a negative window is read as off`() {
        val plan = autoDownloadPlan(
            fiction,
            currentChapterId = 5,
            keepAhead = -3,
            autoDownloaded = setOf(9),
        )

        assertTrue(plan.download.isEmpty())
        assertEquals(listOf(9), plan.release)
    }
}
