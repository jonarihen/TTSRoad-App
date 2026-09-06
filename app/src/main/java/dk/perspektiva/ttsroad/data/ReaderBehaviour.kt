package dk.perspektiva.ttsroad.data

import kotlin.math.roundToInt

/**
 * Reader behaviour that is pure enough to test, kept out of the composable so it can be.
 */

/**
 * Whether the reader should hold the screen awake.
 *
 * The sleep timer's fade wins over the preference. Reading is why the screen is held bright in the
 * first place, but the fade means the listener is on their way out, and a reader left open would
 * otherwise keep the screen lit long after the audio has stopped.
 */
fun shouldKeepReaderScreenOn(preferenceEnabled: Boolean, sleepTimerFading: Boolean): Boolean =
    preferenceEnabled && !sleepTimerFading

/**
 * Where auto-scroll puts the active paragraph, as a lazy-list scroll offset in pixels.
 *
 * Negative, because the offset leaves space above the item. The line being spoken sits a third of
 * the way down: reading runs ahead of the audio, so the rest of the sentence has to be visible
 * below it, and pinning the active line to the very top leaves nothing to read into.
 */
fun readerAutoScrollOffsetPx(viewportHeightPx: Int): Int =
    if (viewportHeightPx <= 0) 0 else -(viewportHeightPx / 3)

/**
 * Where the line being spoken comes to rest after a correction, as a fraction of the viewport.
 *
 * The same upper third [readerAutoScrollOffsetPx] uses, for the same reason — this is the
 * line-precise version of that anchor.
 */
private const val ReaderFollowAnchorFraction = 1f / 3f

/**
 * The band the spoken line is allowed to drift through before the reader corrects.
 *
 * Correcting on every line would make the page twitch under a sentence someone is mid-way through
 * reading; never correcting until the paragraph changes is the bug this exists to fix — inside a
 * paragraph taller than the screen the highlight simply walks off the bottom and stays gone until
 * the next paragraph yanks it back. So the line is left alone while it is comfortably readable and
 * pulled back to the anchor the moment it is not, which is a scroll every several lines.
 *
 * The bottom guard is deliberately well above the fold: the reader's eye is *ahead* of the audio,
 * so a line at 55% still has the rest of its sentence on screen to read into. Letting it reach the
 * actual bottom edge would be correct-but-useless — by the time it is visible it is behind.
 */
private const val ReaderFollowTopGuardFraction = 0.12f
private const val ReaderFollowBottomGuardFraction = 0.55f

/**
 * How far to scroll to keep the line being spoken readable, or null to leave the page alone.
 *
 * All three measurements are pixels in the reader viewport's own coordinates, with 0 at the top
 * edge. A positive result scrolls forward (the text moves up), which is what
 * `LazyListState.animateScrollBy` takes.
 *
 * Following the *line* rather than the paragraph is the whole point. A paragraph-level anchor is
 * correct only for paragraphs shorter than the screen, and web fiction is full of ones that are
 * not; there the highlight leaves the viewport entirely and comes back with a jump at the next
 * paragraph break. Anchoring the line means the correction is the same size whatever the paragraph
 * looks like.
 */
fun readerFollowScrollDelta(
    lineTopPx: Int,
    lineBottomPx: Int,
    viewportHeightPx: Int,
): Int? {
    if (viewportHeightPx <= 0) return null
    val topGuard = viewportHeightPx * ReaderFollowTopGuardFraction
    val bottomGuard = viewportHeightPx * ReaderFollowBottomGuardFraction
    // The top is what the guards are about. The bottom only has to be on screen at all, which
    // matters at the largest font scales, where one line is a sizeable fraction of the viewport.
    val settled = lineTopPx >= topGuard &&
        lineTopPx <= bottomGuard &&
        lineBottomPx <= viewportHeightPx
    if (settled) return null
    val anchor = viewportHeightPx * ReaderFollowAnchorFraction
    return (lineTopPx - anchor).roundToInt().takeIf { it != 0 }
}
