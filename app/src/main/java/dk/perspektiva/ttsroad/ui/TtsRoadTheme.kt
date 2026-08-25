package dk.perspektiva.ttsroad.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * AARIS design language — dark, square, thin-bordered, orange-accent, mono-labelled
 * "operator console". Mirrors the canonical tokens from the web app's aaris.css so the
 * Android client reads as the same product.
 */
object AarisColor {
    val Bg = Color(0xFF0E1014)
    val BgRaise = Color(0xFF12151A)
    val BgHover = Color(0xFF1A1E25)
    val BgInput = Color(0xFF0B0D11)
    val Ink = Color(0xFFE9ECEF)
    val Muted = Color(0xFF8B939E)

    /**
     * Third-rank text — durations, status hints, the line under a setting explaining it.
     *
     * Third rank, but still text. The old `#4D545E` was 2.19:1 on the hover surface and 2.39:1 on a
     * card, which is not a de-emphasis but an erasure: at the 10–11sp `MetaText` uses, it made
     * remaining time, chapter metadata, bookmark notes, search snippets and every Settings
     * explanation effectively decorative for anyone with imperfect sight or an imperfect screen.
     * The hierarchy has to come from emphasis, not from unreadability.
     *
     * This is the lowest AARIS foreground clearing WCAG AA 4.5:1 against [Bg], [BgRaise], [BgHover]
     * and [BgInput] — the same value the desktop client moved to, so the two read alike.
     * `TtsRoadThemeContrastTest` holds it there.
     *
     * **Not for disabled controls.** [Disabled] is that, and is deliberately below AA.
     */
    val Dim = Color(0xFF808995)

    /**
     * A control that cannot be used right now.
     *
     * The old [Dim], kept for the one thing it was legitimately doing. Disabled controls are exempt
     * from the contrast floor precisely because looking unavailable is the message, so this stays
     * out of the contrast test — and out of anything a user is meant to read.
     */
    val Disabled = Color(0xFF4D545E)

    val Line = Color(0xFF232830)
    val LineSoft = Color(0xFF1A1E25)
    val Accent = Color(0xFFFF5A1F)
    val AccentHover = Color(0xFFFF7A44)
    val Ok = Color(0xFF3FD97F)
    val Warning = Color(0xFFFFB224)
    // 4.27:1 on the hover surface as #E5484D, and error text is the last thing that should be hard
    // to read. Raised to the desktop client's corrected value, which clears AA on every surface.
    val Danger = Color(0xFFEC555A)
}

/** IBM Plex Mono carries the design's labels; the system monospace stands in on-device. */
val MonoFamily: FontFamily = FontFamily.Monospace

private val AarisColorScheme = darkColorScheme(
    primary = AarisColor.Accent,
    onPrimary = AarisColor.Bg,
    secondary = AarisColor.Accent,
    onSecondary = AarisColor.Bg,
    tertiary = AarisColor.Warning,
    onTertiary = AarisColor.Bg,
    background = AarisColor.Bg,
    onBackground = AarisColor.Ink,
    surface = AarisColor.BgRaise,
    onSurface = AarisColor.Ink,
    surfaceVariant = AarisColor.BgHover,
    onSurfaceVariant = AarisColor.Muted,
    surfaceContainer = AarisColor.BgRaise,
    surfaceContainerHigh = AarisColor.BgHover,
    outline = AarisColor.Line,
    outlineVariant = AarisColor.LineSoft,
    error = AarisColor.Danger,
    onError = AarisColor.Bg,
)

// Machined surfaces: no radius anywhere.
private val SquareCorner = RoundedCornerShape(0.dp)
private val AarisShapes = Shapes(
    extraSmall = SquareCorner,
    small = SquareCorner,
    medium = SquareCorner,
    large = SquareCorner,
    extraLarge = SquareCorner,
)

private val AarisTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Black,
        fontSize = 32.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Black,
        fontSize = 22.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Black,
        fontSize = 18.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    // Terminal-action label: every Button/TextButton inherits the mono, spaced-cap look.
    labelLarge = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.4.sp,
    ),
)

@Composable
fun TtsRoadTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AarisColorScheme,
        shapes = AarisShapes,
        typography = AarisTypography,
        content = content,
    )
}

/**
 * Mono uppercase "meta" label (section kickers, field captions, status text).
 * Equivalent to the web app's `.meta` / `.page-kicker`.
 */
@Composable
fun MetaText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = AarisColor.Muted,
    /** Caps free-form text — a server error message is not written to fit a list row. */
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            fontFamily = MonoFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            letterSpacing = 1.3.sp,
        ),
    )
}

/** Mono uppercase bordered chip — the AARIS `.tag`. Used for fiction tags/genres. */
@Composable
fun AarisTag(
    text: String,
    modifier: Modifier = Modifier,
    /** Border and text colour. Defaults to the neutral tag; a failure passes [AarisColor.Danger]. */
    color: Color = AarisColor.Muted,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier
            .border(1.dp, if (color == AarisColor.Muted) AarisColor.Line else color)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        color = color,
        style = TextStyle(
            fontFamily = MonoFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 10.sp,
            letterSpacing = 1.2.sp,
        ),
    )
}

/**
 * Hairline progress bar drawn AARIS-style (square, accent-on-line) — used on cover art and the
 * mini player, where Material's LinearProgressIndicator (rounded caps, stop gap) looks wrong.
 */
@Composable
fun ThinProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 3.dp,
) {
    Box(modifier.height(height).background(AarisColor.Line)) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(AarisColor.Accent),
        )
    }
}

/** Flat, thin-bordered panel — the AARIS `.panel`. Replaces elevated Material cards. */
@Composable
fun AarisCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = CardDefaults.outlinedCardColors(
        containerColor = AarisColor.BgRaise,
        contentColor = AarisColor.Ink,
    )
    val border = BorderStroke(1.dp, AarisColor.Line)
    if (onClick != null) {
        OutlinedCard(onClick = onClick, modifier = modifier, colors = colors, border = border) {
            content()
        }
    } else {
        OutlinedCard(modifier = modifier, colors = colors, border = border) {
            content()
        }
    }
}

/**
 * A one-of-N choice that wraps instead of running off the side of the card.
 *
 * Five Settings selectors each laid their options out in a plain [androidx.compose.foundation.layout.Row]
 * with no wrapping and no scroll (#99). A `Row` does not wrap — it measures its children, runs out
 * of width, and the last ones are simply placed outside the parent. At 320 dp the page gutter and
 * the card padding leave about 240 dp, and six sleep-timer buttons need well over 380 dp, so the
 * last two choices could not be seen or tapped at all. A preference that exists in the data model
 * and cannot be selected from the UI is the failure worth naming: it is not cosmetic clipping.
 *
 * [FlowRow] fixes it by construction rather than by tuning. There is no width at which this
 * overflows, so it holds for a narrow phone, split screen, a large display scale, and a
 * longer label in some future locale — none of which a hand-picked breakpoint would survive.
 *
 * The [selected] semantics are here because the visual cue is colour alone. TalkBack reads the
 * label and nothing else, so without this a screen-reader user can hear all six options and not
 * learn which one is in force.
 */
@Composable
fun <T> AarisChoiceRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            OutlinedButton(
                onClick = { onSelect(option) },
                modifier = Modifier.semantics { this.selected = isSelected },
                shape = RectangleShape,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (isSelected) AarisColor.Accent else AarisColor.Muted,
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(label(option))
            }
        }
    }
}
