package dk.perspektiva.ttsroad

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dk.perspektiva.ttsroad.ui.ErrorDetailsToggleTag
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class PlaybackErrorBannerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `playback details fold opens closes and resets for a different failure without hiding retry`() {
        val details = mutableStateOf("ERROR_CODE_IO_BAD_HTTP_STATUS\nHTTP 404")
        var retries = 0
        compose.setContent {
            TtsRoadTheme {
                PlaybackErrorBanner("Could not play this chapter", details.value) { retries++ }
            }
        }
        compose.onNodeWithText(details.value).assertDoesNotExist()
        compose.onNodeWithTag(ErrorDetailsToggleTag).performClick()
        compose.onNodeWithText(details.value).assertExists()
        compose.onNodeWithText("RETRY").performClick()
        assertEquals(1, retries)
        compose.onNodeWithTag(ErrorDetailsToggleTag).performClick()
        compose.onNodeWithText(details.value).assertDoesNotExist()
        compose.onNodeWithTag(ErrorDetailsToggleTag).performClick()
        compose.runOnIdle { details.value = "ERROR_CODE_DECODER_INIT_FAILED" }
        compose.onNodeWithText(details.value).assertDoesNotExist()
        compose.onNodeWithTag(ErrorDetailsToggleTag).performClick()
        compose.onNodeWithText(details.value).assertExists()
    }
}
