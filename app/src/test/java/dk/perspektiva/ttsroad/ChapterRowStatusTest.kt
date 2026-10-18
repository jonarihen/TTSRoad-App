package dk.perspektiva.ttsroad

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dk.perspektiva.ttsroad.data.ChapterStatus
import dk.perspektiva.ttsroad.data.ChapterSubStatus
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.ui.ErrorDetailsToggleTag
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #488: an unplayable chapter says why in a listener's words, and keeps the pipeline's words and
 * the server's message behind Details for whoever runs the server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class ChapterRowStatusTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `a failed chapter says it couldn't be prepared and hides the server's reason until asked`() {
        render(
            ChapterSummary(
                id = 1,
                title = "The Locked One",
                status = ChapterStatus.Error,
                errorMessage = "Chapter is locked behind a pledge tier",
            ),
        )

        compose.onNodeWithText("COULDN'T PREPARE").assertIsDisplayed()
        compose.onNodeWithText("Couldn't prepare this chapter.").assertIsDisplayed()
        compose.onNodeWithText("Chapter is locked behind a pledge tier", substring = true)
            .assertDoesNotExist()

        compose.onNodeWithTag(ErrorDetailsToggleTag).performClick()

        compose.onNodeWithText("Server said: Chapter is locked behind a pledge tier", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `a chapter in the pipeline reads as getting ready, not as a pipeline stage`() {
        render(
            ChapterSummary(
                id = 1,
                title = "The Busy One",
                status = ChapterStatus.Processing,
                subStatus = ChapterSubStatus.Preprocessing,
            ),
        )

        compose.onNodeWithText("GETTING READY…").assertIsDisplayed()
        compose.onNodeWithText("CLEANING").assertDoesNotExist()

        compose.onNodeWithTag(ErrorDetailsToggleTag).performClick()

        compose.onNodeWithText("Status: cleaning", substring = true).assertIsDisplayed()
    }

    private fun render(chapter: ChapterSummary) {
        compose.setContent {
            TtsRoadTheme {
                ChapterRow(chapter = chapter, fiction = null, onPlay = {})
            }
        }
    }
}
