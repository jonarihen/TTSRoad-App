package dk.perspektiva.ttsroad

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
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

    @Test
    fun `a failed play is shown inline instead of vanishing`() {
        val entry = ChapterNotificationEntry(
            id = 10,
            state = "ready",
            playable = true,
            dismissible = true,
            fiction = ChapterNotificationFiction(id = 7, title = "Test Serial"),
            chapter = ChapterNotificationChapter(id = 101, title = "The Arrival", chapterNumber = 1.0),
        )
        showEntry(entry, onPlay = { throw java.io.IOException("Server unreachable") })

        compose.onNodeWithText("PLAY").performClick()

        compose.onNodeWithText("Server unreachable").assertIsDisplayed()
        compose.onNodeWithText("RETRY").assertIsDisplayed()
        // The row is usable again once the attempt has finished.
        compose.onNodeWithText("PLAY").assertIsEnabled()
    }

    @Test
    fun `a failed open is shown inline instead of vanishing`() {
        val entry = ChapterNotificationEntry(
            id = 10,
            state = "pulled",
            fiction = ChapterNotificationFiction(id = 7, title = "Test Serial"),
            chapter = ChapterNotificationChapter(id = 101, title = "The Arrival", chapterNumber = 1.0),
        )
        showEntry(entry, onOpenFiction = { error("Fiction is gone") })

        compose.onNodeWithText("Test Serial").performClick()

        compose.onNodeWithText("Fiction is gone").assertIsDisplayed()
    }

    private fun showEntry(
        entry: ChapterNotificationEntry,
        onPlay: suspend (ChapterNotificationEntry) -> Unit = {},
        onOpenFiction: suspend (ChapterNotificationEntry) -> Unit = {},
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

    private fun ready(id: Int, fictionId: Int, fiction: String) = ChapterNotificationEntry(
        id = id,
        state = ChapterNotificationState.Ready.wire,
        playable = true,
        fiction = ChapterNotificationFiction(id = fictionId, title = fiction),
        chapter = ChapterNotificationChapter(id = 1000 + id, title = "Chapter $id"),
    )

    @Test
    // Tall viewport: the list is lazy, so the second group must fit on screen to be composed.
    @Config(sdk = [34], qualifiers = "w320dp-h1200dp")
    fun `rows group under their fiction and play new starts at the first chapter`() {
        val played = mutableListOf<Int>()
        val state = NewChaptersState().apply {
            isLoading = false
            loadedOnce = true
            notifications = listOf(ready(1, 7, "Alpha"), ready(2, 8, "Beta"), ready(3, 7, "Alpha"))
        }
        compose.setContent {
            TtsRoadTheme {
                NewChaptersScreen(PaddingValues(0.dp), state, fakeRepo, onPlay = { played += it.id }, onOpenFiction = {})
            }
        }
        // One header per fiction (the title is no longer repeated on every row).
        compose.onAllNodesWithTag("new-chapter-group").assertCountEquals(2)
        compose.onAllNodesWithText("Alpha").assertCountEquals(1)
        compose.onAllNodesWithText("PLAY NEW")[0].performClick()
        assertEquals(listOf(1), played)
    }

    @Test
    fun `a large backlog is lazy and composes only the visible rows`() {
        val state = NewChaptersState().apply {
            isLoading = false
            loadedOnce = true
            notifications = (1..200).map { ready(it, it, "Serial $it") }
        }
        compose.setContent {
            TtsRoadTheme {
                NewChaptersScreen(PaddingValues(0.dp), state, fakeRepo, onPlay = {}, onOpenFiction = {})
            }
        }
        compose.onNodeWithText("Serial 1").assertIsDisplayed()
        compose.onAllNodesWithText("Serial 200").assertCountEquals(0)
    }

    @Test
    fun `an id-less fiction never merges with a real fiction`() {
        val title = ready(1, 0, "Zeta")
        val groups = newChapterGroups(listOf(title, ready(2, "Zeta".hashCode(), "Other"), ready(3, 0, "Zeta")))
        assertEquals(listOf(2, 1), groups.map { it.entries.size })
    }
}
