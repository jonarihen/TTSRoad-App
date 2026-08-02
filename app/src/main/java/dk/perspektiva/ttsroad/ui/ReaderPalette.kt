package dk.perspektiva.ttsroad.ui

import androidx.compose.ui.graphics.Color
import dk.perspektiva.ttsroad.data.ReaderTheme

/**
 * Colours for the reader surface.
 *
 * The reader is the one screen that gets to leave the AARIS dark, because it is the one screen a
 * user stares at for an hour. Everything else about it stays in the design language — square
 * corners, mono labels, orange accent — so only the page itself changes.
 */
data class ReaderPalette(
    val background: Color,
    val ink: Color,
    val muted: Color,
    /** The sentence band. Sits behind the text, so it has to stay legible under body copy. */
    val band: Color,
    /** The word accent inside the band. */
    val accent: Color,
    val line: Color,
)

fun readerPalette(theme: ReaderTheme): ReaderPalette = when (theme) {
    ReaderTheme.Console -> ReaderPalette(
        background = AarisColor.Bg,
        ink = AarisColor.Ink,
        muted = AarisColor.Muted,
        // A dark accent wash rather than a bright fill: at this size a saturated band vibrates
        // against the text and is harder to read than no band at all.
        band = Color(0xFF3A2412),
        accent = AarisColor.Accent,
        line = AarisColor.Line,
    )

    ReaderTheme.Paper -> ReaderPalette(
        background = Color(0xFFF4EFE6),
        ink = Color(0xFF17191C),
        muted = Color(0xFF5F6670),
        band = Color(0xFFFBDCC5),
        accent = Color(0xFFC43D06),
        line = Color(0xFFD9D1C4),
    )

    ReaderTheme.Night -> ReaderPalette(
        background = Color(0xFF06070A),
        // Dimmed rather than pure white: this theme exists for reading next to someone asleep.
        ink = Color(0xFF9AA1AB),
        muted = Color(0xFF565D67),
        band = Color(0xFF1B1712),
        accent = Color(0xFFB0561F),
        line = Color(0xFF14171C),
    )
}
