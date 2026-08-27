package dk.perspektiva.ttsroad.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
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
 * Android's minimum touch target (#104).
 *
 * Material's own buttons stop at 40 dp, which is a visual-density decision rather than an
 * accessibility one. These controls are used while walking, commuting and reaching one-handed, and
 * their neighbours do different things — so the gap between 40 and 48 is not cosmetic.
 */
val MinTouchTargetSize = 48.dp

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
                modifier = Modifier
                    .heightIn(min = MinTouchTargetSize)
                    .semantics { this.selected = isSelected },
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

/**
 * The AARIS section rule: an accent kicker, an uppercase title, and a hairline under both.
 *
 * This is the app's structural device — the thing that says which surface you are on once the top
 * bar has scrolled away, and the reason the home screen reads as designed rather than assembled.
 * It lived as a private composable inside `MainActivity` until #158, which is most of why the
 * screens in their own files never used it: they import [AarisCard], [MetaText] and [AarisTag]
 * already, and could not reach this one. **Every scrolling screen opens with one of these**, a
 * screen whose whole body is a single list included.
 *
 * ## Which kicker
 *
 * Two forms, and the choice is not taste — it follows from whether the section can disappear.
 *
 * - **A two-digit ordinal (`01`, `02`, `03`)** for a screen whose sections are a fixed sequence
 *   that is always present, like the home screen's three rails. The number is a position the
 *   reader can rely on, and it stays put between visits.
 * - **A short mnemonic (`CH`, `BM`, `Q`)** for a section that is conditional, or that stands alone
 *   on its screen.
 *
 * A conditional section must never be numbered. The fiction screen is the case that proves it:
 * its bookmarks block only appears when the fiction has bookmarks, so numbering the two sections
 * there would make **Chapters** `01` or `02` depending on something the reader did days ago in a
 * different screen. A kicker that moves is worse than no kicker — it looks like a count of
 * something, and it is counting nothing.
 *
 * Keep mnemonics unique within a screen, and keep them to four characters; past that they stop
 * reading as a code and start competing with the title beside them.
 *
 * ## Not to be confused with the `//` line
 *
 * `MetaText("// Something", color = AarisColor.Accent)` is the app's other accent label, and there
 * are 54 of them against this header's handful. That ratio is the drift #158 is about, but it is
 * not an instruction to convert all 54 — most are doing honest work at a smaller scale. The split:
 *
 * - **`§` (this)** heads a *screen-level* section — a band of the page, with a rule under it,
 *   that the reader scrolls between.
 * - **`//`** labels a group *inside* a card, a sheet or a dialog — "// Playback speed" above a
 *   speed picker. It is a caption, not a landmark, and it deliberately carries no rule.
 *
 * A `//` line that is the first thing in a scrolling screen is a section header wearing the wrong
 * clothes, and should become one of these.
 *
 * @param kicker the ordinal or mnemonic, rendered after a `§`. Uppercased for you.
 * @param title the section name. Uppercased for you.
 * @param actionLabel optional trailing action — "Refresh", "Browse all". Uppercased for you.
 */
@Composable
fun SectionHeader(
    kicker: String,
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MetaText(text = "§ ${kicker.uppercase()}", color = AarisColor.Accent)
            if (actionLabel != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    modifier = Modifier.heightIn(min = MinTouchTargetSize),
                ) {
                    Text(actionLabel.uppercase())
                }
            }
        }
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
    }
}

/**
 * # Control rank
 *
 * AARIS says *no radius, thin border, mono uppercase label*. Applied to one control that is
 * handsome; applied to every control on a screen it produces a stack of identical grey rectangles
 * with nothing for the eye to land on. That is the mechanism behind #159 — the fiction header
 * reached **ten** full-width buttons and the player **eight** equal text buttons, and the complaint
 * that arrived was "too many buttons", when the real fault was that none of them outranked another.
 *
 * Colour was doing the only differentiating, and colour here carries *severity*
 * ([AarisColor.Warning], [AarisColor.Danger]), not rank — so a rare destructive action and an
 * everyday one looked equally loud, just differently tinted.
 *
 * Three ranks. Pick by how often the control is reached for, not by how important it feels:
 *
 * 1. **Primary** — at most **one per screen**. Filled, accent, full width. The thing the screen
 *    exists for: RESUME on a fiction, play/pause on the player.
 * 2. **Secondary** — outlined, and **laid out in a row, not a column**. Two or three per screen.
 *    Wanting a second action is normal; giving each one its own full-width band is what makes a
 *    screen read as a control panel. `fillMaxWidth()` is the exception here, not the default.
 * 3. **Tertiary / housekeeping** — [AarisActionRow] inside a sheet or a collapsed block. Anything
 *    rare, destructive, or needing its consequence spelled out. Never in the primary scroll.
 *
 * The test for rank three is simple: if the control needs a sentence under it to be safe to press,
 * it is not a button, it is a row — and it belongs behind something.
 */
@Composable
fun AarisActionRow(
    title: String,
    /**
     * The consequence, in one line — and when [enabled] is false, **the reason why not**.
     *
     * This is the whole argument for the row over a button. "Regenerating makes everyone
     * re-subscribe" and "No audio for this chapter yet" cannot ride on a button's label, and
     * trailing them under one as loose [MetaText] leaves the reader to guess which control the
     * sentence belongs to.
     */
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Overrides the title colour. For a destructive row — pass [AarisColor.Danger]. */
    color: Color = Color.Unspecified,
) {
    Column(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTargetSize)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                // Disabled, not de-emphasised: Dim is readable body text, and reusing it here
                // would leave an unavailable row looking available.
                color = when {
                    !enabled -> AarisColor.Disabled
                    color != Color.Unspecified -> color
                    else -> AarisColor.Ink
                },
            )
            Spacer(modifier = Modifier.height(2.dp))
            MetaText(text = subtitle, color = AarisColor.Dim)
        }
        HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
    }
}
