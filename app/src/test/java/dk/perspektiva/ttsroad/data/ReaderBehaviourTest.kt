package dk.perspektiva.ttsroad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader's two behaviours that are easy to get wrong and impossible to notice in review:
 * when it holds the screen awake, and where it scrolls the active line to.
 */
class ReaderBehaviourTest {

    @Test
    fun `the reader holds the screen awake while reading`() {
        assertTrue(shouldKeepReaderScreenOn(preferenceEnabled = true, sleepTimerFading = false))
    }

    @Test
    fun `the sleep timer's fade releases the screen`() {
        // The fade is the user on their way out. Holding a bright screen over someone falling
        // asleep is the exact opposite of what arming the timer asked for, and it outlasts the
        // audio because the reader has no reason of its own to stop.
        assertFalse(shouldKeepReaderScreenOn(preferenceEnabled = true, sleepTimerFading = true))
    }

    @Test
    fun `turning the preference off releases the screen regardless of the timer`() {
        assertFalse(shouldKeepReaderScreenOn(preferenceEnabled = false, sleepTimerFading = false))
        assertFalse(shouldKeepReaderScreenOn(preferenceEnabled = false, sleepTimerFading = true))
    }

    @Test
    fun `the active line is scrolled to the upper third, not the top`() {
        // Reading happens ahead of the highlight, so the line being spoken belongs high on the
        // screen with the rest of the sentence still below it — pinning it to the very top leaves
        // nothing to read into.
        assertEquals(-400, readerAutoScrollOffsetPx(viewportHeightPx = 1_200))
        assertEquals(-600, readerAutoScrollOffsetPx(viewportHeightPx = 1_800))
    }

    @Test
    fun `the scroll offset is never positive, which would push the line off the top`() {
        for (height in listOf(0, 1, 100, 2_400, 10_000)) {
            assertTrue("height $height", readerAutoScrollOffsetPx(height) <= 0)
        }
    }

    @Test
    fun `an unmeasured viewport asks for no offset rather than a nonsense one`() {
        // The first frame runs before the list has been laid out.
        assertEquals(0, readerAutoScrollOffsetPx(viewportHeightPx = 0))
        assertEquals(0, readerAutoScrollOffsetPx(viewportHeightPx = -50))
    }
}
