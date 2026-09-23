package dk.perspektiva.ttsroad

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import dk.perspektiva.ttsroad.update.ReleaseInfo
import dk.perspektiva.ttsroad.update.UpdateState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h320dp")
class UpdateDialogShortLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `long notes leave both actions reachable in a short window`() {
        compose.setContent {
            TtsRoadTheme {
                UpdateOverlay(
                    state = UpdateState.Available(
                        ReleaseInfo(
                            versionName = "0.17.0",
                            notes = (1..60).joinToString("\n") { "- change number $it" },
                            apkUrl = "https://x.test/a.apk",
                        ),
                    ),
                    onDownload = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("DOWNLOAD & INSTALL").assertIsDisplayed()
        compose.onNodeWithText("LATER").assertIsDisplayed()
        val notes = compose.onNodeWithTag("update-notes").getUnclippedBoundsInRoot()
        val notesHeight = notes.bottom - notes.top
        assertTrue(
            "notes consume ${notesHeight.value} dp of a 320 dp-high window",
            notesHeight <= 320.dp * 0.42f + 1.dp,
        )
    }
}
