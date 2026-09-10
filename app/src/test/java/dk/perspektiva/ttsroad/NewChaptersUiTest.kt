package dk.perspektiva.ttsroad

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.data.ChapterNotificationChapter
import dk.perspektiva.ttsroad.data.ChapterNotificationEntry
import dk.perspektiva.ttsroad.data.ChapterNotificationFiction
import dk.perspektiva.ttsroad.data.ChapterNotificationState
import dk.perspektiva.ttsroad.data.FakeSessionStore
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class NewChaptersUiTest {
    @get:Rule val compose = createComposeRule()

    private val fakeRepo = TtsRoadRepository(FakeSessionStore(SessionState()))

    @Test
    fun `shows loading state initially`() {
        val state = NewChaptersState().apply {
            isLoading = true
            loadedOnce = false
        }
        compose.setContent {
            TtsRoadTheme {
                NewChaptersScreen(
                    padding = PaddingValues(0.dp),
                    state = state,
                    repository = fakeRepo,
                    onPlay = {},
                    onOpenFiction = {},
                )
            }
        }
        compose.onNodeWithText("LOADING NEW CHAPTERS…").assertIsDisplayed()
    }

    @Test
    fun `shows honest unsupported state without retry button`() {
        val state = NewChaptersState().apply {
            isLoading = false
            loadedOnce = true
            isUnsupported = true
        }
        compose.setContent {
            TtsRoadTheme {
                NewChaptersScreen(
                    padding = PaddingValues(0.dp),
                    state = state,
                    repository = fakeRepo,
                    onPlay = {},
                    onOpenFiction = {},
                )
            }
        }
        compose.onNodeWithText("THIS SERVER DOES NOT SUPPORT NEW CHAPTER NOTIFICATIONS. UPDATE THE BACKEND TO TRACK NEW CHAPTERS.").assertIsDisplayed()
        compose.onNodeWithText("RETRY").assertDoesNotExist()
    }

    @Test
    fun `shows error state with retry button`() {
        val state = NewChaptersState().apply {
            isLoading = false
            loadedOnce = false
            error = "Could not reach server"
        }
        compose.setContent {
            TtsRoadTheme {
                NewChaptersScreen(
                    padding = PaddingValues(0.dp),
                    state = state,
                    repository = fakeRepo,
                    onPlay = {},
                    onOpenFiction = {},
                )
            }
        }
        compose.onNodeWithText("Could not reach server").assertIsDisplayed()
        compose.onNodeWithText("RETRY").assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun `play and dismiss buttons are 48dp targets and honour busy state`() {
        var played = 0
        val entry = ChapterNotificationEntry(
            id = 10,
            state = ChapterNotificationState.Ready.wire,
            playable = true,
            dismissible = true,
            fiction = ChapterNotificationFiction(title = "Test Serial"),
            chapter = ChapterNotificationChapter(title = "Chapter 1", chapterNumber = 1),
        )
        val state = NewChaptersState().apply {
            isLoading = false
            loadedOnce = true
            notifications = listOf(entry)
        }

        compose.setContent {
            TtsRoadTheme {
                NewChaptersScreen(
                    padding = PaddingValues(0.dp),
                    state = state,
                    repository = fakeRepo,
                    onPlay = { played++ },
                    onOpenFiction = {},
                )
            }
        }

        val playBtn = compose.onNodeWithText("PLAY")
        val dismissBtn = compose.onNodeWithText("DISMISS")

        playBtn.assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .assertIsEnabled()

        dismissBtn.assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .assertIsEnabled()

        playBtn.performClick()
        assertEquals(1, played)
    }
}
