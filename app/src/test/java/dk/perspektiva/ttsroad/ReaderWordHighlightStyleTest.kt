package dk.perspektiva.ttsroad

import androidx.compose.ui.graphics.Color
import dk.perspektiva.ttsroad.data.HighlightGranularity
import dk.perspektiva.ttsroad.ui.ReaderPalette
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderWordHighlightStyleTest {
    private val palette = ReaderPalette(
        background = Color.Black,
        ink = Color.White,
        muted = Color.Gray,
        band = Color.Yellow,
        accent = Color.Red,
        line = Color.DarkGray,
    )

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
