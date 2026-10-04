package dk.perspektiva.ttsroad

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import dk.perspektiva.ttsroad.data.FakeSessionStore
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import kotlinx.coroutines.flow.flowOf
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
}
