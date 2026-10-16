package dk.perspektiva.ttsroad

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.data.ServerAddressAction
import dk.perspektiva.ttsroad.data.ServerAddressResult
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings → Profile → Other addresses (#372), at the narrowest phone width. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class ServerAddressSettingsTest {
    @get:Rule val compose = createComposeRule()

    private val checks = mutableListOf<Pair<String, ServerAddressAction>>()
    private val forgotten = mutableListOf<String>()

    private fun show(
        saved: List<String> = listOf("https://ts.example/"),
        answer: ServerAddressResult = ServerAddressResult.Done,
    ) {
        compose.setContent {
            TtsRoadTheme {
                androidx.compose.foundation.layout.Column {
                    ServerAddressCard(
                        inUse = "https://lan.example/",
                        saved = saved,
                        check = { address, action ->
                            checks += address to action
                            answer
                        },
                        forget = { forgotten += it },
                    )
                }
            }
        }
    }

    @Test
    fun `a saved address can be switched to and forgotten`() {
        show()

        compose.onNodeWithText("https://lan.example/").assertIsDisplayed()
        compose.onNodeWithContentDescription("Switch to https://ts.example/", substring = true).performClick()
        compose.waitForIdle()
        assertEquals(listOf("https://ts.example/" to ServerAddressAction.Use), checks)
        compose.onNodeWithText(serverAddressDoneMessage(ServerAddressAction.Use), ignoreCase = true).assertExists()

        compose.onNodeWithContentDescription("Forget https://ts.example/", substring = true).performClick()
        compose.waitForIdle()
        assertEquals(listOf("https://ts.example/"), forgotten)
    }

    @Test
    fun `a typed address is checked before it is saved, and a refusal is shown as given`() {
        show(saved = emptyList(), answer = ServerAddressResult.Refused("That address is a different server."))

        compose.onNodeWithText("CHECK AND SAVE").assertIsNotEnabled()
        compose.onNodeWithText("ANOTHER ADDRESS").performTextInput("https://ts.example")
        compose.onNodeWithText("CHECK AND SAVE").performClick()
        compose.waitForIdle()

        assertEquals(listOf("https://ts.example" to ServerAddressAction.Remember), checks)
        compose.onNodeWithText("That address is a different server.", ignoreCase = true).assertExists()
    }

    @Test
    fun `the switch control is a full touch target and fits a 320 dp phone`() {
        show()

        val bounds = compose.onNodeWithContentDescription("Switch to https://ts.example/", substring = true)
            .getUnclippedBoundsInRoot()
        assertTrue(bounds.bottom - bounds.top >= 48.dp)
        assertTrue(bounds.right <= 320.dp)
    }
}
