package dk.perspektiva.ttsroad

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dk.perspektiva.ttsroad.ui.AarisColor
import dk.perspektiva.ttsroad.ui.AarisPalette
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * #357: a disabled transport control looked the same as an enabled one, and every outline was a
 * separator-grade line at about 1.2:1.
 */
class TransportControlContrastTest {

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble() + 0.05
        val lb = b.luminance().toDouble() + 0.05
        return max(la, lb) / min(la, lb)
    }

    private fun eachPalette(check: (String) -> Unit) {
        val original = AarisColor.palette
        try {
            AarisPalette.all.forEachIndexed { index, palette ->
                AarisColor.palette = palette
                check(if (palette == AarisPalette.Night) "Night" else "palette $index")
            }
        } finally {
            AarisColor.palette = original
        }
    }

    @Test
    fun `an enabled control's outline clears 3 to 1 on the surfaces it is drawn on`() = eachPalette { name ->
        for (surface in listOf(AarisColor.Bg, AarisColor.BgRaise, AarisColor.BgHover)) {
            val ratio = contrast(transportOutline(enabled = true), surface)
            assertTrue("$name: outline is ${"%.2f".format(ratio)}:1", ratio >= 3.0)
        }
    }

    @Test
    fun `a disabled glyph is clearly dimmer than an enabled one`() = eachPalette { name ->
        val enabled = transportTint(enabled = true, filled = false)
        val disabled = transportTint(enabled = false, filled = false)
        // Against the control's own fill, the enabled glyph must read and the disabled one must
        // sit well below it — not a few steps of grey apart, as Muted and Dim were.
        val onFill = AarisColor.BgRaise
        assertTrue("$name: enabled glyph must clear 4.5:1", contrast(enabled, onFill) >= 4.5)
        assertTrue(
            "$name: disabled glyph must be at most half the enabled contrast",
            contrast(disabled, onFill) <= contrast(enabled, onFill) / 2,
        )
        assertTrue(
            "$name: disabled outline must be fainter than enabled",
            contrast(transportOutline(false), onFill) < contrast(transportOutline(true), onFill),
        )
    }
}
