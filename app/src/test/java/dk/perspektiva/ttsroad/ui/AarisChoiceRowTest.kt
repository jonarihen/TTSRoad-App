package dk.perspektiva.ttsroad.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #99: five Settings selectors laid their options out in a plain `Row`, which does not wrap.
 *
 * The width these run at is not arbitrary. 320 dp is the narrowest phone Android still ships, the
 * page gutter is 24 dp each side and the Settings card pads 16 dp each side, so a selector gets
 * about **240 dp** — which is what [ChoiceRowWidth] is. The longest selector is the six
 * sleep-timer defaults, and that is the one the issue's acceptance criteria name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class AarisChoiceRowTest {
    @get:Rule val compose = createComposeRule()

    private val sleepTimerLabels = listOf("OFF", "5 MIN", "15 MIN", "30 MIN", "45 MIN", "60 MIN")

    @Test
    fun `every sleep-timer choice is reachable at 320 dp`() {
        compose.setContent {
            ConstrainedToACard {
                AarisChoiceRow(
                    options = sleepTimerLabels,
                    selected = "30 MIN",
                    label = { it },
                    onSelect = {},
                )
            }
        }

        for (label in sleepTimerLabels) {
            compose.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun `no choice is placed outside the card it lives in`() {
        compose.setContent {
            ConstrainedToACard {
                AarisChoiceRow(
                    options = sleepTimerLabels,
                    selected = "30 MIN",
                    label = { it },
                    onSelect = {},
                )
            }
        }

        for (label in sleepTimerLabels) {
            val right = compose.onNodeWithText(label).getUnclippedBoundsInRoot().right
            assertTrue(
                "$label is placed at ${right.value} dp, outside the ${ChoiceRowWidth.value} dp card",
                right <= ChoiceRowWidth + Tolerance,
            )
        }
    }

    /**
     * The guard on the guard.
     *
     * A wrapping test only means something if the non-wrapping layout it replaced would have failed
     * it. This renders the exact shape the five selectors had before — a `Row` of `OutlinedButton`s
     * with 8 dp spacing — and asserts it really does put a choice outside the card. Without this, a
     * future change that made the buttons narrow enough to fit by luck would leave the tests above
     * passing while saying nothing.
     */
    @Test
    fun `the plain Row this replaced does overflow, so the assertion has teeth`() {
        compose.setContent {
            ConstrainedToACard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sleepTimerLabels.forEach { label ->
                        OutlinedButton(onClick = {}) { Text(label) }
                    }
                }
            }
        }

        val furthestRight = sleepTimerLabels.maxOf {
            compose.onNodeWithText(it).getUnclippedBoundsInRoot().right
        }
        assertTrue(
            "A non-wrapping Row of six choices fitted in ${ChoiceRowWidth.value} dp, so this " +
                "test can no longer tell a wrapping layout from a clipping one",
            furthestRight > ChoiceRowWidth,
        )
    }

    @Test
    fun `only the selected choice reports itself selected`() {
        compose.setContent {
            ConstrainedToACard {
                AarisChoiceRow(
                    options = sleepTimerLabels,
                    selected = "15 MIN",
                    label = { it },
                    onSelect = {},
                )
            }
        }

        compose.onNodeWithText("15 MIN").assertIsSelected()
        for (label in sleepTimerLabels - "15 MIN") {
            compose.onNodeWithText(label).assertIsNotSelected()
        }
    }

    @Test
    fun `a choice on a wrapped line is tappable, not merely drawn`() {
        var chosen: String? = null
        compose.setContent {
            ConstrainedToACard {
                AarisChoiceRow(
                    options = sleepTimerLabels,
                    selected = "OFF",
                    label = { it },
                    onSelect = { chosen = it },
                )
            }
        }

        // The last option: the one a non-wrapping Row placed off the side of the card, where it
        // could be neither seen nor hit. Reaching it is the whole point of the fix.
        compose.onNodeWithText("60 MIN").performClick()

        assertEquals("60 MIN", chosen)
    }

    @Test
    fun `selection follows the state the caller holds`() {
        compose.setContent {
            var selected by remember { mutableStateOf("OFF") }
            ConstrainedToACard {
                AarisChoiceRow(
                    options = sleepTimerLabels,
                    selected = selected,
                    label = { it },
                    onSelect = { selected = it },
                )
            }
        }

        compose.onNodeWithText("45 MIN").performClick()

        compose.onNodeWithText("45 MIN").assertIsSelected()
        compose.onNodeWithText("OFF").assertIsNotSelected()
    }

    /** The width a Settings selector actually gets on the narrowest phone Android ships. */
    @Composable
    private fun ConstrainedToACard(content: @Composable () -> Unit) {
        Box(modifier = Modifier.padding(24.dp).width(ChoiceRowWidth)) { content() }
    }

    private companion object {
        val ChoiceRowWidth: Dp = 240.dp

        /** Compose measures in pixels; a fraction of a dp either way is rounding, not overflow. */
        val Tolerance: Dp = 0.5.dp
    }
}
