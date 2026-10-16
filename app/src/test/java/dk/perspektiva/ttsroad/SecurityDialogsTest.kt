package dk.perspektiva.ttsroad

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dk.perspektiva.ttsroad.data.FakeSessionStore
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp", fontScale = 1.5f)
class SecurityDialogsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val session = SessionState(token = "test", serverUrl = "http://localhost:8080")
    private val store = FakeSessionStore(session)
    private val fakeRepo = TtsRoadRepository(store)

    @Test
    fun `security settings initial render at large font scale`() {
        val owner = compose.activity.recoveryCodesOwner(fakeRepo, flowOf(session), store::current)
        compose.setContent {
            TtsRoadTheme {
                CompositionLocalProvider(LocalRecoveryCodesOwner provides owner) {
                    AccountSecuritySettings(repository = fakeRepo)
                }
            }
        }

        compose.onNodeWithText("CHANGE PASSWORD").assertIsDisplayed()
    }

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))

    @Test
    fun `turning off two-factor needs both the password and a code`() {
        val sent = mutableListOf<Pair<String, String>>()
        compose.setContent {
            TtsRoadTheme {
                DisableTwoFactorDialog(
                    busy = false,
                    error = null,
                    onDismiss = {},
                    onDisable = { password, code -> sent += password to code },
                )
            }
        }

        compose.onNodeWithText("TURN OFF").assertIsNotEnabled()
        field("Current password").performTextInput("still secret")
        compose.onNodeWithText("TURN OFF").assertIsNotEnabled()
        field("Authenticator code or recovery code").performTextInput(" 123456 ")
        compose.onNodeWithText("TURN OFF").assertIsEnabled().performClick()

        assertEquals(listOf("still secret" to "123456"), sent)
    }

    @Test
    fun `a refused code is shown in the dialog`() {
        compose.setContent {
            TtsRoadTheme {
                DisableTwoFactorDialog(
                    busy = false,
                    error = "Invalid authentication code.",
                    onDismiss = {},
                    onDisable = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText("INVALID AUTHENTICATION CODE.").assertIsDisplayed()
    }

    @Test
    fun `the dialog cannot be submitted twice while a request is in flight`() {
        compose.setContent {
            TtsRoadTheme {
                DisableTwoFactorDialog(
                    busy = true,
                    error = null,
                    onDismiss = {},
                    onDisable = { _, _ -> },
                )
            }
        }

        field("Current password").performTextInput("still secret")
        field("Authenticator code or recovery code").performTextInput("123456")
        compose.onNodeWithText("TURN OFF").assertIsNotEnabled()
    }
}
