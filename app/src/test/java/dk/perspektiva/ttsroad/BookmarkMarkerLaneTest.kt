package dk.perspektiva.ttsroad

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import dk.perspektiva.ttsroad.player.BookmarkMarker
import dk.perspektiva.ttsroad.player.PlayerUiState
import dk.perspektiva.ttsroad.player.QueueItem
import dk.perspektiva.ttsroad.player.SleepTimerState
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bookmark strip under the scrub bar (#121), as the player actually composes it.
 *
 * The arithmetic is covered in `player/BookmarkMarkersTest`; what this pins is the pair of
 * properties a reader cannot check by looking at the arithmetic — that a chapter with marks
 * announces them, and that a chapter with none adds nothing to a layout that #101 established has
 * no height to spare.
 */
@RunWith(RobolectricTestRunner::class)
class BookmarkMarkerLaneTest {
    @get:Rule val compose = createComposeRule()

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h640dp")
    fun `a chapter with marks says how many`() {
        renderPlayer(
            listOf(
                BookmarkMarker(id = 1, label = "The gate", positionMs = 60_000, fraction = 0.2f),
                BookmarkMarker(id = 2, label = "Second", positionMs = 900_000, fraction = 0.5f),
            ),
        )

        compose.onNodeWithContentDescription("2 bookmarks in this chapter").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h640dp")
    fun `one mark is announced in the singular`() {
        renderPlayer(
            listOf(BookmarkMarker(id = 1, label = "Only", positionMs = 60_000, fraction = 0.2f)),
        )

        compose.onNodeWithContentDescription("1 bookmark in this chapter").assertIsDisplayed()
    }

    /**
     * The ordinary case. An empty lane must not occupy height: #101 was about the player running
     * out of it, and a 20 dp strip present on every chapter nobody has marked would be a slice of
     * that budget spent on nothing.
     */
    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h640dp")
    fun `a chapter with no marks draws no lane`() {
        renderPlayer(emptyList())

        compose.onNodeWithContentDescription("1 bookmark in this chapter").assertDoesNotExist()
        compose.onNodeWithContentDescription("0 bookmarks in this chapter").assertDoesNotExist()
    }

    private fun renderPlayer(markers: List<BookmarkMarker>) {
        compose.setContent {
            TtsRoadTheme {
                PlayerScreenBody(
                    playerState = PlayerUiState(
                        title = "Chapter 47",
                        fictionTitle = "A Serial",
                        isPlaying = true,
                        hasMedia = true,
                        positionMs = 61_000L,
                        durationMs = 1_800_000L,
                        bufferedPercentage = 40,
                        queue = List(3) { QueueItem(mediaId = "chapter:$it", title = "Chapter $it") },
                        currentIndex = 1,
                        hasNext = true,
                    ),
                    skipIntervalMs = 30_000L,
                    sleepTimerState = SleepTimerState(),
                    actionFeedback = null,
                    canRead = true,
                    canBookmark = true,
                    canReportPronunciation = true,
                    canJumpBack = true,
                    canOpenQueue = true,
                    bookmarkMarkers = markers,
                    onRetry = {},
                    onSeek = {},
                    onPreviousChapter = {},
                    onSkipBack = {},
                    onTogglePlayPause = {},
                    onSkipForward = {},
                    onNextChapter = {},
                    onOpenSpeed = {},
                    onOpenSleepTimer = {},
                    onRead = {},
                    onBookmark = {},
                    onReportPronunciation = {},
                    onOpenJumpBack = {},
                    onOpenChapters = {},
                    onOpenQueue = {},
                )
            }
        }
    }
}
