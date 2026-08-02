package dk.perspektiva.ttsroad.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning the wire payload into a document the reader can draw.
 *
 * Everything the binary search depends on — sorted cues, spans inside the text — is established
 * here, because a single bad row from the server would otherwise make the highlight jump at random
 * for the rest of the chapter.
 */
class ReadAlongDocumentTest {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private fun parse(json: String): ReadAlongResponse =
        requireNotNull(moshi.adapter(ReadAlongResponse::class.java).fromJson(json))

    private val fullPayload = """
        {
          "api_version": 1,
          "chapter": {"id":10,"fiction_id":1,"title":"Chapter 1","chapter_number":1,
                      "audio_duration":1420.5,"has_timings":true,"timing_version":1},
          "text": "The knight rode north.\n\nSnow fell on the pass.",
          "paragraphs": [[0,22],[24,45]],
          "cues": [[0,3,0.0],[4,10,0.42],[11,15,0.98]]
        }
    """

    @Test
    fun `the full payload becomes a document`() {
        val document = ReadAlongDocument.from(parse(fullPayload))

        assertEquals(10, document.chapterId)
        assertEquals(1, document.fictionId)
        assertEquals("Chapter 1", document.title)
        assertEquals(1.0, document.chapterNumber!!, 0.0001)
        assertEquals(1420.5, document.audioDurationSeconds, 0.0001)
        assertTrue(document.text.startsWith("The knight rode north."))
        assertTrue(document.hasTimings)
    }

    @Test
    fun `cues map to half-open character spans and media times`() {
        val document = ReadAlongDocument.from(parse(fullPayload))

        assertEquals(3, document.cues.size)
        assertEquals(TextSpan(0, 3), document.cues[0].span)
        assertEquals(0.0, document.cues[0].startSeconds, 0.0001)
        assertEquals(TextSpan(4, 10), document.cues[1].span)
        assertEquals(0.42, document.cues[1].startSeconds, 0.0001)
        assertEquals("The", document.textIn(document.cues[0].span))
        assertEquals("knight", document.textIn(document.cues[1].span))
    }

    @Test
    fun `paragraphs map to spans in the same string`() {
        val document = ReadAlongDocument.from(parse(fullPayload))

        assertEquals(2, document.paragraphs.size)
        assertEquals("The knight rode north.", document.textIn(document.paragraphs[0]))
        assertTrue(document.textIn(document.paragraphs[1]).startsWith("Snow fell"))
    }

    @Test
    fun `a chapter with no timings still reads as text`() {
        // The single most common non-error outcome: the chapter converted before timing existed.
        val document = ReadAlongDocument.from(
            parse(
                """
                {"api_version":1,
                 "chapter":{"id":11,"fiction_id":1,"title":"Chapter 2","audio_duration":60.0,
                            "has_timings":false},
                 "text":"Nothing was timed here.","paragraphs":[[0,23]],"cues":[]}
                """,
            ),
        )

        assertFalse(document.hasTimings)
        assertEquals(0, document.cues.size)
        assertEquals("Nothing was timed here.", document.text)
        assertEquals(1, document.paragraphs.size)
        assertEquals(1, document.sentences.size)
        assertEquals(ReadAlongHighlight.None, document.highlightAt(10.0))
    }

    @Test
    fun `missing optional fields fall back rather than failing the whole chapter`() {
        val document = ReadAlongDocument.from(
            parse("""{"chapter":{"id":12,"fiction_id":3},"text":"Just words here."}"""),
        )

        assertEquals(12, document.chapterId)
        assertEquals(3, document.fictionId)
        assertNull(document.chapterNumber)
        assertEquals(0.0, document.audioDurationSeconds, 0.0001)
        assertEquals("Just words here.", document.text)
        assertFalse(document.hasTimings)
    }

    @Test
    fun `a payload with no paragraphs is still laid out as paragraphs`() {
        // Rendering an entire chapter as one unbroken block would be unreadable, so blank lines
        // stand in for the ranges the server did not send.
        val document = ReadAlongDocument.from(
            parse("""{"chapter":{"id":13},"text":"First line.\n\nSecond line.","cues":[]}"""),
        )

        assertEquals(2, document.paragraphs.size)
        assertEquals("First line.", document.textIn(document.paragraphs[0]))
        assertEquals("Second line.", document.textIn(document.paragraphs[1]))
    }

    @Test
    fun `cues arriving out of order are sorted, because the lookup is a binary search`() {
        val document = ReadAlongDocument.from(
            parse(
                """{"chapter":{"id":14,"audio_duration":9.0},"text":"one two three",
                    "paragraphs":[[0,13]],"cues":[[8,13,2.0],[0,3,0.0],[4,7,1.0]]}""",
            ),
        )

        assertEquals(listOf(0.0, 1.0, 2.0), document.cues.map { it.startSeconds })
        assertEquals(0, document.cueIndexAt(0.5))
        assertEquals(2, document.cueIndexAt(2.5))
    }

    @Test
    fun `malformed rows are dropped instead of taking the chapter down`() {
        val document = ReadAlongDocument.from(
            parse(
                """{"chapter":{"id":15,"audio_duration":9.0},"text":"one two three",
                    "paragraphs":[[0,13],[5]],"cues":[[0,3,0.0],[4,7],[9,4,1.0],[8,13,2.0]]}""",
            ),
        )

        // The two-element cue has no time, and the reversed span covers nothing.
        assertEquals(2, document.cues.size)
        assertEquals(TextSpan(0, 3), document.cues[0].span)
        assertEquals(TextSpan(8, 13), document.cues[1].span)
        assertEquals(1, document.paragraphs.size)
    }

    @Test
    fun `spans running past the end of the text are clamped`() {
        val document = ReadAlongDocument.from(
            parse(
                """{"chapter":{"id":16,"audio_duration":9.0},"text":"one two",
                    "paragraphs":[[0,900]],"cues":[[0,3,0.0],[4,900,1.0]]}""",
            ),
        )

        assertEquals(TextSpan(0, 7), document.paragraphs[0])
        assertEquals(TextSpan(4, 7), document.cues[1].span)
        assertEquals("two", document.textIn(document.cues[1].span))
    }
}

/** Tapping the text to move playback. */
class ReadAlongSeekTest {

    private val text = "The knight rode north. Snow fell."

    private fun cue(start: Int, end: Int, seconds: Double) = ReadAlongCue(TextSpan(start, end), seconds)

    private val document = ReadAlongDocument(
        text = text,
        paragraphs = listOf(TextSpan(0, text.length)),
        cues = listOf(
            cue(0, 3, 0.0),      // The
            cue(4, 10, 0.4),     // knight
            cue(11, 15, 0.9),    // rode
            cue(16, 21, 1.3),    // north
            cue(23, 27, 2.0),    // Snow
        ),
        audioDurationSeconds = 3.0,
    )

    @Test
    fun `tapping inside a word seeks to that word`() {
        assertEquals(0.4, document.seekSecondsForOffset(6)!!, 0.0001)
        assertEquals(1.3, document.seekSecondsForOffset(18)!!, 0.0001)
    }

    @Test
    fun `tapping the first character of a word seeks to it`() {
        assertEquals(0.9, document.seekSecondsForOffset(11)!!, 0.0001)
    }

    @Test
    fun `tapping punctuation or a gap picks the nearest word`() {
        // Offset 21 is the full stop after "north"; 22 is the space before "Snow".
        assertEquals(1.3, document.seekSecondsForOffset(21)!!, 0.0001)
        assertEquals(2.0, document.seekSecondsForOffset(22)!!, 0.0001)
    }

    @Test
    fun `tapping the start of a paragraph seeks to its first word`() {
        assertEquals(0.0, document.seekSecondsForOffset(document.paragraphs[0].start)!!, 0.0001)
    }

    @Test
    fun `tapping before or past every cue still lands on a real word`() {
        assertEquals(0.0, document.seekSecondsForOffset(-5)!!, 0.0001)
        assertEquals(2.0, document.seekSecondsForOffset(9_000)!!, 0.0001)
    }

    @Test
    fun `a chapter with no cues cannot be seeked by tapping`() {
        val untimed = ReadAlongDocument(text = text, paragraphs = listOf(TextSpan(0, text.length)))

        assertNull(untimed.seekSecondsForOffset(6))
    }

    @Test
    fun `a tapped word resolves even when the document is large`() {
        val many = (0 until 5_000).map { cue(it * 5, it * 5 + 4, it * 0.25) }
        val big = ReadAlongDocument(
            text = "x".repeat(25_000),
            paragraphs = listOf(TextSpan(0, 25_000)),
            cues = many,
            audioDurationSeconds = 1_250.0,
        )

        assertNotNull(big.seekSecondsForOffset(12_501))
        assertEquals(625.0, big.seekSecondsForOffset(12_501)!!, 0.0001)
    }
}
