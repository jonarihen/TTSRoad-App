package dk.perspektiva.ttsroad.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class ErrorDetailsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the raw text stays folded until details is tapped`() {
        val raw = "UnknownHostException: Unable to resolve host \"https\""
        compose.setContent { TtsRoadTheme { ErrorDetails(details = raw) } }

        compose.onNodeWithText(raw).assertDoesNotExist()
        compose.onNodeWithTag(ErrorDetailsToggleTag).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText(raw).assertExists()
    }

    @Test
    fun `no details renders nothing`() {
        compose.setContent { TtsRoadTheme { ErrorDetails(details = null) } }

        compose.onNodeWithTag(ErrorDetailsToggleTag).assertDoesNotExist()
    }
}
