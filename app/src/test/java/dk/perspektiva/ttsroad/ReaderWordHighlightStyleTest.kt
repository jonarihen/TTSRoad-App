package dk.perspektiva.ttsroad

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dk.perspektiva.ttsroad.data.HighlightGranularity
import dk.perspektiva.ttsroad.data.ReadAlongDocument
import dk.perspektiva.ttsroad.data.ReadAlongResponse
import dk.perspektiva.ttsroad.data.TextSpan
import dk.perspektiva.ttsroad.ui.ReaderPalette
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class ReaderWordHighlightStyleTest {
    @get:Rule val compose = createComposeRule()
    private val palette = ReaderPalette(
        background = Color.Black,
        ink = Color.White,
        muted = Color.Gray,
        band = Color.Yellow,
        accent = Color.Red,
        line = Color.DarkGray,
    )

    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        .adapter(ReadAlongResponse::class.java)
    private val wirePayload = """
        {
          "chapter": {"id":10,"audio_duration":4.0,"has_timings":true},
          "text": "\uD834\uDD1E Prelude.\n\nA \uD834\uDD1E\uD840\uDC00 tone. Next sound.",
          "paragraphs": [[0,10],[12,34]],
          "cues": [[12,13,1.0],[14,16,1.5],[18,20,1.8],[23,27,2.5],[28,33,2.8]]
        }
    """

    private fun parseDocument(json: String = wirePayload): ReadAlongDocument =
        ReadAlongDocument.from(requireNotNull(adapter.fromJson(json)))

    private fun renderedText(text: String): AnnotatedString =
        compose.onNodeWithText(text).fetchSemanticsNode().config[SemanticsProperties.Text].single()

    @Test
    fun parsedWireCuesRenderExactSubstringsAndUtf16StyleRanges() {
        val document = parseDocument()
        val paragraph = document.paragraphs[1]
        val text = "A \uD834\uDD1E\uD840\uDC00 tone. Next sound."
        val words = listOf("A", "\uD834\uDD1E\uD840\uDC00", "on", "Next", "sound")
        val wordSpans = listOf(
            TextSpan(0, 1), TextSpan(2, 6), TextSpan(8, 10), TextSpan(13, 17), TextSpan(18, 23),
        )
        assertEquals(TextSpan(13, 37), paragraph)
        assertEquals(wordSpans.map { TextSpan(13 + it.start, 13 + it.end) }, document.cues.map { it.span })
        for ((index, cue) in document.cues.withIndex()) {
            val highlight = document.highlightAt(cue.startSeconds)
            val sentence = if (index < 3) TextSpan(0, 12) else TextSpan(13, 24)
            for (mode in HighlightGranularity.entries) {
                val actual = readerParagraphText(
                    document, paragraph, highlight.sentence, highlight.word, mode, palette,
                )
                val expectedStyles = buildList {
                    if (mode.showsSentence) {
                        add(AnnotatedString.Range(SpanStyle(background = palette.band), sentence.start, sentence.end))
                    }
                    if (mode.showsWord) {
                        add(
                            AnnotatedString.Range(
                                SpanStyle(
                                    color = palette.accent,
                                    background = if (mode == HighlightGranularity.WordOnly) {
                                        palette.band
                                    } else Color.Unspecified,
                                ),
                                wordSpans[index].start,
                                wordSpans[index].end,
                            ),
                        )
                    }
                }
                assertEquals(text, actual.text)
                assertEquals(expectedStyles, actual.spanStyles)
                if (mode.showsWord) {
                    val range = actual.spanStyles.last()
                    assertEquals(words[index], actual.text.substring(range.start, range.end))
                }
                if (mode.showsSentence) {
                    val range = actual.spanStyles.first()
                    assertEquals(
                        if (index < 3) "A \uD834\uDD1E\uD840\uDC00 tone." else "Next sound.",
                        actual.text.substring(range.start, range.end),
                    )
                }
                assertEquals(
                    AnnotatedString("\uD834\uDD1E Prelude."),
                    readerParagraphText(
                        document, document.paragraphs[0], highlight.sentence, highlight.word, mode, palette,
                    ),
                )
            }
        }
    }

    @Test
    fun annotationRangesAreClippedToTheParagraphWithoutExpandingCues() {
        val document = parseDocument()
        val paragraph = document.paragraphs[1]
        for (mode in listOf(HighlightGranularity.WordOnly, HighlightGranularity.SentenceAndWord)) {
            val actual = readerParagraphText(
                document, paragraph, TextSpan(0, 37), TextSpan(11, 19), mode, palette,
            )
            val expectedWord = AnnotatedString.Range(
                SpanStyle(
                    color = palette.accent,
                    background = if (mode == HighlightGranularity.WordOnly) palette.band else Color.Unspecified,
                ),
                0,
                6,
            )
            assertEquals(
                if (mode == HighlightGranularity.WordOnly) listOf(expectedWord)
                else listOf(AnnotatedString.Range(SpanStyle(background = palette.band), 0, 24), expectedWord),
                actual.spanStyles,
            )
            assertEquals("A \uD834\uDD1E\uD840\uDC00", actual.text.substring(0, actual.spanStyles.last().end))
            val endClipped = readerParagraphText(
                document, paragraph, null, TextSpan(31, 40), mode, palette,
            )
            assertEquals(listOf(expectedWord.copy(start = 18, end = 24)), endClipped.spanStyles)
            assertEquals("sound.", endClipped.text.substring(18, 24))
            assertTrue(
                readerParagraphText(
                    document, paragraph, TextSpan(0, 13), TextSpan(37, 40), mode, palette,
                ).spanStyles.isEmpty(),
            )
        }
    }

    @Test
    fun replacingDocumentWithIdenticalSpansUpdatesTheActualParagraph() {
        val original = parseDocument()
        val replacement = parseDocument(wirePayload.replace("tone", "tune"))
        val currentDocument = mutableStateOf(original)
        assertEquals(original.paragraphs, replacement.paragraphs)
        assertEquals(original.highlightAt(1.8), replacement.highlightAt(1.8))
        compose.setContent {
            val document = currentDocument.value
            TtsRoadTheme {
                ReaderParagraph(
                    document = document,
                    span = document.paragraphs[1],
                    highlight = document.highlightAt(1.8),
                    palette = palette,
                    granularity = HighlightGranularity.SentenceAndWord,
                    style = TextStyle(fontSize = 17.sp),
                    isActiveParagraph = false,
                    onActiveLayout = {},
                    onSeekToOffset = {},
                )
            }
        }
        val styles = listOf(
            AnnotatedString.Range(SpanStyle(background = palette.band), 0, 12),
            AnnotatedString.Range(SpanStyle(color = palette.accent), 8, 10),
        )
        val originalText = "A \uD834\uDD1E\uD840\uDC00 tone. Next sound."
        assertEquals(AnnotatedString(originalText, styles), renderedText(originalText))
        compose.runOnIdle { currentDocument.value = replacement }
        val replacementText = "A \uD834\uDD1E\uD840\uDC00 tune. Next sound."
        val actual = renderedText(replacementText)
        assertEquals(AnnotatedString(replacementText, styles), actual)
        assertEquals("un", actual.text.substring(actual.spanStyles.last().start, actual.spanStyles.last().end))
    }

    @Test
    fun changingGranularityWithoutASentenceUpdatesTheActualWordPaint() {
        val document = parseDocument()
        val mode = mutableStateOf(HighlightGranularity.WordOnly)
        val highlight = document.highlightAt(1.5).copy(sentence = null, sentenceIndex = -1)
        compose.setContent {
            TtsRoadTheme {
                ReaderParagraph(
                    document = document,
                    span = document.paragraphs[1],
                    highlight = highlight,
                    palette = palette,
                    granularity = mode.value,
                    style = TextStyle(fontSize = 17.sp),
                    isActiveParagraph = false,
                    onActiveLayout = {},
                    onSeekToOffset = {},
                )
            }
        }
        val text = "A \uD834\uDD1E\uD840\uDC00 tone. Next sound."
        val wordOnly = AnnotatedString.Range(SpanStyle(color = palette.accent, background = palette.band), 2, 6)
        assertEquals(listOf(wordOnly), renderedText(text).spanStyles)
        compose.runOnIdle { mode.value = HighlightGranularity.SentenceAndWord }
        assertEquals(
            listOf(AnnotatedString.Range(SpanStyle(color = palette.accent), 2, 6)),
            renderedText(text).spanStyles,
        )
        compose.runOnIdle { mode.value = HighlightGranularity.WordOnly }
        assertEquals(listOf(wordOnly), renderedText(text).spanStyles)
    }

    @Test
    fun tapsUseTheReplacementDocumentCallbackWithUnchangedTextAndSpans() {
        val original = parseDocument()
        val replacement = parseDocument(wirePayload.replace("1.8", "1.9"))
        val currentDocument = mutableStateOf(original)
        val seeks = mutableListOf<Pair<Int, Long?>>()
        assertEquals(original.text, replacement.text)
        assertEquals(original.paragraphs, replacement.paragraphs)
        compose.setContent {
            val document = currentDocument.value
            TtsRoadTheme {
                ReaderParagraph(
                    document = document,
                    span = document.paragraphs[1],
                    highlight = document.highlightAt(2.0),
                    palette = palette,
                    granularity = HighlightGranularity.WordOnly,
                    style = TextStyle(fontSize = 17.sp),
                    isActiveParagraph = false,
                    onActiveLayout = {},
                    onSeekToOffset = { offset -> seeks.add(offset to document.seekMillisForOffset(offset)) },
                )
            }
        }
        val text = "A \uD834\uDD1E\uD840\uDC00 tone. Next sound."
        fun tapCue() {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                assertTrue(it(layouts))
            }
            val bounds = layouts.single().getBoundingBox(8)
            val position = Offset(bounds.left + bounds.width / 4f, bounds.center.y)
            compose.onNodeWithText(text).performTouchInput { click(position) }
        }
        tapCue()
        compose.runOnIdle { assertEquals(listOf(21 to 1800L), seeks) }
        compose.runOnIdle { currentDocument.value = replacement }
        tapCue()
        compose.runOnIdle { assertEquals(listOf(21 to 1800L, 21 to 1900L), seeks) }
    }

    @Test
    fun highlightingOnlyChangesPaintAttributes() {
        for (mode in listOf(HighlightGranularity.WordOnly, HighlightGranularity.SentenceAndWord)) {
            val style = readerWordStyle(mode, palette)
            assertEquals(SpanStyle(), style.copy(color = Color.Unspecified, background = Color.Unspecified))
            assertEquals(palette.accent, style.color)
        }
    }

    @Test
    fun movingHighlightPreservesWrappingAndGlyphBounds() {
        lateinit var measurer: TextMeasurer
        compose.setContent { measurer = rememberTextMeasurer() }
        compose.runOnIdle {
            val text = "Wide words wandering along a narrow paragraph make every line boundary important."
            val words = Regex("\\S+").findAll(text).map { it.range }.toList()
            val cues = words.mapIndexed { index, word ->
                "[${12 + word.first},${12 + word.last + 1},$index]"
            }.joinToString(",")
            val document = parseDocument(
                """{"text":"\uD834\uDD1E Prelude.\n\n$text",
                    "paragraphs":[[0,10],[12,${12 + text.length}]],"cues":[$cues]}""",
            )
            val paragraph = document.paragraphs[1]
            assertEquals(13, paragraph.start)
            assertEquals(text, document.textIn(paragraph))
            assertEquals(words.size, document.cues.size)
            for (family in listOf(FontFamily.Serif, FontFamily.SansSerif)) {
                for (size in listOf(16.sp, 24.sp, 32.sp)) {
                    for (width in listOf(160, 240, 320)) {
                        val style = TextStyle(fontFamily = family, fontSize = size)
                        val constraints = Constraints(maxWidth = width)
                        val baseline = measurer.measure(text, style, constraints = constraints)
                        assertTrue(baseline.lineCount > 1)
                        for (mode in listOf(HighlightGranularity.WordOnly, HighlightGranularity.SentenceAndWord)) {
                            for ((index, cue) in document.cues.withIndex()) {
                                val highlight = document.highlightAt(cue.startSeconds)
                                val highlighted = readerParagraphText(
                                    document, paragraph, highlight.sentence, highlight.word, mode, palette,
                                )
                                assertEquals(if (mode.showsSentence) 2 else 1, highlighted.spanStyles.size)
                                val wordRange = highlighted.spanStyles.last()
                                assertEquals(words[index].first, wordRange.start)
                                assertEquals(words[index].last + 1, wordRange.end)
                                assertEquals(
                                    text.substring(words[index]),
                                    highlighted.text.substring(wordRange.start, wordRange.end),
                                )
                                if (mode.showsSentence) {
                                    assertEquals(
                                        AnnotatedString.Range(SpanStyle(background = palette.band), 0, text.length),
                                        highlighted.spanStyles.first(),
                                    )
                                }
                                val actual = measurer.measure(highlighted, style, constraints = constraints)
                                assertEquals(baseline.size, actual.size)
                                assertEquals(baseline.lineCount, actual.lineCount)
                                for (line in 0 until baseline.lineCount) {
                                    assertEquals(baseline.getLineStart(line), actual.getLineStart(line))
                                    assertEquals(baseline.getLineEnd(line), actual.getLineEnd(line))
                                }
                                for (offset in text.indices) {
                                    assertEquals(baseline.getBoundingBox(offset), actual.getBoundingBox(offset))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun wordOnlyHighlightsTheWholeWordBackground() {
        assertEquals(palette.band, readerWordStyle(HighlightGranularity.WordOnly, palette).background)
    }

    @Test
    fun sentenceAndWordLeavesTheSentenceBandVisible() {
        assertEquals(
            Color.Unspecified,
            readerWordStyle(HighlightGranularity.SentenceAndWord, palette).background,
        )
    }
}
