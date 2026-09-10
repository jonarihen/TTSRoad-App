package dk.perspektiva.ttsroad

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.ui.MetaText
import dk.perspektiva.ttsroad.ui.MinTouchTargetSize
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class SettingsSwitchSemanticsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `auto mark played setting row has full row switch semantics`() {
        var value = false
        compose.setContent {
            TtsRoadTheme {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouchTargetSize)
                        .toggleable(
                            value = value,
                            role = Role.Switch,
                            onValueChange = { value = it },
                        )
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        MetaText(text = "Mark chapters played automatically")
                    }
                    Switch(checked = value, onCheckedChange = null)
                }
            }
        }

        val fullRow = compose.onNodeWithText("MARK CHAPTERS PLAYED AUTOMATICALLY", substring = true)
        fullRow.assertIsDisplayed()
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)

        fullRow.performClick()
        compose.waitForIdle()
        assertEquals(true, value)
    }

    @Test
    fun `skip silence setting row has full row switch semantics`() {
        var value = true
        compose.setContent {
            TtsRoadTheme {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouchTargetSize)
                        .toggleable(
                            value = value,
                            role = Role.Switch,
                            onValueChange = { value = it },
                        )
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        MetaText(text = "Skip silence")
                    }
                    Switch(checked = value, onCheckedChange = null)
                }
            }
        }

        val fullRow = compose.onNodeWithText("SKIP SILENCE", substring = true)
        fullRow.assertIsDisplayed()
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)

        fullRow.performClick()
        compose.waitForIdle()
        assertEquals(false, value)
    }
}
