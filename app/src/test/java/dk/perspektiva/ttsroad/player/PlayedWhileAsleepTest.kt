package dk.perspektiva.ttsroad.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which chapters a jump back offers to unmark (#342). */
class PlayedWhileAsleepTest {
    private fun snap(at: Long, chapterId: Int, fictionId: Int = 7) = HistorySnapshot(
        timestamp = at,
        mediaId = "chapter:$chapterId",
        fictionId = fictionId,
        chapterId = chapterId,
        title = "Chapter $chapterId",
        fictionTitle = "Book",
        positionMs = 0L,
    )

    @Test
    fun `chapters run through after the jump point, including the one it lands in, are offered`() {
        val fellAsleep = snap(1_000, chapterId = 10)
        val history = listOf(
            snap(500, chapterId = 9),
            fellAsleep,
            snap(2_000, chapterId = 10),
            snap(3_000, chapterId = 11),
            snap(4_000, chapterId = 12),
        )
        assertEquals(mapOf(7 to setOf(10, 11, 12)), chaptersPlayedAfter(history, fellAsleep))
    }

    @Test
    fun `nothing is offered when playback never left the chapter jumped to`() {
        val target = snap(1_000, chapterId = 10)
        val history = listOf(target, snap(2_000, chapterId = 10), snap(3_000, chapterId = 10))
        assertTrue(chaptersPlayedAfter(history, target).isEmpty())
    }

    @Test
    fun `chapters heard before the jump point are left alone`() {
        val target = snap(2_000, chapterId = 10)
        val history = listOf(snap(1_000, chapterId = 9), target, snap(3_000, chapterId = 11))
        assertEquals(mapOf(7 to setOf(10, 11)), chaptersPlayedAfter(history, target))
    }

    @Test
    fun `an auto-advance into another book is grouped under that book`() {
        val target = snap(1_000, chapterId = 10)
        val history = listOf(target, snap(2_000, chapterId = 20, fictionId = 8))
        assertEquals(mapOf(7 to setOf(10), 8 to setOf(20)), chaptersPlayedAfter(history, target))
    }

    @Test
    fun `snapshots without ids cannot be unmarked and are skipped`() {
        val target = snap(1_000, chapterId = 10)
        val history = listOf(target, snap(2_000, chapterId = 0, fictionId = 0), snap(3_000, chapterId = 11))
        assertEquals(mapOf(7 to setOf(10, 11)), chaptersPlayedAfter(history, target))
    }
}
