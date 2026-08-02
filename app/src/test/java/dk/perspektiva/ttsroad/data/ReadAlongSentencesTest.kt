package dk.perspektiva.ttsroad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sentence banding.
 *
 * The band, not the word, is the primary follow-along cue: at the 1.5x-2x this app is actually used
 * at, a single highlighted word moves faster than the eye can track and the reader loses the line
 * entirely. So the segmentation has to be good enough that a band never ends mid-sentence.
 */
class ReadAlongSentencesTest {

    private fun paragraphsOf(vararg spans: Pair<Int, Int>) = spans.map { TextSpan(it.first, it.second) }

    private fun segmentsOf(text: String, paragraphs: List<TextSpan> = listOf(TextSpan(0, text.length))) =
        segmentSentences(text, paragraphs).map { text.substring(it.start, it.end) }

    @Test
    fun `a paragraph splits into its sentences`() {
        val text = "The knight rode north. Snow fell on the pass. He did not stop."

        assertEquals(
            listOf("The knight rode north.", "Snow fell on the pass.", "He did not stop."),
            segmentsOf(text),
        )
    }

    @Test
    fun `a sentence never spans a paragraph boundary`() {
        // Two paragraphs, the first without terminal punctuation. Running them together would put
        // the band across a blank line, which reads as the reader losing its place.
        val text = "A shout from the wall\n\nThe gate opened."
        val paragraphs = paragraphsOf(0 to 21, 23 to 39)

        assertEquals(listOf("A shout from the wall", "The gate opened."), segmentsOf(text, paragraphs))
    }

    @Test
    fun `text with no terminal punctuation is one sentence per paragraph`() {
        val text = "chapter twenty three"

        assertEquals(listOf("chapter twenty three"), segmentsOf(text))
    }

    @Test
    fun `an empty or whitespace-only paragraph produces no sentence`() {
        val text = "One.\n\n   \n\nTwo."
        val paragraphs = paragraphsOf(0 to 4, 6 to 9, 11 to 15)

        assertEquals(listOf("One.", "Two."), segmentsOf(text, paragraphs))
    }

    @Test
    fun `an ellipsis or an interrobang closes one sentence, not several`() {
        val text = "He hesitated... Then he ran. What?! Nothing moved."

        assertEquals(
            listOf("He hesitated...", "Then he ran.", "What?!", "Nothing moved."),
            segmentsOf(text),
        )
    }

    @Test
    fun `a closing quote belongs to the sentence it closes`() {
        val text = "\"Go north.\" The captain turned away."

        assertEquals(listOf("\"Go north.\"", "The captain turned away."), segmentsOf(text))
    }

    @Test
    fun `dialogue continued by a lowercase speech tag stays one sentence`() {
        // "Stop." he snarled — grammatically one sentence, and splitting it puts the band on two
        // lines of what the narrator reads as a single breath.
        val text = "\"Stop.\" he snarled at the gate."

        assertEquals(listOf("\"Stop.\" he snarled at the gate."), segmentsOf(text))
    }

    @Test
    fun `an honorific does not end a sentence`() {
        val text = "Mr. Vale bowed. Dr. Rhen did not."

        assertEquals(listOf("Mr. Vale bowed.", "Dr. Rhen did not."), segmentsOf(text))
    }

    @Test
    fun `initials do not end a sentence`() {
        val text = "The book was by J. R. Vale. He never read it."

        assertEquals(listOf("The book was by J. R. Vale.", "He never read it."), segmentsOf(text))
    }

    @Test
    fun `a decimal number does not end a sentence`() {
        val text = "The rope held 3.5 tonnes. Barely."

        assertEquals(listOf("The rope held 3.5 tonnes.", "Barely."), segmentsOf(text))
    }

    @Test
    fun `sentences are trimmed and never overlap`() {
        val text = "One.   Two.   Three."
        val spans = segmentSentences(text, listOf(TextSpan(0, text.length)))

        for (span in spans) {
            assertTrue("span $span is empty", span.end > span.start)
            assertTrue("span $span has padding", !text[span.start].isWhitespace())
            assertTrue("span $span has padding", !text[span.end - 1].isWhitespace())
        }
        for (index in 1 until spans.size) {
            assertTrue("spans overlap at $index", spans[index].start >= spans[index - 1].end)
        }
    }

    @Test
    fun `paragraph spans outside the text are clamped rather than throwing`() {
        // The reader must survive a server that disagrees with itself about the text length.
        val text = "Short."

        assertEquals(listOf("Short."), segmentsOf(text, paragraphsOf(0 to 9_000)))
        assertEquals(emptyList<String>(), segmentsOf(text, paragraphsOf(50 to 60)))
    }
}

/**
 * Mapping the active cue onto its sentence — the step between "which word is being spoken" and
 * "which band do we draw".
 */
class ReadAlongSentenceBandTest {

    private val text = "The knight rode north. Snow fell on the pass."

    private fun cue(start: Int, end: Int, seconds: Double) = ReadAlongCue(TextSpan(start, end), seconds)

    private val document = ReadAlongDocument(
        text = text,
        paragraphs = listOf(TextSpan(0, text.length)),
        cues = listOf(
            cue(0, 3, 0.0),      // The
            cue(4, 10, 0.4),     // knight
            cue(16, 21, 1.2),    // north
            cue(23, 27, 1.8),    // Snow
            cue(28, 32, 2.2),    // fell
        ),
        audioDurationSeconds = 3.0,
    )

    @Test
    fun `several cues in a row map to the same sentence`() {
        val first = document.sentences[0]

        assertEquals(first, document.highlightAt(0.0).sentence)
        assertEquals(first, document.highlightAt(0.4).sentence)
        assertEquals(first, document.highlightAt(1.2).sentence)
    }

    @Test
    fun `crossing into the next sentence moves the band`() {
        assertEquals(document.sentences[0], document.highlightAt(1.7).sentence)
        assertEquals(document.sentences[1], document.highlightAt(1.8).sentence)
        assertEquals(document.sentences[1], document.highlightAt(2.9).sentence)
    }

    @Test
    fun `the word accent sits inside the sentence band`() {
        val highlight = document.highlightAt(2.2)

        assertEquals(TextSpan(28, 32), highlight.word)
        val band = requireNotNull(highlight.sentence)
        assertTrue(highlight.word!!.start >= band.start)
        assertTrue(highlight.word!!.end <= band.end)
        assertEquals("fell", text.substring(28, 32))
    }

    @Test
    fun `no active cue means no band`() {
        val late = ReadAlongDocument(
            text = text,
            paragraphs = listOf(TextSpan(0, text.length)),
            cues = listOf(cue(0, 3, 5.0)),
            audioDurationSeconds = 9.0,
        )

        val highlight = late.highlightAt(1.0)

        assertEquals(ReadAlongHighlight.None, highlight)
        assertEquals(null, highlight.sentence)
        assertEquals(null, highlight.word)
    }
}
