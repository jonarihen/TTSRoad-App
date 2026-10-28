package dk.perspektiva.ttsroad.player

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnmarkPlayedOfferTest {
    private fun snap(at: Long, chapterId: Int) = HistorySnapshot(
        timestamp = at,
        mediaId = "chapter:$chapterId",
        fictionId = 7,
        chapterId = chapterId,
        title = "Chapter $chapterId",
        fictionTitle = "Book",
        positionMs = 0L,
    )

    private val fellAsleep = snap(1_000, chapterId = 10)
    private val night = listOf(fellAsleep, snap(2_000, 11), snap(3_000, 12))

    @Test
    fun `a successful jump offers chapters only after landing at the requested snapshot`() = runTest {
        var offered: Map<Int, Set<Int>>? = null
        var jumpedTo: HistorySnapshot? = null
        assertNull(jumpBackAndOffer(fellAsleep, night, { offered = it }) {
            assertNull(offered)
            jumpedTo = it
            null
        })
        assertEquals(fellAsleep, jumpedTo)
        assertEquals(mapOf(7 to setOf(10, 11, 12)), offered)
    }

    @Test
    fun `a failed jump raises no offer and hands back its message`() = runTest {
        val failure = jumpBackAndOffer(fellAsleep, night, { error("Unexpected offer") }) {
            "server unreachable"
        }
        assertEquals("server unreachable", failure)
    }

    @Test
    fun `a jump within one chapter has nothing to unmark`() = runTest {
        var offered = mapOf(7 to setOf(99))
        assertNull(jumpBackAndOffer(fellAsleep, listOf(fellAsleep), { offered = it }) { null })
        assertTrue(offered.isEmpty())
    }
}
