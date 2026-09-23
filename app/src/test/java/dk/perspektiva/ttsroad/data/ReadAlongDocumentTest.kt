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
          "paragraphs": [[0,22],[24,46]],
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
        assertEquals(46, document.text.length)
        assertEquals(TextSpan(24, 46), document.paragraphs[1])
        assertEquals("Snow fell on the pass.", document.textIn(document.paragraphs[1]))
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
    fun `out of bounds spans fall back for paragraphs and are dropped for cues`() {
        val document = ReadAlongDocument.from(
            parse(
                """{"chapter":{"id":16,"audio_duration":9.0},"text":"one two",
                    "paragraphs":[[0,900]],"cues":[[0,3,0.0],[4,900,1.0]]}""",
            ),
        )

        assertEquals(listOf(TextSpan(0, 7)), document.paragraphs)
        assertEquals(listOf(ReadAlongCue(TextSpan(0, 3), 0.0)), document.cues)
    }

    @Test
    fun `code point spans produce exact UTF16 highlights sentences paragraphs and tap roundtrips`() {
        val response = parse(
            """{"text":"𝄞 Prelude.\n\nA 𝄞𠀀 tone. Next sound.",
                "paragraphs":[[0,10],[12,34]],
                "cues":[[0,1,0.0],[2,9,0.2504],[12,13,1.0001],[14,16,1.5001],
                        [17,21,1.8001],[23,27,2.5001],[28,33,2.8001]]}""",
        )
        val document = ReadAlongDocument.from(response)
        val words = listOf("𝄞", "Prelude", "A", "𝄞𠀀", "tone", "Next", "sound")
        val spans = listOf(
            TextSpan(0, 2), TextSpan(3, 10), TextSpan(13, 14), TextSpan(15, 19),
            TextSpan(20, 24), TextSpan(26, 30), TextSpan(31, 36),
        )
        val sentences = listOf("𝄞 Prelude.", "A 𝄞𠀀 tone.", "Next sound.")
        val sentenceIndices = listOf(0, 0, 1, 1, 1, 2, 2)

        assertEquals(34, document.text.codePointCount(0, document.text.length))
        assertEquals(37, document.text.length)
        assertEquals(spans, document.cues.map { it.span })
        assertEquals(listOf(TextSpan(0, 11), TextSpan(13, 37)), document.paragraphs)
        assertEquals(
            listOf("𝄞 Prelude.", "A 𝄞𠀀 tone. Next sound."),
            document.paragraphs.map(document::textIn),
        )
        assertEquals(sentences, document.sentences.map(document::textIn))
        for ((index, cue) in document.cues.withIndex()) {
            val highlight = document.highlightAt(cue.startSeconds)
            assertEquals(words[index], document.textIn(requireNotNull(highlight.word)))
            assertEquals(sentenceIndices[index], highlight.sentenceIndex)
            assertEquals(sentences[sentenceIndices[index]], document.textIn(requireNotNull(highlight.sentence)))
            assertEquals(if (index < 2) 0 else 1, document.paragraphIndexAt(cue.span.start))
            for (offset in cue.span.start until cue.span.end) {
                assertEquals(cue.startSeconds, document.seekSecondsForOffset(offset)!!, 0.0)
                val seekMillis = requireNotNull(document.seekMillisForOffset(offset))
                assertEquals(highlight, document.highlightAtMillis(seekMillis))
            }
        }
        assertEquals(listOf(14.0, 16.0, 1.5001), response.cues[3])
        assertEquals(document, ReadAlongDocument.from(response))
    }

    @Test
    fun `multiple supplementary characters preserve valid wire paragraph boundaries`() {
        val document = ReadAlongDocument.from(
            parse("""{"text":"A 𝄞 𠀀 B","paragraphs":[[0,3],[4,7]],"cues":[[2,3,0],[4,5,1],[6,7,2]]}"""),
        )

        assertEquals(listOf(TextSpan(0, 4), TextSpan(5, 9)), document.paragraphs)
        assertEquals(listOf("A 𝄞", "𠀀 B"), document.paragraphs.map(document::textIn))
        assertEquals(listOf("A 𝄞", "𠀀 B"), document.sentences.map(document::textIn))
        assertEquals(listOf("𝄞", "𠀀", "B"), document.cues.map { document.textIn(it.span) })
    }

    @Test
    fun `nonfinite fractional negative and out of bounds offsets are never rounded or clamped`() {
        val invalidOffsets = listOf(
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            -1.0, -0.5, 0.5, 3.5, 8.0, Int.MAX_VALUE.toDouble() + 1.0, Double.MAX_VALUE,
        )
        for (offset in invalidOffsets) {
            for (row in listOf(listOf(offset, 7.0, 1.0), listOf(0.0, offset, 1.0))) {
                val document = ReadAlongDocument.from(ReadAlongResponse(text = "one two", cues = listOf(row)))
                assertTrue("accepted $row", document.cues.isEmpty())
            }
        }
    }

    @Test
    fun `offset bounds are measured in code points rather than UTF16 units`() {
        val document = ReadAlongDocument.from(
            parse("""{"text":"𝄞 A","paragraphs":[[0,4]],"cues":[[0,1,0],[2,4,1],[3,4,2]]}"""),
        )

        assertEquals(listOf(TextSpan(0, 4)), document.paragraphs)
        assertEquals(listOf(ReadAlongCue(TextSpan(0, 2), 0.0)), document.cues)
    }

    @Test
    fun `only finite nonnegative cue times reachable by a Long millisecond position are accepted`() {
        val invalidTimes = listOf(
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.001,
            Math.nextUp(Long.MAX_VALUE / 1000.0), Long.MAX_VALUE.toDouble(), Double.MAX_VALUE,
        )
        for (time in invalidTimes) {
            val document = ReadAlongDocument.from(
                ReadAlongResponse(
                    text = "one two three",
                    cues = listOf(listOf(0.0, 3.0, 0.0), listOf(4.0, 7.0, time), listOf(8.0, 13.0, 2.0)),
                ),
            )
            assertEquals(listOf(0.0, 2.0), document.cues.map { it.startSeconds })
            assertEquals("one", document.textIn(requireNotNull(document.highlightAt(1.0).word)))
            assertEquals("three", document.textIn(requireNotNull(document.highlightAt(2.0).word)))
        }
    }

    @Test
    fun `empty reversed and incorrectly sized cue rows are dropped`() {
        val rows = listOf(
            emptyList(), listOf(0.0), listOf(0.0, 3.0), listOf(0.0, 3.0, 0.0, 1.0),
            listOf(3.0, 3.0, 0.0), listOf(3.0, 0.0, 0.0),
        )
        for (row in rows) {
            assertTrue(ReadAlongDocument.from(ReadAlongResponse(text = "one", cues = listOf(row))).cues.isEmpty())
        }
    }

    @Test
    fun `invalid partial overlapping or out of order paragraphs never hide or duplicate prose`() {
        val first = listOf(0.0, 6.0)
        val second = listOf(8.0, 15.0)
        val invalidParagraphs = listOf(
            emptyList(),
            listOf(first),
            listOf(second),
            listOf(listOf(1.0, 6.0), second),
            listOf(listOf(0.0, 5.0), second),
            listOf(first, listOf(8.0, 14.0)),
            listOf(second, first),
            listOf(first, first, second),
            listOf(listOf(0.0, 10.0), second),
            listOf(first, listOf(8.0, 900.0)),
            listOf(listOf(-1.0, 6.0), second),
            listOf(first, listOf(8.0, 8.0)),
            listOf(first, listOf(15.0, 8.0)),
            listOf(first, listOf(8.0)),
            listOf(first, emptyList()),
            listOf(first, listOf(8.0, 15.0, 1.0)),
        )
        for (paragraphs in invalidParagraphs) {
            val document = ReadAlongDocument.from(ReadAlongResponse(text = "First.\n\nSecond.", paragraphs = paragraphs))
            assertEquals("paragraphs $paragraphs", listOf(TextSpan(0, 6), TextSpan(8, 15)), document.paragraphs)
            assertEquals(listOf("First.", "Second."), document.paragraphs.map(document::textIn))
            assertEquals(listOf("First.", "Second."), document.sentences.map(document::textIn))
            assertEquals(1, document.paragraphIndexAt(10))
        }
    }

    @Test
    fun `nonfinite and fractional paragraph offsets invalidate the entire layout`() {
        val invalidOffsets = listOf(
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.5, 0.5, 6.5, Double.MAX_VALUE,
        )
        for (offset in invalidOffsets) {
            for (row in listOf(listOf(offset, 15.0), listOf(8.0, offset))) {
                val document = ReadAlongDocument.from(
                    ReadAlongResponse(text = "First.\n\nSecond.", paragraphs = listOf(listOf(0.0, 6.0), row)),
                )
                assertEquals("paragraph $row", listOf("First.", "Second."), document.paragraphs.map(document::textIn))
            }
        }
    }

    @Test
    fun `whitespace gaps and padding do not invalidate complete paragraph coverage`() {
        val document = ReadAlongDocument.from(
            ReadAlongResponse(
                text = " \tFirst. \n\n Second. \t",
                paragraphs = listOf(listOf(2.0, 8.0), listOf(12.0, 19.0)),
            ),
        )

        assertEquals(listOf(TextSpan(2, 8), TextSpan(12, 19)), document.paragraphs)
        assertEquals(listOf("First.", "Second."), document.paragraphs.map(document::textIn))
    }

    @Test
    fun `empty or whitespace only text tolerates invalid paragraphs`() {
        for (text in listOf("", " \t\n\n \r\n")) {
            val document = ReadAlongDocument.from(
                ReadAlongResponse(text = text, paragraphs = listOf(listOf(0.0, 900.0))),
            )
            assertTrue(document.paragraphs.isEmpty())
            assertTrue(document.sentences.isEmpty())
        }
    }

    @Test
    fun `cue spans that move backward or partially overlap are dropped after time sorting`() {
        val document = ReadAlongDocument.from(
            parse(
                """{"text":"one two three",
                    "cues":[[8,13,2],[4,7,1],[0,3,0],[1,2,1.1],[6,10,1.2],[4,8,1.3],[4,6,1.4]]}""",
            ),
        )

        assertEquals(listOf(TextSpan(0, 3), TextSpan(4, 7), TextSpan(8, 13)), document.cues.map { it.span })
        assertEquals(listOf(0.0, 1.0, 2.0), document.cues.map { it.startSeconds })
        for ((index, cue) in document.cues.withIndex()) {
            assertEquals(index, document.highlightAtMillis(document.seekMillisForOffset(cue.span.start)!!).cueIndex)
        }
    }

    @Test
    fun `adjacent cue ranges remain valid`() {
        val document = ReadAlongDocument.from(parse("""{"text":"one","cues":[[0,1,0],[1,2,1],[2,3,2]]}"""))

        assertEquals(listOf(TextSpan(0, 1), TextSpan(1, 2), TextSpan(2, 3)), document.cues.map { it.span })
        assertEquals(1.0, document.seekSecondsForOffset(1)!!, 0.0)
    }

    @Test
    fun `repeated pronunciation spans are retained and taps seek to the start of the expansion`() {
        val document = ReadAlongDocument.from(
            parse("""{"text":"INT rose.","cues":[[0,3,0.1],[0,3,0.2],[0,3,0.3],[4,8,0.4]]}"""),
        )

        assertEquals(4, document.cues.size)
        for (index in 0..2) {
            assertEquals(index, document.cueIndexAt(document.cues[index].startSeconds))
            assertEquals("INT", document.textIn(requireNotNull(document.highlightAt(document.cues[index].startSeconds).word)))
            assertEquals("INT rose.", document.textIn(requireNotNull(document.highlightAt(document.cues[index].startSeconds).sentence)))
        }
        for (offset in 0..3) {
            assertEquals(0.1, document.seekSecondsForOffset(offset)!!, 0.0)
            assertEquals(0, document.highlightAtMillis(document.seekMillisForOffset(offset)!!).cueIndex)
        }
        assertEquals(0.4, document.seekSecondsForOffset(4)!!, 0.0)
    }

    @Test
    fun `spatial validation compares with the last retained cue rather than a rejected outlier`() {
        val document = ReadAlongDocument.from(
            parse(
                """{"text":"INT rose.",
                    "cues":[[0,3,0],[1,8,0.1],[0,3,0.2],[4,8,0.3],[0,3,0.4],[4,8,0.5]]}""",
            ),
        )

        assertEquals(listOf(0.0, 0.2, 0.3, 0.5), document.cues.map { it.startSeconds })
        assertEquals(listOf("INT", "INT", "rose", "rose"), document.cues.map { document.textIn(it.span) })
        assertEquals(0.0, document.seekSecondsForOffset(1)!!, 0.0)
        assertEquals(0.3, document.seekSecondsForOffset(5)!!, 0.0)
    }

    @Test(timeout = 10_000)
    fun `a normal large chapter converts supplementary spans and validates paragraph coverage once`() {
        val count = 50_000
        val response = ReadAlongResponse(
            text = "𝄞a.\n".repeat(count),
            paragraphs = List(count) { listOf(it * 4.0, it * 4.0 + 3.0) },
            cues = List(count) { listOf(it * 4.0, it * 4.0 + 2.0, it * 0.25) },
        )
        val document = ReadAlongDocument.from(response)

        assertEquals(count, document.paragraphs.size)
        assertEquals(count, document.sentences.size)
        assertEquals(count, document.cues.size)
        for (index in 0 until count step 101) {
            assertEquals(TextSpan(index * 5, index * 5 + 4), document.paragraphs[index])
            assertEquals("𝄞a.", document.textIn(document.paragraphs[index]))
            assertEquals("𝄞a", document.textIn(document.cues[index].span))
            assertEquals(index * 250L, document.seekMillisForOffset(index * 5 + 1)!!)
            assertEquals(index, document.highlightAtMillis(index * 250L).cueIndex)
        }
        assertEquals(TextSpan((count - 1) * 5, count * 5 - 1), document.paragraphs.last())
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
    fun `fractional millisecond seeks never leave a paused highlight on the preceding word`() {
        val timed = ReadAlongDocument.from(
            ReadAlongResponse(
                text = "one two three four",
                cues = listOf(
                    listOf(0.0, 3.0, 0.0),
                    listOf(4.0, 7.0, 0.4201),
                    listOf(8.0, 13.0, 0.980999),
                    listOf(14.0, 18.0, 1.000001),
                ),
            ),
        )
        val expectedMillis = listOf(0L, 421L, 981L, 1001L)

        for ((index, cue) in timed.cues.withIndex()) {
            val millis = requireNotNull(timed.seekMillisForOffset(cue.span.start))
            assertEquals(expectedMillis[index], millis)
            assertEquals(cue.startSeconds, timed.seekSecondsForOffset(cue.span.start)!!, 0.0)
            repeat(3) {
                assertEquals(index, timed.highlightAtMillis(millis).cueIndex)
            }
            if (index > 0) {
                assertEquals(index - 1, timed.highlightAtMillis(millis - 1).cueIndex)
            }
        }
    }

    @Test
    fun `rounded down multiplication still seeks after the original start`() {
        val start = 0.1 + 0.041
        val timed = ReadAlongDocument.from(
            ReadAlongResponse(text = "one two", cues = listOf(listOf(0.0, 3.0, 0.0), listOf(4.0, 7.0, start))),
        )

        assertEquals(141.0, start * 1000.0, 0.0)
        assertTrue(141L / 1000.0 < start)
        assertEquals(start, timed.seekSecondsForOffset(5)!!, 0.0)
        assertEquals(142L, timed.seekMillisForOffset(5)!!)
        assertEquals(0, timed.highlightAtMillis(141L).cueIndex)
        assertEquals(timed.highlightAt(start), timed.highlightAtMillis(timed.seekMillisForOffset(5)!!))
    }

    @Test
    fun `rounded up multiplication does not delay an exactly reachable start`() {
        val start = 2.007
        val timed = ReadAlongDocument.from(
            ReadAlongResponse(
                text = "one two three",
                cues = listOf(listOf(0.0, 3.0, 0.0), listOf(4.0, 7.0, start), listOf(8.0, 13.0, 2.0075)),
            ),
        )

        assertTrue(start * 1000.0 > 2007.0)
        assertEquals(start, 2007L / 1000.0, 0.0)
        assertEquals(start, timed.seekSecondsForOffset(5)!!, 0.0)
        assertEquals(2007L, timed.seekMillisForOffset(5)!!)
        assertEquals(0, timed.highlightAtMillis(2006L).cueIndex)
        assertEquals(timed.highlightAt(start), timed.highlightAtMillis(timed.seekMillisForOffset(5)!!))
        assertEquals(2, timed.highlightAtMillis(2008L).cueIndex)
    }

    @Test
    fun `seeks choose the earliest millisecond across floating point neighbors`() {
        for (millis in listOf(1L, 141L, 2007L, 4201L, 9999999L)) {
            val exact = millis / 1000.0
            val cases = listOf(Math.nextDown(exact) to millis, exact to millis, Math.nextUp(exact) to (millis + 1))
            for ((start, expected) in cases) {
                val timed = ReadAlongDocument.from(
                    ReadAlongResponse(text = "one two", cues = listOf(listOf(0.0, 3.0, 0.0), listOf(4.0, 7.0, start))),
                )
                val sought = requireNotNull(timed.seekMillisForOffset(5))

                assertEquals(expected, sought)
                assertTrue(sought / 1000.0 >= start)
                assertTrue((sought - 1) / 1000.0 < start)
                assertEquals(start, timed.seekSecondsForOffset(5)!!, 0.0)
                assertEquals(timed.highlightAt(start), timed.highlightAtMillis(sought))
            }
        }
    }

    @Test(timeout = 1_000)
    fun `large representable timestamps find the first millisecond without linear adjustment or overflow`() {
        val maximum = Long.MAX_VALUE / 1000.0
        for (start in listOf(0.0, Double.MIN_VALUE, 1.0e13, 1.0e15, Math.nextDown(maximum), maximum)) {
            val timed = ReadAlongDocument.from(
                ReadAlongResponse(text = "word", cues = listOf(listOf(0.0, 4.0, start))),
            )
            val sought = requireNotNull(timed.seekMillisForOffset(1))

            assertEquals(1, timed.cues.size)
            assertTrue(sought >= 0L)
            assertTrue(sought / 1000.0 >= start)
            if (sought > 0L) assertTrue((sought - 1) / 1000.0 < start)
            assertEquals(start, timed.seekSecondsForOffset(1)!!, 0.0)
            assertEquals(timed.highlightAt(start), timed.highlightAtMillis(sought))
        }
    }

    @Test(timeout = 1_000)
    fun `unrepresentable manually constructed cue times cannot overflow a millisecond seek`() {
        val starts = listOf(
            -1.0, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
            Math.nextUp(Long.MAX_VALUE / 1000.0), Double.MAX_VALUE,
        )
        for (start in starts) {
            val timed = ReadAlongDocument(text = "word", cues = listOf(cue(0, 4, start)))

            assertEquals(start, timed.seekSecondsForOffset(1)!!, 0.0)
            assertNull(timed.seekMillisForOffset(1))
        }
    }

    @Test
    fun `whole millisecond seeks keep their existing media position`() {
        assertEquals(0L, document.seekMillisForOffset(0)!!)
        assertEquals(400L, document.seekMillisForOffset(6)!!)
        assertEquals(900L, document.seekMillisForOffset(11)!!)
        assertEquals(1300L, document.seekMillisForOffset(18)!!)
        assertEquals(2000L, document.seekMillisForOffset(25)!!)
    }

    @Test
    fun `millisecond seeks share the nearest word and out of range rules`() {
        assertEquals(1300L, document.seekMillisForOffset(21)!!)
        assertEquals(2000L, document.seekMillisForOffset(22)!!)
        assertEquals(0L, document.seekMillisForOffset(Int.MIN_VALUE)!!)
        assertEquals(2000L, document.seekMillisForOffset(Int.MAX_VALUE)!!)
        assertNull(ReadAlongDocument(text = text).seekMillisForOffset(6))
    }

    @Test
    fun `tapping a long repeated expansion still uses a binary search`() {
        val count = 10_000
        var probes = 0
        val cues = object : AbstractList<ReadAlongCue>() {
            override val size: Int get() = count

            override fun get(index: Int): ReadAlongCue {
                probes++
                return cue(0, 3, index * 0.25)
            }
        }
        val repeated = ReadAlongDocument(text = "INT", cues = cues)

        assertEquals(0.0, repeated.seekSecondsForOffset(1)!!, 0.0)
        assertTrue("probed $probes cues", probes <= 40)
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
