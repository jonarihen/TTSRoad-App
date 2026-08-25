package dk.perspektiva.ttsroad.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every AARIS foreground has to stay readable on every AARIS surface.
 *
 * This exists because the palette drifted below the floor once already and nothing noticed: `Dim`
 * shipped at 2.19:1 on the hover surface, and `Danger` — error text — at 4.27:1. Both are the kind
 * of regression that is invisible to whoever picks the colour, on a good screen, with good eyes.
 * A ratio is arithmetic, so it can simply be asserted.
 *
 * WCAG 2.1 AA is 4.5:1 for text under 18pt. Everything here is well under: the `MetaText` these
 * tokens are mostly used through is 10–11sp, so the large-text exception never applies and there is
 * no reason to encode it.
 */
class TtsRoadThemeContrastTest {

    /** Every background a foreground can land on. Lighter surfaces are the hard cases. */
    private val surfaces = mapOf(
        "Bg" to AarisColor.Bg,
        "BgRaise" to AarisColor.BgRaise,
        "BgHover" to AarisColor.BgHover,
        "BgInput" to AarisColor.BgInput,
    )

    /**
     * Foregrounds carrying text a user is meant to read.
     *
     * `Disabled` is deliberately absent: a disabled control is exempt, and looking unavailable is
     * the whole message. `Line` and `LineSoft` are absent because they are borders, not text.
     */
    private val textForegrounds = mapOf(
        "Ink" to AarisColor.Ink,
        "Muted" to AarisColor.Muted,
        "Dim" to AarisColor.Dim,
        "Accent" to AarisColor.Accent,
        "AccentHover" to AarisColor.AccentHover,
        "Ok" to AarisColor.Ok,
        "Warning" to AarisColor.Warning,
        "Danger" to AarisColor.Danger,
    )

    @Test
    fun `every text colour clears AA on every surface`() {
        val failures = mutableListOf<String>()
        textForegrounds.forEach { (fgName, fg) ->
            surfaces.forEach { (bgName, bg) ->
                val ratio = contrastRatio(fg, bg)
                if (ratio < 4.5) failures += "$fgName on $bgName is ${"%.2f".format(ratio)}:1"
            }
        }
        assertTrue("below WCAG AA 4.5:1 — ${failures.joinToString("; ")}", failures.isEmpty())
    }

    @Test
    fun `the two tokens that had regressed are held above the floor`() {
        // Named individually so a future change to either fails with the reason attached rather
        // than as one line of a list.
        assertTrue(
            "Dim was 2.19:1 on the hover surface and made metadata decorative",
            contrastRatio(AarisColor.Dim, AarisColor.BgHover) >= 4.5,
        )
        assertTrue(
            "Danger is error text, and was 4.27:1 on the hover surface",
            contrastRatio(AarisColor.Danger, AarisColor.BgHover) >= 4.5,
        )
    }

    @Test
    fun `the three text ranks stay distinguishable from each other`() {
        // Readability must not be bought by collapsing the hierarchy into one grey. These are not
        // AA thresholds — they are "you can see which is which".
        assertTrue(
            "Ink and Muted have merged",
            contrastRatio(AarisColor.Ink, AarisColor.Muted) >= 1.5,
        )
        assertTrue(
            "Muted and Dim have merged",
            contrastRatio(AarisColor.Muted, AarisColor.Dim) > 1.0,
        )
        assertTrue(
            "Dim is no longer dimmer than Muted",
            relativeLuminance(AarisColor.Dim) < relativeLuminance(AarisColor.Muted),
        )
    }

    @Test
    fun `the disabled token is not one of the text colours`() {
        // Its whole job is to be below the floor, so it must never be reachable as body text by
        // being the same value as something that is.
        assertTrue(
            "Disabled has become a text colour",
            AarisColor.Disabled !in textForegrounds.values,
        )
        assertTrue(
            "Disabled should read as unavailable, not as text",
            contrastRatio(AarisColor.Disabled, AarisColor.BgHover) < 4.5,
        )
    }

    @Test
    fun `the ratio maths agrees with the known extremes`() {
        // A guard on the helper itself: white on black is 21:1 and a colour on itself is 1:1, so a
        // broken formula cannot quietly pass everything above.
        assertTrue(contrastRatio(Color.White, Color.Black) > 20.9)
        assertTrue(contrastRatio(AarisColor.Ink, AarisColor.Ink) < 1.01)
    }

    /** WCAG 2.1 relative luminance. */
    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }

    private fun contrastRatio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }
}
