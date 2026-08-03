package dk.perspektiva.ttsroad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cue engine's lookup half.
 *
 * A chapter is tens of thousands of cues long and the reader asks "which word is being spoken?"
 * on every frame, so the lookup has to be a binary search and it has to answer sanely for every
 * position the player can report — including positions outside the timed range, which is exactly
 * what skip-silence and a duration mismatch produce.
 */
class ReadAlongCueLookupTest {

    // "The knight rode north." — cue spans are half-open, matching the server contract.
    private val text = "The knight rode north.\n\nSnow fell on the pass."

    private fun cue(start: Int, end: Int, seconds: Double) = ReadAlongCue(TextSpan(start, end), seconds)

    private val cues = listOf(
        cue(0, 3, 0.0),      // The
        cue(4, 10, 0.42),    // knight
        cue(11, 15, 0.98),   // rode
        cue(16, 21, 1.30),   // north
    )

    private fun document(
        cues: List<ReadAlongCue> = this.cues,
        audioDurationSeconds: Double = 4.0,
    ) = ReadAlongDocument(
        text = text,
        paragraphs = listOf(TextSpan(0, 22), TextSpan(24, 46)),
        cues = cues,
        audioDurationSeconds = audioDurationSeconds,
    )

    @Test
    fun `an empty document has nothing to highlight`() {
        val empty = document(cues = emptyList())

        assertEquals(-1, empty.cueIndexAt(0.0))
        assertEquals(-1, empty.cueIndexAt(12.5))
        assertFalse(empty.hasTimings)
        assertEquals(ReadAlongHighlight.None, empty.highlightAt(12.5))
    }

    @Test
    fun `a single cue is active from its start onwards`() {
        val single = document(cues = listOf(cue(0, 3, 1.5)))

        assertEquals(-1, single.cueIndexAt(1.49))
        assertEquals(0, single.cueIndexAt(1.5))
        assertEquals(0, single.cueIndexAt(3.9))
        assertTrue(single.hasTimings)
    }

    @Test
    fun `a position before the first cue highlights nothing`() {
        // A chapter can open on a title read or a musical sting the server did not time. Guessing
        // the first word there would highlight a line nobody is speaking yet.
        val document = document(cues = cues.map { it.copy(startSeconds = it.startSeconds + 2.0) })

        assertEquals(-1, document.cueIndexAt(0.0))
        assertEquals(-1, document.cueIndexAt(1.999))
        assertEquals(0, document.cueIndexAt(2.0))
    }

    @Test
    fun `a position exactly on a boundary belongs to the cue that starts there`() {
        val document = document()

        assertEquals(0, document.cueIndexAt(0.0))
        assertEquals(1, document.cueIndexAt(0.42))
        assertEquals(2, document.cueIndexAt(0.98))
        assertEquals(3, document.cueIndexAt(1.30))
    }

    @Test
    fun `a position between two cues belongs to the earlier one`() {
        val document = document()

        // A cue runs until the next one starts, so the gaps belong to the word still being spoken.
        assertEquals(0, document.cueIndexAt(0.20))
        assertEquals(1, document.cueIndexAt(0.97))
        assertEquals(2, document.cueIndexAt(1.29))
    }

    @Test
    fun `the last cue runs to the audio duration`() {
        val document = document(audioDurationSeconds = 4.0)

        assertEquals(3, document.cueIndexAt(1.31))
        assertEquals(3, document.cueIndexAt(3.99))
        assertEquals(4.0, document.cueEndSeconds(3), 0.0001)
    }

    @Test
    fun `a position far past the end keeps the last cue rather than blanking the highlight`() {
        // Skip-silence shortens the media timeline relative to the file the timings were derived
        // from, and a chapter's reported duration can disagree with the server's by seconds. Losing
        // the highlight at the end of every chapter would read as a bug, so the lookup clamps.
        val document = document(audioDurationSeconds = 4.0)

        assertEquals(3, document.cueIndexAt(4.0))
        assertEquals(3, document.cueIndexAt(600.0))
        assertEquals(3, document.cueIndexAt(1_000_000.0))
    }

    @Test
    fun `a negative position highlights nothing`() {
        val document = document()

        assertEquals(-1, document.cueIndexAt(-1.0))
        assertEquals(-1, document.cueIndexAt(-1_000_000.0))
    }

    @Test
    fun `cue end times chain to the next cue, and the last to the audio duration`() {
        val document = document(audioDurationSeconds = 4.0)

        assertEquals(0.42, document.cueEndSeconds(0), 0.0001)
        assertEquals(0.98, document.cueEndSeconds(1), 0.0001)
        assertEquals(1.30, document.cueEndSeconds(2), 0.0001)
        assertEquals(4.0, document.cueEndSeconds(3), 0.0001)
    }

    @Test
    fun `looking up in a ten thousand cue document is a binary search, not a scan`() {
        val many = (0 until 10_000).map { cue(it * 5, it * 5 + 4, it * 0.25) }
        val counting = ProbeCountingList(many)
        val document = document(cues = counting, audioDurationSeconds = 2_500.0)

        assertEquals(9_999, document.cueIndexAt(2_499.75))

        // log2(10_000) is ~13.3, so a correct binary search probes about 14 elements. The bound is
        // loose enough to survive an implementation tweak and still fail loudly on a linear scan.
        assertTrue(
            "a scan would probe thousands of cues, this probed ${counting.probes}",
            counting.probes <= 20,
        )
    }

    @Test
    fun `every position in a large document resolves to the cue that covers it`() {
        val many = (0 until 2_000).map { cue(it * 5, it * 5 + 4, it * 0.25) }
        val document = document(cues = many, audioDurationSeconds = 500.0)

        for (index in many.indices) {
            val start = many[index].startSeconds
            assertEquals(index, document.cueIndexAt(start))
            assertEquals(index, document.cueIndexAt(start + 0.24))
        }
    }
}

/**
 * Counts element reads so a lookup's cost can be asserted structurally, without timing anything —
 * a wall-clock benchmark on a build machine is noise, not a test.
 */
private class ProbeCountingList(
    private val delegate: List<ReadAlongCue>,
) : AbstractList<ReadAlongCue>() {
    var probes = 0
        private set

    override val size: Int get() = delegate.size

    override fun get(index: Int): ReadAlongCue {
        probes++
        return delegate[index]
    }
}
