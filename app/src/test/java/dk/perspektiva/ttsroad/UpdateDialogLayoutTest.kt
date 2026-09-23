package dk.perspektiva.ttsroad

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import dk.perspektiva.ttsroad.update.ReleaseInfo
import dk.perspektiva.ttsroad.update.UpdateState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The update dialog on the narrow phone it has to survive.
 *
 * This dialog asks permission to install something, and the release notes are the only part of it
 * that says what the install changes. Before this, a long body was cut at 400 characters with
 * nothing on screen admitting anything was missing, and Markdown markers were drawn literally.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class UpdateDialogLayoutTest {
    @get:Rule val compose = createComposeRule()

    private fun show(notes: String) {
        compose.setContent {
            TtsRoadTheme {
                UpdateOverlay(
                    state = UpdateState.Available(
                        ReleaseInfo(versionName = "0.17.0", notes = notes, apkUrl = "https://x.test/a.apk"),
                    ),
                    onDownload = {},
                    onDismiss = {},
                )
            }
        }
    }

    @Test
    fun `the end of a long release note is reachable`() {
        show((1..60).joinToString("\n") { "- change number $it" })

        compose.onNodeWithText("• change number 60", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `the install action stays on screen while the notes scroll`() {
        // The notes are bounded so the dialog cannot grow past the viewport and push its own
        // buttons off. A reader who cannot reach DOWNLOAD cannot act on any of this.
        show((1..60).joinToString("\n") { "- change number $it" })

        compose.onNodeWithText("DOWNLOAD & INSTALL").assertIsDisplayed()
        compose.onNodeWithText("LATER").assertIsDisplayed()
    }

    @Test
    fun `markdown markers never reach the screen`() {
        show("## Added\n- **Recently listened** ordering\n- Fixed `EPUB` downloads")

        compose.onNodeWithText("Added", substring = true).assertIsDisplayed()
        compose.onNodeWithText("**", substring = true).assertDoesNotExist()
        compose.onNodeWithText("##", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a release with no notes still offers the version and the action`() {
        show("")

        compose.onNodeWithTag("update-notes").assertIsDisplayed()
        // MetaText draws the version uppercased, which is what the semantics tree reports.
        compose.onNodeWithText("VERSION 0.17.0", substring = true).assertIsDisplayed()
        compose.onNodeWithText("DOWNLOAD & INSTALL").assertIsDisplayed()
    }
}
