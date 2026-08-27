package dk.perspektiva.ttsroad.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The section rule — the app's one structural landmark (#158).
 *
 * Worth testing for a reason its size does not suggest. It moved out of `MainActivity` so every
 * screen could reach it, and the point of the move is that screens now *share* one of these rather
 * than each growing its own accent line. That makes its rendering a contract: `§ BM` and
 * `2 BOOKMARKS` are what half a dozen screens' layout tests match on, so a change to the casing or
 * to the `§` here would break all of them at once, far from this file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class SectionHeaderTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the kicker is prefixed and both halves are capitalised for you`() {
        // Call sites pass ordinary prose — "2 bookmarks", not "2 BOOKMARKS". The header owning the
        // casing is what stops nine screens each deciding it differently.
        compose.setContent {
            TtsRoadTheme { SectionHeader(kicker = "bm", title = "2 bookmarks") }
        }

        compose.onNodeWithText("§ BM").assertIsDisplayed()
        compose.onNodeWithText("2 BOOKMARKS").assertIsDisplayed()
    }

    @Test
    fun `a header with no action draws no button`() {
        compose.setContent {
            TtsRoadTheme { SectionHeader(kicker = "Q", title = "Nothing queued") }
        }

        // Both halves of the pair are required before anything clickable appears, so a screen that
        // passes a label and forgets the lambda gets a plain header rather than a dead button.
        val clickable = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertEquals(0, clickable.size)
    }

    @Test
    fun `the trailing action reports taps`() {
        var taps = 0
        compose.setContent {
            TtsRoadTheme {
                SectionHeader(
                    kicker = "01",
                    title = "Continue listening",
                    actionLabel = "Refresh",
                    onAction = { taps++ },
                )
            }
        }

        compose.onNodeWithText("REFRESH").assertIsDisplayed().performClick()

        assertEquals(1, taps)
    }

    @Test
    fun `the trailing action is a full touch target`() {
        // #104's rule applied to a control that predates it: Material's TextButton stops at 40 dp,
        // and this one sits at the top of a scrolling list, where a near-miss scrolls the page
        // instead of refreshing it. Matched on the clickable node rather than on the glyph — the
        // label inside a 48 dp button reports the label's bounds, not the button's.
        compose.setContent {
            TtsRoadTheme {
                SectionHeader(
                    kicker = "01",
                    title = "Continue listening",
                    actionLabel = "Refresh",
                    onAction = {},
                )
            }
        }

        compose.onNode(hasClickAction()).assertHeightIsAtLeast(MinTouchTargetSize)
    }

    @Test
    fun `a long title does not push the action off a narrow screen`() {
        // 320 dp, a title that cannot fit beside anything, and an action that still has to be
        // reachable. assertIsDisplayed is the assertion that cares: it distinguishes
        // composed-and-visible from composed-and-clipped, which is the failure mode here.
        compose.setContent {
            TtsRoadTheme {
                SectionHeader(
                    kicker = "ALL",
                    title = "A fiction title long enough to need the whole width and then some",
                    actionLabel = "Browse all",
                    onAction = {},
                )
            }
        }

        compose.onNodeWithText("BROWSE ALL").assertIsDisplayed()
    }
}
