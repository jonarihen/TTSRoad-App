package dk.perspektiva.ttsroad

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which ways into the library a server actually offers (#114).
 *
 * The two are separate server capabilities and the backend says why: multipart EPUB import is not
 * the same promise as JSON fiction CRUD, and a deployment may support one without the other. So
 * neither control may assume the other, and drawing a button the server cannot serve is the failure
 * mode this guards — from the user's side, "this server is older than my app" and "this app is
 * broken" look identical.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class AddFictionSectionTest {
    @get:Rule val compose = createComposeRule()

    private fun render(
        canAddByUrl: Boolean,
        canUploadEpub: Boolean,
        maxEpubBytes: Long = 100L * 1024 * 1024,
    ) {
        compose.setContent {
            TtsRoadTheme {
                AddFictionSection(
                    canAddByUrl = canAddByUrl,
                    canUploadEpub = canUploadEpub,
                    maxEpubBytes = maxEpubBytes,
                )
            }
        }
    }

    @Test
    fun `a server that takes both offers both`() {
        render(canAddByUrl = true, canUploadEpub = true)

        compose.onNodeWithText("ADD FICTION").assertIsDisplayed()
        compose.onNodeWithText("UPLOAD AN EPUB").assertIsDisplayed()
    }

    @Test
    fun `a server without epub upload does not offer a file picker`() {
        render(canAddByUrl = true, canUploadEpub = false)

        compose.onNodeWithText("ADD FICTION").assertIsDisplayed()
        compose.onNodeWithText("UPLOAD AN EPUB").assertDoesNotExist()
    }

    @Test
    fun `the form opens on the newest 25, not on the whole backlog`() {
        // The bug this control exists for. With no sync_limit the server converts *every* chapter
        // — `add_fiction` branches on `if body.sync_limit:` — so the form opening on anything but a
        // bounded window is how a 400-chapter serial quietly becomes 400 chapters of TTS.
        render(canAddByUrl = true, canUploadEpub = false)
        compose.onNodeWithText("ADD FICTION").performClick()

        compose.onNodeWithText("FICTION URL OR ID").assertIsDisplayed()
        compose.onNodeWithText("NEWEST").assertIsSelected()
        compose.onNodeWithText("EVERYTHING").assertIsNotSelected()
        // Existence rather than display: on a 640 dp window the sheet does not open tall enough
        // to reach its own summary line, and what this test is about is the value the form starts
        // with, not where that line lands. The two chip assertions above are the on-screen half.
        compose.onNodeWithText("THE 25 NEWEST CHAPTERS").assertExists()
    }

    @Test
    fun `choosing everything says what it costs`() {
        // "All chapters" is a legitimate choice and a slow one, and the only place that is visible
        // before it is made is this line.
        render(canAddByUrl = true, canUploadEpub = false)
        compose.onNodeWithText("ADD FICTION").performClick()
        compose.onNodeWithText("EVERYTHING").performClick()

        compose.onNodeWithText("HOW MANY CHAPTERS").assertDoesNotExist()
        compose.onNodeWithText("hours of narration", substring = true, ignoreCase = true)
            .assertExists()
    }

    @Test
    fun `a rate the server cannot read is caught before the request`() {
        // The PATCH stores whatever string it is handed without checking it, so an unsigned "10"
        // does not fail on save — it fails hours later as a chapter that will not narrate.
        render(canAddByUrl = true, canUploadEpub = false)
        compose.onNodeWithText("ADD FICTION").performClick()
        compose.onNodeWithText("FICTION URL OR ID").performTextInput("12345")
        compose.onNodeWithText("SPEECH RATE").performTextInput("quickly")

        compose.onNodeWithText("A RATE READS LIKE +0%, +25% OR -10%.")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onAllNodesWithText("ADD FICTION").onLast().assertIsNotEnabled()
    }

    @Test
    fun `a server that takes files but not urls offers only the upload`() {
        // The unlikely half of the backend's own distinction, and the one a client gets wrong by
        // assuming: the section still has a reason to exist without the URL field.
        render(canAddByUrl = false, canUploadEpub = true)

        compose.onNodeWithText("UPLOAD AN EPUB").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("ADD FICTION").assertDoesNotExist()
    }

    @Test
    fun `the ceiling shown is the one this server advertised`() {
        // Not the client's assumed 100 MB: a server is free to publish a smaller limit, and the
        // number under the button is the only place a user sees it before picking a file.
        render(canAddByUrl = true, canUploadEpub = true, maxEpubBytes = 20L * 1024 * 1024)

        // MetaText renders in caps, which is the theme's doing and not the copy's.
        compose.onNodeWithText("up to 20 MB", substring = true, ignoreCase = true).assertExists()
    }
}
