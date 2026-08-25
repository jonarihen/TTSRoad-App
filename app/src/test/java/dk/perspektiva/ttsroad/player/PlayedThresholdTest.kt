package dk.perspektiva.ttsroad.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When finishing a chapter counts as having played it.
 *
 * This rule lived inline in `TtsRoadMediaService.saveCurrentProgress` as a single expression and
 * had never been tested. It decides whether `is_played` is written to a row the browser also
 * reads, so getting it wrong is visible on every client at once.
 *
 * The two thresholds cross over at about eight and a half minutes. Below that, twenty seconds is
 * more than 4% of the chapter and the tail rule is the permissive one; above it, the percentage is.
 * Both cases are pinned below, because a change to either constant moves that crossover.
 */
class PlayedThresholdTest {
    private val twentyMinutesMs = 1_200_000L
    private val fiveMinutesMs = 300_000L
    private val hourMs = 3_600_000L

    @Test
    fun `the percentage threshold marks an ordinary chapter played`() {
        // 96% of twenty minutes, exactly.
        assertTrue(PlayedThreshold.reached(1_152_000L, twentyMinutesMs, autoMarkEnabled = true))
    }

    @Test
    fun `short of both thresholds is not played`() {
        // 91.7% of twenty minutes, and nowhere near the last twenty seconds.
        assertFalse(PlayedThreshold.reached(1_100_000L, twentyMinutesMs, autoMarkEnabled = true))
        // 83% of an hour.
        assertFalse(PlayedThreshold.reached(3_000_000L, hourMs, autoMarkEnabled = true))
    }

    @Test
    fun `on a long chapter the percentage is the permissive threshold`() {
        // 96.5% of twenty minutes: past the fraction, still 42s from the end, so the tail rule
        // alone would not have fired.
        assertTrue(PlayedThreshold.reached(1_158_000L, twentyMinutesMs, autoMarkEnabled = true))
    }

    @Test
    fun `on a short chapter the tail is the permissive threshold`() {
        // 93.7% of five minutes — below the fraction — but inside the last twenty seconds.
        assertTrue(PlayedThreshold.reached(281_000L, fiveMinutesMs, autoMarkEnabled = true))
    }

    @Test
    fun `a chapter shorter than the tail is played from the first second`() {
        // Documenting existing behaviour rather than endorsing it: when the duration is under
        // twenty seconds, `total - TailMs` is negative and every position clears it. In practice
        // no converted chapter is this short, which is presumably why it has never mattered.
        assertTrue(PlayedThreshold.reached(0L, 10_000L, autoMarkEnabled = true))
    }

    @Test
    fun `nothing is concluded without a duration`() {
        assertFalse(PlayedThreshold.reached(9_999L, null, autoMarkEnabled = true))
        assertFalse(PlayedThreshold.reached(9_999L, 0L, autoMarkEnabled = true))
        assertFalse(PlayedThreshold.reached(9_999L, -1L, autoMarkEnabled = true))
    }

    @Test
    fun `the account preference switches the automatic mark off entirely`() {
        // The regression #119 is about: with auto_mark_played false, no position marks a chapter
        // played, including one at or past the end.
        assertFalse(PlayedThreshold.reached(hourMs, hourMs, autoMarkEnabled = false))
        assertFalse(PlayedThreshold.reached(Long.MAX_VALUE, hourMs, autoMarkEnabled = false))
        assertFalse(PlayedThreshold.reached(1_152_000L, twentyMinutesMs, autoMarkEnabled = false))
    }

    @Test
    fun `the start of a chapter is never played`() {
        assertFalse(PlayedThreshold.reached(0L, hourMs, autoMarkEnabled = true))
        assertFalse(PlayedThreshold.reached(0L, twentyMinutesMs, autoMarkEnabled = true))
    }

    @Test
    fun `a position past the end still counts as played`() {
        // The player can report a position a shade beyond the duration at STATE_ENDED.
        assertTrue(PlayedThreshold.reached(hourMs + 50L, hourMs, autoMarkEnabled = true))
    }
}
