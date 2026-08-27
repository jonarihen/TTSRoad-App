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
 * Rank three: the row for an action that needs its consequence spelled out (#159).
 *
 * It was private to `MainActivity` as `BulkAction` and used by two sheets, which is a component
 * that has already proved itself twice and cannot be reached a third time. The reason it is worth
 * a test now is the subtitle contract: this row is what carries "regenerating makes everyone
 * re-subscribe" and "no audio for this chapter yet", and those sentences are the entire argument
 * for using a row instead of a button. A row that silently dropped its subtitle, or that stayed
 * pressable while disabled, would look fine and be wrong.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class AarisActionRowTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the consequence is shown, not just the title`() {
        compose.setContent {
            TtsRoadTheme {
                AarisActionRow(
                    title = "Regenerate feed link",
                    subtitle = "Everyone subscribed has to re-subscribe",
                    enabled = true,
                    onClick = {},
                )
            }
        }

        compose.onNodeWithText("Regenerate feed link").assertIsDisplayed()
        // MetaText uppercases, so the subtitle is matched as it actually renders.
        compose.onNodeWithText("EVERYONE SUBSCRIBED HAS TO RE-SUBSCRIBE").assertIsDisplayed()
    }

    @Test
    fun `a disabled row does not fire`() {
        var taps = 0
        compose.setContent {
            TtsRoadTheme {
                AarisActionRow(
                    title = "Play next",
                    subtitle = "No audio for this chapter yet",
                    enabled = false,
                    onClick = { taps++ },
                )
            }
        }

        compose.onNodeWithText("Play next").performClick()

        assertEquals(0, taps)
    }

    @Test
    fun `an enabled row fires once`() {
        var taps = 0
        compose.setContent {
            TtsRoadTheme {
                AarisActionRow(
                    title = "Play next",
                    subtitle = "After the chapter playing now",
                    enabled = true,
                    onClick = { taps++ },
                )
            }
        }

        compose.onNodeWithText("Play next").performClick()

        assertEquals(1, taps)
    }

    @Test
    fun `the row is a full touch target`() {
        // These sit shoulder to shoulder in a sheet and do different things — one of them deletes a
        // chapter for everybody. The gap between Material's 40 dp and Android's 48 is not cosmetic
        // when the neighbour is destructive (#104).
        compose.setContent {
            TtsRoadTheme {
                AarisActionRow(
                    title = "Delete this chapter",
                    subtitle = "Deletes it and its audio, for everyone",
                    enabled = true,
                    onClick = {},
                )
            }
        }

        compose.onNode(hasClickAction()).assertHeightIsAtLeast(MinTouchTargetSize)
    }

    @Test
    fun `a long consequence still renders on a narrow screen`() {
        compose.setContent {
            TtsRoadTheme {
                AarisActionRow(
                    title = "Exclude this chapter",
                    subtitle = "Takes it off every feed and player, for every account",
                    enabled = true,
                    onClick = {},
                )
            }
        }

        compose.onNodeWithText("Exclude this chapter").assertIsDisplayed()
    }
}
