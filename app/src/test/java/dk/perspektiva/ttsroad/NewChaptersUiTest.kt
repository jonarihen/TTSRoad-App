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
    fun `backlog row shows server wording and alert tag while retaining chapter context and actions`() {
        val entry = ChapterNotificationEntry(
            id = 10,
            kind = "backlog",
            backlogSeconds = 7200.5,
            message = "2 hours ready to listen",
            state = "ready",
            playable = true,
            dismissible = true,
            fiction = ChapterNotificationFiction(id = 7, title = "Test Serial"),
            chapter = ChapterNotificationChapter(id = 101, title = "The Arrival", chapterNumber = 1.0),
        )
        var played: ChapterNotificationEntry? = null
        var opened: ChapterNotificationEntry? = null
        showEntry(entry, onPlay = { played = it }, onOpenFiction = { opened = it })

        compose.onNodeWithText("Test Serial").assertIsDisplayed()
        compose.onNodeWithText("BACKLOG ALERT").assertIsDisplayed()
        compose.onNodeWithText("2 hours ready to listen").assertIsDisplayed()
        compose.onNodeWithText("The Arrival").assertIsDisplayed()
        compose.onNodeWithText("CHAPTER 1  ·  READY TO LISTEN").assertIsDisplayed()
        compose.onNodeWithText("PLAY").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(entry, played)
        compose.onNodeWithText("DISMISS").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Test Serial").performClick()
        assertEquals(entry, opened)
    }

    @Test
    fun `chapter row also prefers server wording without a backlog tag`() {
        val entry = ChapterNotificationEntry(
            id = 10,
            message = "A new chapter is ready",
            state = "ready",
            fiction = ChapterNotificationFiction(title = "Test Serial"),
            chapter = ChapterNotificationChapter(title = "The Arrival", chapterNumber = 1.0),
        )
        showEntry(entry)

        compose.onNodeWithText("A new chapter is ready").assertIsDisplayed()
        compose.onNodeWithText("The Arrival").assertIsDisplayed()
        compose.onNodeWithText("BACKLOG ALERT").assertDoesNotExist()
    }

    @Test
    fun `rows fall back to chapter title when server wording is absent or blank`() {
        val entry = ChapterNotificationEntry(
            id = 10,
            state = "ready",
            fiction = ChapterNotificationFiction(title = "Test Serial"),
            chapter = ChapterNotificationChapter(title = "The Arrival", chapterNumber = 1.0),
        )
        val state = showEntry(entry)

        for (kind in listOf("chapter", "backlog")) {
            for (message in listOf(null, "", " \n\t")) {
                compose.runOnIdle {
                    state.notifications = listOf(entry.copy(kind = kind, message = message))
                }
                compose.onNodeWithText("Test Serial").assertIsDisplayed()
                compose.onNodeWithText("The Arrival").assertIsDisplayed()
                compose.onNodeWithText("CHAPTER 1  ·  READY TO LISTEN").assertIsDisplayed()
                if (kind == "backlog") {
                    compose.onNodeWithText("BACKLOG ALERT").assertIsDisplayed()
                } else {
                    compose.onNodeWithText("BACKLOG ALERT").assertDoesNotExist()
                }
            }
        }
    }

    @Test
    fun `backlog actions follow server flags independently of ready state`() {
        val entry = ChapterNotificationEntry(
            id = 10,
            kind = "backlog",
            message = "2 hours ready to listen",
            state = "ready",
            playable = false,
            dismissible = false,
            fiction = ChapterNotificationFiction(title = "Test Serial"),
            chapter = ChapterNotificationChapter(title = "The Arrival"),
        )
        val state = showEntry(entry)

        compose.onNodeWithText("2 hours ready to listen").assertIsDisplayed()
        compose.onNodeWithText("PLAY").assertDoesNotExist()
        compose.onNodeWithText("DISMISS").assertDoesNotExist()

        compose.runOnIdle {
            state.notifications = listOf(entry.copy(playable = true))
        }
        compose.onNodeWithText("PLAY").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("DISMISS").assertDoesNotExist()

        compose.runOnIdle {
            state.notifications = listOf(entry.copy(dismissible = true))
        }
        compose.onNodeWithText("PLAY").assertDoesNotExist()
        compose.onNodeWithText("DISMISS").assertIsDisplayed().assertIsEnabled()
    }

    private fun showEntry(
        entry: ChapterNotificationEntry,
        onPlay: (ChapterNotificationEntry) -> Unit = {},
        onOpenFiction: (ChapterNotificationEntry) -> Unit = {},
    ): NewChaptersState {
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
                    onPlay = onPlay,
                    onOpenFiction = onOpenFiction,
                )
            }
        }
        return state
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
            chapter = ChapterNotificationChapter(title = "Chapter 1", chapterNumber = 1.0),
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
