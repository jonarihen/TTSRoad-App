package dk.perspektiva.ttsroad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // A 1,200 px viewport throughout: the guards land on 144 px and 660 px, the anchor on 400 px.

    @Test
    fun `a line resting in the band is left alone`() {
        // The page must not twitch under someone reading a sentence they are already part-way
        // through. Anywhere comfortable is good enough; only leaving the band earns a scroll.
        assertNull(readerFollowScrollDelta(lineTopPx = 400, lineBottomPx = 448, viewportHeightPx = 1_200))
        assertNull(readerFollowScrollDelta(lineTopPx = 200, lineBottomPx = 248, viewportHeightPx = 1_200))
        assertNull(readerFollowScrollDelta(lineTopPx = 640, lineBottomPx = 688, viewportHeightPx = 1_200))
    }

    @Test
    fun `a line that has drifted below the band is pulled back to the anchor`() {
        // The bug this exists for: inside a paragraph taller than the screen the highlight used to
        // walk off the bottom and stay gone until the next paragraph yanked it back. A positive
        // delta scrolls forward, so the line ends up at the 400 px anchor.
        assertEquals(300, readerFollowScrollDelta(lineTopPx = 700, lineBottomPx = 748, viewportHeightPx = 1_200))
    }

    @Test
    fun `a line already off the bottom of the screen is pulled back too`() {
        // Where the old paragraph-level anchor left it for seconds at a time.
        assertEquals(1_400, readerFollowScrollDelta(lineTopPx = 1_800, lineBottomPx = 1_848, viewportHeightPx = 1_200))
    }

    @Test
    fun `a line above the band is pushed back down`() {
        // Seeking backwards, or tapping a word near the top of the page.
        assertEquals(-380, readerFollowScrollDelta(lineTopPx = 20, lineBottomPx = 68, viewportHeightPx = 1_200))
    }

    @Test
    fun `a line tall enough to be clipped is corrected even though its top is in the band`() {
        // At the largest font scale one line is a real fraction of the viewport, and a top inside
        // the band is no promise that the whole line is on screen.
        assertEquals(240, readerFollowScrollDelta(lineTopPx = 640, lineBottomPx = 1_300, viewportHeightPx = 1_200))
    }

    @Test
    fun `an unmeasured viewport asks for no scroll rather than a nonsense one`() {
        assertNull(readerFollowScrollDelta(lineTopPx = 0, lineBottomPx = 48, viewportHeightPx = 0))
        assertNull(readerFollowScrollDelta(lineTopPx = 0, lineBottomPx = 48, viewportHeightPx = -50))
    }

    @Test
    fun `a line already exactly on the anchor asks for nothing rather than a zero scroll`() {
        // Distinct from "scroll by 0": null is what stops the caller animating at all.
        assertNull(readerFollowScrollDelta(lineTopPx = 400, lineBottomPx = 448, viewportHeightPx = 1_200))
    }

    @Test
    fun `the correction always lands the line back inside the band`() {
        // The property that matters more than any single number: wherever the line was, one
        // correction is enough — a scroll that left it still outside would fire again immediately.
        val viewport = 1_200
        for (top in -500..2_000 step 7) {
            val bottom = top + 48
            val delta = readerFollowScrollDelta(top, bottom, viewport) ?: continue
            val settledTop = top - delta
            assertTrue(
                "line at $top settled to $settledTop",
                settledTop >= viewport * 0.12f && settledTop <= viewport * 0.55f,
            )
        }
    }
}
