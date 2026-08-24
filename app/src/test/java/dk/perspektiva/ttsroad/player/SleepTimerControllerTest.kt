package dk.perspektiva.ttsroad.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The controller is driven by the media service on a short tick, so these tests step a fake clock
 * by hand rather than waiting on real time.
 */
class SleepTimerControllerTest {
    private val controller = SleepTimerController()

    /** Advance the clock in [stepMs] slices, returning the last action produced. */
    private fun run(
        fromMs: Long,
        forMs: Long,
        stepMs: Long = 500L,
        isPlaying: Boolean = true,
        chapterRemainingMs: Long? = null,
    ): SleepTimerAction {
        var action: SleepTimerAction = SleepTimerAction.None
        var now = fromMs
        val until = fromMs + forMs
        while (now <= until) {
            action = controller.tick(now, isPlaying, chapterRemainingMs)
            if (action is SleepTimerAction.Expire) return action
            now += stepMs
        }
        return action
    }

    @Test
    fun `idle controller does nothing`() {
        assertFalse(controller.state.value.isArmed)
        assertEquals(SleepTimerAction.None, controller.tick(0L, isPlaying = true, chapterRemainingMs = null))
    }

    @Test
    fun `arming a duration counts down while playing`() {
        controller.armDuration(30 * 60_000L)
        assertTrue(controller.state.value.isArmed)
        assertEquals(30 * 60_000L, controller.state.value.remainingMs)

        run(fromMs = 0L, forMs = 60_000L)

        // The first tick only seeds the clock; the remaining 60s of ticks are spent.
        assertEquals(29 * 60_000L, controller.state.value.remainingMs)
        assertFalse(controller.state.value.isFading)
    }

    @Test
    fun `a paused player freezes the countdown`() {
        controller.armDuration(30 * 60_000L)
        run(fromMs = 0L, forMs = 10 * 60_000L, isPlaying = false)

        assertEquals(30 * 60_000L, controller.state.value.remainingMs)
        assertTrue(controller.state.value.isArmed)
    }

    @Test
    fun `the last 30 seconds fade proportionally`() {
        controller.armDuration(40_000L)
        // 25s in: 15s left, so roughly half volume.
        run(fromMs = 0L, forMs = 25_000L)

        val state = controller.state.value
        assertTrue("expected a fade, got $state", state.isFading)

        val action = controller.tick(25_500L, isPlaying = true, chapterRemainingMs = null)
        val volume = (action as SleepTimerAction.SetVolume).volume
        assertTrue("volume was $volume", volume in 0.4f..0.6f)
    }

    @Test
    fun `running out pauses playback and disarms`() {
        controller.armDuration(5_000L)
        val action = run(fromMs = 0L, forMs = 6_000L)

        assertEquals(SleepTimerAction.Expire, action)
        assertFalse(controller.state.value.isArmed)
        assertEquals(SleepTimerMode.Off, controller.state.value.mode)
    }

    @Test
    fun `a shake during the fade adds five minutes and restores volume`() {
        controller.armDuration(20_000L)
        run(fromMs = 0L, forMs = 5_000L)
        assertTrue(controller.state.value.isFading)

        controller.extend(SleepTimerController.ExtendMs)
        assertFalse(controller.state.value.isFading)

        val action = controller.tick(5_500L, isPlaying = true, chapterRemainingMs = null)
        assertEquals(SleepTimerAction.SetVolume(1f), action)
        assertTrue(controller.state.value.remainingMs > SleepTimerController.ExtendMs)
    }

    @Test
    fun `cancelling mid-fade restores volume`() {
        controller.armDuration(20_000L)
        run(fromMs = 0L, forMs = 5_000L)
        assertTrue(controller.state.value.isFading)

        controller.cancel()
        assertFalse(controller.state.value.isArmed)
        assertEquals(
            SleepTimerAction.SetVolume(1f),
            controller.tick(5_500L, isPlaying = true, chapterRemainingMs = null),
        )
    }

    @Test
    fun `end of chapter tracks the real playback position`() {
        controller.armEndOfChapter(chapterRemainingMs = 120_000L)
        assertEquals(SleepTimerMode.EndOfChapter, controller.state.value.mode)

        // A seek backwards inside the chapter pushes the boundary out again.
        controller.tick(0L, isPlaying = true, chapterRemainingMs = 120_000L)
        controller.tick(500L, isPlaying = true, chapterRemainingMs = 300_000L)
        assertEquals(300_000L, controller.state.value.remainingMs)
        assertFalse(controller.state.value.isFading)
    }

    @Test
    fun `end of chapter expires at the boundary even though the player already stopped`() {
        controller.armEndOfChapter(chapterRemainingMs = 10_000L)
        controller.tick(0L, isPlaying = true, chapterRemainingMs = 10_000L)

        // pauseAtEndOfMediaItems has stopped playback at the boundary: not playing, nothing left.
        val action = controller.tick(10_000L, isPlaying = false, chapterRemainingMs = 0L)

        assertEquals(SleepTimerAction.Expire, action)
        assertFalse(controller.state.value.isArmed)
    }

    @Test
    fun `arming a non-positive duration is a cancel`() {
        controller.armDuration(30 * 60_000L)
        controller.armDuration(0L)
        assertFalse(controller.state.value.isArmed)
    }

    // --- end of chapter, with a ceiling -------------------------------------------------------

    @Test
    fun `a chapter shorter than the ceiling still stops at its own end`() {
        // The ceiling is a maximum, not a target: it must never make a timer run longer.
        controller.armEndOfChapter(chapterRemainingMs = 600_000L, capMs = 30 * 60_000L)

        assertEquals(600_000L, controller.state.value.remainingMs)
        assertFalse("the chapter ends first here", controller.state.value.willStopAtCap)
    }

    @Test
    fun `a chapter longer than the ceiling counts down to the ceiling`() {
        controller.armEndOfChapter(chapterRemainingMs = 90 * 60_000L, capMs = 30 * 60_000L)

        assertEquals(30 * 60_000L, controller.state.value.remainingMs)
        assertTrue(controller.state.value.willStopAtCap)
    }

    @Test
    fun `the ceiling fires, and the chapter keeps playing until it does`() {
        controller.armEndOfChapter(chapterRemainingMs = 90 * 60_000L, capMs = 60_000L)
        controller.tick(0L, isPlaying = true, chapterRemainingMs = 90 * 60_000L)

        val midway = controller.tick(30_000L, isPlaying = true, chapterRemainingMs = 89 * 60_000L)
        assertNotEquals(SleepTimerAction.Expire, midway)
        assertEquals(30_000L, controller.state.value.remainingMs)

        val expired = controller.tick(60_000L, isPlaying = true, chapterRemainingMs = 88 * 60_000L)
        assertEquals(SleepTimerAction.Expire, expired)
        assertFalse(controller.state.value.isArmed)
    }

    @Test
    fun `a chapter ending before the ceiling still stops at the boundary`() {
        controller.armEndOfChapter(chapterRemainingMs = 20 * 60_000L, capMs = 30 * 60_000L)
        controller.tick(0L, isPlaying = true, chapterRemainingMs = 20 * 60_000L)

        val action = controller.tick(60_000L, isPlaying = false, chapterRemainingMs = 0L)

        assertEquals(SleepTimerAction.Expire, action)
    }

    @Test
    fun `seeking backwards cannot push the stop past the ceiling`() {
        // The reason the ceiling is tracked through tick rather than resolved when arming. A
        // boundary timer follows the real position, so rewinding would otherwise buy back exactly
        // the time the ceiling was there to refuse.
        controller.armEndOfChapter(chapterRemainingMs = 40 * 60_000L, capMs = 30 * 60_000L)
        controller.tick(0L, isPlaying = true, chapterRemainingMs = 40 * 60_000L)

        controller.tick(60_000L, isPlaying = true, chapterRemainingMs = 120 * 60_000L)

        assertEquals(29 * 60_000L, controller.state.value.remainingMs)
        assertTrue(controller.state.value.willStopAtCap)
    }

    @Test
    fun `a pause freezes the ceiling rather than spending it`() {
        controller.armEndOfChapter(chapterRemainingMs = 90 * 60_000L, capMs = 30 * 60_000L)

        // Stepped rather than jumped, because the service ticks twice a second and the freeze is
        // implemented by charging only the time between ticks. A test that skipped from minute one
        // to minute twenty would be measuring an interval that never happens on a phone.
        run(fromMs = 0L, forMs = 60_000L, chapterRemainingMs = 90 * 60_000L)
        run(
            fromMs = 60_000L,
            forMs = 19 * 60_000L,
            isPlaying = false,
            chapterRemainingMs = 90 * 60_000L,
        )
        run(fromMs = 20 * 60_000L, forMs = 1_000L, chapterRemainingMs = 90 * 60_000L)

        // Only the minute that was actually played has gone, plus the second after resuming.
        assertEquals(29 * 60_000L - 1_000L, controller.state.value.remainingMs)
    }

    @Test
    fun `the ceiling fades out like any other countdown`() {
        controller.armEndOfChapter(chapterRemainingMs = 90 * 60_000L, capMs = 60_000L)
        controller.tick(0L, isPlaying = true, chapterRemainingMs = 90 * 60_000L)

        val action = controller.tick(50_000L, isPlaying = true, chapterRemainingMs = 89 * 60_000L)

        assertTrue(controller.state.value.isFading)
        assertTrue(action is SleepTimerAction.SetVolume)
    }

    @Test
    fun `shaking to extend drops the ceiling with the mode`() {
        controller.armEndOfChapter(chapterRemainingMs = 90 * 60_000L, capMs = 60_000L)
        controller.tick(0L, isPlaying = true, chapterRemainingMs = 90 * 60_000L)

        controller.extend(SleepTimerController.ExtendMs)

        assertEquals(SleepTimerMode.Duration, controller.state.value.mode)
        // Re-applying the ceiling here would take the five minutes straight back.
        assertFalse(controller.state.value.isCapped)
        assertEquals(60_000L + SleepTimerController.ExtendMs, controller.state.value.remainingMs)
    }

    @Test
    fun `an uncapped end of chapter timer is unchanged`() {
        controller.armEndOfChapter(chapterRemainingMs = 90 * 60_000L)

        assertFalse(controller.state.value.isCapped)
        assertFalse(controller.state.value.willStopAtCap)
        assertEquals(90 * 60_000L, controller.state.value.remainingMs)
    }

    @Test
    fun `a nonsense ceiling expires at once rather than running forever`() {
        controller.armEndOfChapter(chapterRemainingMs = 90 * 60_000L, capMs = -1L)

        assertEquals(0L, controller.state.value.remainingMs)
        assertEquals(
            SleepTimerAction.Expire,
            controller.tick(0L, isPlaying = true, chapterRemainingMs = 90 * 60_000L),
        )
    }
}
