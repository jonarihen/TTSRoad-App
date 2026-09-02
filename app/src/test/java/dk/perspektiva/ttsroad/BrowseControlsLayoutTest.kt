package dk.perspektiva.ttsroad

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.data.BrowseScope
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The controls that narrow the browse grid, on the narrow phone they have to survive.
 *
 * What these cover is the half of the behaviour that the pure functions in `FictionBrowseTest`
 * cannot reach: whether the state a filter is in is *legible*, and whether it is announced to
 * anything but the eye. A filter that is hiding rows while looking exactly like one that is not is
 * the failure mode worth a test — the grid comes back short after a restart and reads as the
 * server having lost the shelf.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class BrowseControlsLayoutTest {
    @get:Rule val compose = createComposeRule()

    private val MinimumTarget = 48.dp

    @Test
    fun `the scope tabs carry their counts and report which is chosen`() {
        compose.setContent {
            TtsRoadTheme {
                BrowseScopeTabs(
                    selected = BrowseScope.All,
                    counts = mapOf(BrowseScope.Following to 12, BrowseScope.All to 240),
                    onSelect = {},
                )
            }
        }

        // MetaText renders AARIS mono caps, which is what the semantics tree carries.
        compose.onNodeWithText("FOLLOWING  12").assertIsDisplayed()
        compose.onNodeWithText("ALL  240").assertIsDisplayed()
        // The accent rule is colour alone, so selection has to reach TalkBack some other way.
        compose.onNodeWithText("ALL  240").assertIsSelected()
        compose.onNodeWithText("FOLLOWING  12").assertIsNotSelected()
    }

    @Test
    fun `a scope tab is a 48 dp target`() {
        compose.setContent {
            TtsRoadTheme {
                BrowseScopeTabs(
                    selected = BrowseScope.All,
                    counts = mapOf(BrowseScope.Following to 1, BrowseScope.All to 2),
                    onSelect = {},
                )
            }
        }

        compose.onNodeWithText("FOLLOWING  1").assertHeightIsAtLeast(MinimumTarget)
    }

    @Test
    fun `switching tabs reports the tab that was pressed, not the one that was on`() {
        var chosen: BrowseScope? = null
        compose.setContent {
            TtsRoadTheme {
                BrowseScopeTabs(
                    selected = BrowseScope.All,
                    counts = mapOf(BrowseScope.Following to 12, BrowseScope.All to 240),
                    onSelect = { chosen = it },
                )
            }
        }

        compose.onNodeWithText("FOLLOWING  12").performClick()

        assertEquals(BrowseScope.Following, chosen)
    }

    @Test
    fun `the tag bar says nothing is filtered when nothing is`() {
        compose.setContent {
            TtsRoadTheme {
                TagFilterBar(active = emptySet(), onOpen = {}, onClear = {})
            }
        }

        compose.onNodeWithText("TAGS").assertIsDisplayed()
        // Nothing to undo, so nothing offers to undo it.
        compose.onNodeWithText("CLEAR").assertDoesNotExist()
    }

    @Test
    fun `an active tag filter says so, names itself, and offers a way out`() {
        // The whole reason this is a bar rather than a row in the sort sheet. A filter that hides
        // rows without saying it is on sends the next reader looking for a bug in the server.
        var cleared = 0
        compose.setContent {
            TtsRoadTheme {
                TagFilterBar(
                    active = setOf("litrpg", "progression"),
                    onOpen = {},
                    onClear = { cleared++ },
                )
            }
        }

        compose.onNodeWithText("TAGS 2").assertIsDisplayed()
        compose.onNodeWithText("LITRPG").assertIsDisplayed()
        compose.onNodeWithText("PROGRESSION").assertIsDisplayed()
        compose.onNodeWithText("CLEAR").performClick()

        assertEquals(1, cleared)
    }

    @Test
    fun `a tag row is a checkbox that says which way it is set`() {
        // Ticking is ANDing, and a checkbox is the one control everybody reads that way. The state
        // has to be in the semantics tree, not only in the accent colour.
        compose.setContent {
            TtsRoadTheme {
                TagFilterSheet(
                    tags = listOf("litrpg", "romance"),
                    selected = setOf("litrpg"),
                    onToggle = {},
                    onClear = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("LITRPG").assertIsOn()
        compose.onNodeWithText("ROMANCE").assertIsOff()
    }

    @Test
    fun `a tag row is a 48 dp target and toggles the tag it names`() {
        var toggled: String? = null
        compose.setContent {
            TtsRoadTheme {
                TagFilterSheet(
                    tags = listOf("litrpg", "romance"),
                    selected = emptySet(),
                    onToggle = { toggled = it },
                    onClear = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("ROMANCE").assertHeightIsAtLeast(MinimumTarget)
        compose.onNodeWithText("ROMANCE").performClick()

        assertEquals("romance", toggled)
    }
}
