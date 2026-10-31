package dk.perspektiva.ttsroad

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #508: an empty Home card points at Browse instead of dead-ending. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class EmptyCardTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `the action opens browse and is a 48 dp target`() {
        var opened = 0
        compose.setContent {
            TtsRoadTheme { EmptyCard("No fictions found", FindSomethingLabel) { opened++ } }
        }

        compose.onNodeWithText(FindSomethingLabel).assertHeightIsAtLeast(48.dp).performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `without an action it is only the message`() {
        compose.setContent { TtsRoadTheme { EmptyCard("Nothing here") } }

        compose.onNodeWithText(FindSomethingLabel).assertDoesNotExist()
    }
}
