package dk.perspektiva.ttsroad

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import dk.perspektiva.ttsroad.data.HighlightGranularity
import dk.perspektiva.ttsroad.ui.ReaderPalette
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Rule
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
            for (family in listOf(FontFamily.Serif, FontFamily.SansSerif)) {
                for (size in listOf(16.sp, 24.sp, 32.sp)) {
                    for (width in listOf(160, 240, 320)) {
                        val style = TextStyle(fontFamily = family, fontSize = size)
                        val constraints = Constraints(maxWidth = width)
                        val baseline = measurer.measure(text, style, constraints = constraints)
                        for (mode in listOf(HighlightGranularity.WordOnly, HighlightGranularity.SentenceAndWord)) {
                            for (word in words) {
                                val highlighted = buildAnnotatedString {
                                    append(text)
                                    addStyle(readerWordStyle(mode, palette), word.first, word.last + 1)
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
