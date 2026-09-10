package dk.perspektiva.ttsroad

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dk.perspektiva.ttsroad.data.FakeSessionStore
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp", fontScale = 1.5f)
class SecurityDialogsTest {
    @get:Rule val compose = createComposeRule()

    private val fakeRepo = TtsRoadRepository(FakeSessionStore(SessionState(token = "test", serverUrl = "http://localhost:8080")))

    @Test
    fun `security settings initial render at large font scale`() {
        compose.setContent {
            TtsRoadTheme {
                AccountSecuritySettings(repository = fakeRepo)
            }
        }

        compose.onNodeWithText("CHANGE PASSWORD").assertIsDisplayed()
    }
}
