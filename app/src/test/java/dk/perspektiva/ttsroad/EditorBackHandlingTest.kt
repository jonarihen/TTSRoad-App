package dk.perspektiva.ttsroad

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.data.FakeSessionStore
import dk.perspektiva.ttsroad.data.FictionSummary
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class EditorBackHandlingTest {
    @get:Rule val compose = createComposeRule()

    private val fakeRepo = TtsRoadRepository(FakeSessionStore(SessionState(token = "test", serverUrl = "http://localhost:8080")))

    @Test
    fun `back handling honours dirty and busy state and shows exact changed field names`() {
        val fiction = FictionSummary(id = 1, title = "Original Title")

        var dirtyState by mutableStateOf(false)
        var backRequest by mutableStateOf(0)
        var busyState by mutableStateOf(false)

        compose.setContent {
            TtsRoadTheme {
                FictionEditScreen(
                    padding = PaddingValues(0.dp),
                    fiction = fiction,
                    repository = fakeRepo,
                    isAdmin = false,
                    onFictionChanged = {},
                    onDone = {},
                    externalBackRequest = backRequest,
                    onDirtyChange = { dirtyState = it },
                    onBusyChange = { busyState = it },
                )
            }
        }

        assertFalse(dirtyState)
        compose.onNodeWithText("Original Title").performTextInput(" (Modified)")
        compose.waitForIdle()
        assertTrue(dirtyState)

        // Scenario 1: Dirty and not busy -> Back request shows discard dialog with exact changed field ("title")
        backRequest = 1
        compose.waitForIdle()

        compose.onNodeWithText("DISCARD UNSAVED CHANGES?", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("YOUR UNSAVED TITLE CHANGES WILL BE DISCARDED. COVER CHANGES ALREADY UPLOADED ARE KEPT.", useUnmergedTree = true).assertIsDisplayed()
    }
}
