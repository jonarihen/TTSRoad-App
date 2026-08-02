package dk.perspektiva.ttsroad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The highlight must be a pure function of the position the player *reports*, never of elapsed
 * wall time.
 *
 * Skip-silence is on by default and removes real time from the media timeline, so the timings the
 * server derived from an unmodified file no longer line up with a clock started at play(). Anything
 * that extrapolates ("0.5s of wall time passed, so advance 0.5s") accumulates that error for the
 * length of a chapter. Reading the reported position instead means every frame re-syncs and the
 * error can never accumulate — these tests are the regression guard for that.
 */
class ReadAlongHighlightTest {

    private val text = "One two three four five six."

    private fun cue(start: Int, end: Int, seconds: Double) = ReadAlongCue(TextSpan(start, end), seconds)

    private val document = ReadAlongDocument(
        text = text,
        paragraphs = listOf(TextSpan(0, text.length)),
        cues = listOf(
            cue(0, 3, 0.0),      // One
            cue(4, 7, 1.0),      // two
            cue(8, 13, 2.0),     // three
            cue(14, 18, 3.0),    // four
            cue(19, 23, 4.0),    // five
            cue(24, 27, 5.0),    // six
        ),
        audioDurationSeconds = 6.0,
    )

    /** A frame-paced sampler fed reported positions; wall time is passed but must never be used. */
    private fun highlightsFor(reportedMs: List<Long>): List<Int> =
        reportedMs.map { document.highlightAtMillis(it).cueIndex }

    @Test
    fun `the highlight follows reported position, not elapsed wall time`() {
        // A 2x listener: one second of wall time per sample, two seconds of media time.
        val wallClockMs = listOf(0L, 1_000L, 2_000L)
        val reportedMs = listOf(0L, 2_000L, 4_000L)

        assertEquals(listOf(0, 2, 4), highlightsFor(reportedMs))
        assertNotEquals(
            "extrapolating from wall time would lag two words behind at 2x",
            highlightsFor(reportedMs),
            highlightsFor(wallClockMs),
        )
    }

    @Test
    fun `a player that reports the same position leaves the highlight still`() {
        // Paused, or buffering: wall time keeps running, the media position does not.
        val frozen = List(10) { 2_400L }

        assertEquals(List(10) { 2 }, highlightsFor(frozen))
    }

    @Test
    fun `a backwards jump in reported position moves the highlight backwards`() {
        // A skip-back, or the player correcting its own reported position after a seek.
        assertEquals(listOf(4, 1, 2), highlightsFor(listOf(4_500L, 1_200L, 2_100L)))
    }

    @Test
    fun `a reported position that jumps forward lands on the right word immediately`() {
        // Skip-silence can advance the reported position further than real time in one step; the
        // highlight must land where the player says it is, not walk there.
        assertEquals(0, document.highlightAtMillis(0L).cueIndex)
        assertEquals(5, document.highlightAtMillis(5_400L).cueIndex)
    }

    @Test
    fun `the same reported position always yields the same highlight`() {
        // No hidden clock, no hidden state: the only input is the reported position.
        val first = document.highlightAtMillis(3_200L)
        val second = document.highlightAtMillis(3_200L)

        assertEquals(first, second)
        assertEquals(3, first.cueIndex)
    }

    @Test
    fun `millisecond and second lookups agree`() {
        assertEquals(document.highlightAt(2.5).cueIndex, document.highlightAtMillis(2_500L).cueIndex)
    }

    @Test
    fun `a negative reported position is tolerated rather than throwing`() {
        // Media3 reports a negative position briefly while a controller is connecting.
        assertEquals(ReadAlongHighlight.None, document.highlightAtMillis(-1L))
    }

    @Test
    fun `the band stays put while the word accent moves inside it`() {
        val early = document.highlightAtMillis(0L)
        val late = document.highlightAtMillis(5_000L)

        assertEquals("one sentence, so one band", early.sentence, late.sentence)
        assertNotEquals(early.word, late.word)
        assertTrue(early.isActive && late.isActive)
    }
}

/**
 * Which affordance a chapter gets. Two independent gates: the server has the feature at all, and
 * this particular chapter has timings.
 */
class ReadAlongAvailabilityTest {

    private val enabled = ServerCapabilities.Baseline.copy(readAlong = true)

    private fun chapter(hasTimings: Boolean?) =
        ChapterSummary(id = 1, fictionId = 2, hasTimings = hasTimings)

    @Test
    fun `a server without read-along offers no reader at all`() {
        assertEquals(
            ReadAlongAvailability.Unavailable,
            readAlongAvailability(ServerCapabilities.Baseline, chapter(hasTimings = true)),
        )
    }

    @Test
    fun `a chapter without timings still opens, as text only`() {
        assertEquals(
            ReadAlongAvailability.TextOnly,
            readAlongAvailability(enabled, chapter(hasTimings = false)),
        )
    }

    @Test
    fun `a timed chapter on a capable server follows along`() {
        assertEquals(
            ReadAlongAvailability.FollowAlong,
            readAlongAvailability(enabled, chapter(hasTimings = true)),
        )
    }

    @Test
    fun `a chapter list that does not mention timings still offers the reader`() {
        // Older chapter payloads have no has_timings field. The loaded document is the authority on
        // whether follow-along works, so hiding the reader here would hide a working feature.
        assertEquals(
            ReadAlongAvailability.FollowAlong,
            readAlongAvailability(enabled, chapter(hasTimings = null)),
        )
    }

    @Test
    fun `only follow-along and text-only offer a reader`() {
        assertTrue(ReadAlongAvailability.FollowAlong.offersReader)
        assertTrue(ReadAlongAvailability.TextOnly.offersReader)
        assertTrue(!ReadAlongAvailability.Unavailable.offersReader)
    }

    @Test
    fun `has_timings is read off the chapter list payload`() {
        assertEquals(true, ChapterSummary(hasTimings = true).hasTimings)
        assertEquals(null, ChapterSummary().hasTimings)
    }
}
