package dk.perspektiva.ttsroad

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class PlaybackSkipsSettingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `setting has account copy and a full-row accessible target on a narrow phone`() {
        compose.setContent {
            TtsRoadTheme { PlaybackSkipsSetting(enabled = true, onEnabledChange = {}) }
        }
        compose.onNodeWithText("SKIP ADVERTS AND DISCLAIMERS").assertIsDisplayed()
        val target = compose.onNodeWithContentDescription("SKIP ADVERTS AND DISCLAIMERS")
        target.assertHasClickAction().assertIsOn()
        val bounds = target.getUnclippedBoundsInRoot()
        assertTrue(bounds.right - bounds.left >= 48.dp)
        assertTrue(bounds.bottom - bounds.top >= 48.dp)
        assertTrue(bounds.right <= 320.dp)
    }
}
