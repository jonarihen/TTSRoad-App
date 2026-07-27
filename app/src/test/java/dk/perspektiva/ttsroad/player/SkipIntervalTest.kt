package dk.perspektiva.ttsroad.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class SkipIntervalTest {
    private val chapterMs = 10 * 60 * 1000L

    @Test
    fun `skips forward within the chapter`() {
        assertEquals(
            2 * 60 * 1000L + SkipIntervalMs,
            skipTargetMs(2 * 60 * 1000L, chapterMs, SkipIntervalMs),
        )
    }

    @Test
    fun `skips back within the chapter`() {
        assertEquals(
            2 * 60 * 1000L - SkipIntervalMs,
            skipTargetMs(2 * 60 * 1000L, chapterMs, -SkipIntervalMs),
        )
    }

    /** Forward near the end must stop at the end, not roll over into the next chapter. */
    @Test
    fun `clamps forward to the end of the chapter`() {
        assertEquals(chapterMs, skipTargetMs(chapterMs - 5_000L, chapterMs, SkipIntervalMs))
    }

    /** Back near the start must stop at zero, not jump to the previous chapter. */
    @Test
    fun `clamps back to the start of the chapter`() {
        assertEquals(0L, skipTargetMs(5_000L, chapterMs, -SkipIntervalMs))
    }

    @Test
    fun `unknown duration still clamps at zero`() {
        assertEquals(0L, skipTargetMs(5_000L, C.TIME_UNSET, -SkipIntervalMs))
    }

    @Test
    fun `unknown duration does not cap the forward skip`() {
        assertEquals(
            5_000L + SkipIntervalMs,
            skipTargetMs(5_000L, C.TIME_UNSET, SkipIntervalMs),
        )
    }
}
