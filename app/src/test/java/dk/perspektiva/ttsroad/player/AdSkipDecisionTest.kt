package dk.perspektiva.ttsroad.player

import dk.perspektiva.ttsroad.data.ChapterSkips
import dk.perspektiva.ttsroad.data.SkipSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdSkipDecisionTest {
    private val skips = ChapterSkips(
        chapterId = 10,
        segments = listOf(SkipSegment(10_000, 20_000, "rule")),
    )

    @Test
    fun `position within near-edge tolerance skips`() {
        assertEquals(20_000L, adSkipTarget(skips, 9_750, 60_000)?.targetMs)
    }

    @Test
    fun `position before near-edge tolerance does not skip`() {
        assertNull(adSkipTarget(skips, 9_749, 60_000))
    }

    @Test
    fun `position within end tolerance does not perform a tiny seek`() {
        assertNull(adSkipTarget(skips, 19_750, 60_000))
    }

    @Test
    fun `trailing region seeks to chapter end`() {
        val trailing = ChapterSkips(
            chapterId = 10,
            segments = listOf(SkipSegment(50_000, 59_000, "tail")),
        )
        assertEquals(60_000L, adSkipTarget(trailing, 51_000, 60_000)?.targetMs)
    }

    @Test
    fun `unknown duration uses segment end`() {
        assertEquals(20_000L, adSkipTarget(skips, 12_000, 0)?.targetMs)
    }

    @Test
    fun `length formatting matches short and minute skips`() {
        assertEquals("1s", formatAdSkipLength(250))
        assertEquals("31s", formatAdSkipLength(31_300))
        assertEquals("1m 05s", formatAdSkipLength(65_000))
    }
}
