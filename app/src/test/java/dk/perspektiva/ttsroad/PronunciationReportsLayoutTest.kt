package dk.perspektiva.ttsroad

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.data.PronunciationReport
import dk.perspektiva.ttsroad.player.PlayerUiState
import dk.perspektiva.ttsroad.player.QueueItem
import dk.perspektiva.ttsroad.player.SleepTimerState
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two on-screen surfaces of "that word is wrong" (#125): the player action and the review list.
 *
 * Both are gated on one capability flag and both have to survive the ordinary case, which is a
 * report with **no word** — the phone only ever knows one when a timed read-along document happens
 * to be loaded, and the contract is explicit that a report without one is still the whole point.
 * A list that rendered those as blank rows would make the capture action look broken.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class PronunciationReportsLayoutTest {
    @get:Rule val compose = createComposeRule()

    private val withWord = PronunciationReport(
        id = 7,
        fictionId = 1,
        fictionTitle = "A Test Serial",
        chapterId = 10,
        chapterNumber = 4.0,
        chapterTitle = "Powerful",
        positionSeconds = 283.5,
        word = "Kaelith",
    )

    private val withoutWord = PronunciationReport(
        id = 8,
        fictionId = 1,
        fictionTitle = "A Test Serial",
        chapterId = 11,
        chapterTitle = "Quieter",
        positionSeconds = 61.0,
    )

    @Test
    fun `a server without the capability offers no capture action`() {
        renderPlayer(canReportPronunciation = false)

        compose.onNodeWithText("SAID WRONG").assertDoesNotExist()
        // The neighbouring action is untouched: the two capabilities are gated separately.
        compose.onNodeWithText("BOOKMARK").assertIsDisplayed()
    }

    @Test
    fun `a server with the capability offers the capture action next to bookmark`() {
        var pressed = 0
        renderPlayer(canReportPronunciation = true, onReportPronunciation = { pressed++ })

        // Portrait keeps the player's non-scrolling layout, so the action has to be on screen
        // as drawn rather than merely reachable.
        compose.onNodeWithText("SAID WRONG").assertIsDisplayed()
        compose.onNodeWithText("SAID WRONG").performClick()

        assertEquals(1, pressed)
    }

    @Test
    fun `a report filed with no word still reads as something worth keeping`() {
        renderReports(listOf(withoutWord))

        // The position is the headline when there is no word, because a locked-phone capture knows
        // the second and nothing else — and that second is the entire value of the report.
        compose.onNodeWithText("Word not captured").assertIsDisplayed()
        compose.onNodeWithText("1:01").assertIsDisplayed()
        // MetaText renders the AARIS mono caps, which is what the semantics tree carries.
        compose.onNodeWithText("QUIETER").assertIsDisplayed()
    }

    @Test
    fun `a report that caught the word leads with it`() {
        renderReports(listOf(withWord))

        compose.onNodeWithText("Kaelith").assertIsDisplayed()
        compose.onNodeWithText("4:43").assertIsDisplayed()
        compose.onNodeWithText("CH 4").assertIsDisplayed()
    }

    @Test
    fun `the list counts what it is showing`() {
        renderReports(listOf(withWord, withoutWord))

        compose.onNodeWithText("§ PR").assertIsDisplayed()
        compose.onNodeWithText("2 REPORTS").assertIsDisplayed()
    }

    @Test
    fun `open reports are the default view and the filter says so`() {
        renderReports(listOf(withWord))

        compose.onNodeWithText("OPEN").assertIsSelected()
        compose.onNodeWithText("ALL").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `asking for resolved rows is a new request, not a local filter`() {
        // `include_resolved` is a server-side query and the resolved rows were never fetched, so
        // the toggle has to report upwards rather than filter the list in place.
        var asked: Boolean? = null
        renderReports(listOf(withWord), onSetIncludeResolved = { asked = it })

        compose.onNodeWithText("ALL").performScrollTo().performClick()

        assertEquals(true, asked)
    }

    @Test
    fun `a resolved report says so, because that is why it stopped being wrong`() {
        renderReports(listOf(withWord.copy(resolved = true)), includeResolved = true)

        compose.onNodeWithText("RESOLVED").assertIsDisplayed()
    }

    @Test
    fun `an empty list explains where reports come from`() {
        renderReports(emptyList())

        compose.onNodeWithText("§ PR").assertIsDisplayed()
        compose.onNodeWithText("NOTHING REPORTED").assertIsDisplayed()
    }

    @Test
    fun `delete is the undo for a mistaken tap and names its row`() {
        var deleted: PronunciationReport? = null
        renderReports(listOf(withWord), onDelete = { deleted = it })

        compose.onNodeWithText("DELETE").performScrollTo().performClick()

        assertEquals(withWord, deleted)
    }

    /**
     * Nothing may be deletable while a delete is in flight. The server owns the list and the screen
     * reloads it afterwards, so a second press during that window would act on a stale row.
     */
    @Test
    fun `a delete already in flight offers no second one`() {
        renderReports(listOf(withWord), isBusy = true)

        compose.onNodeWithText("DELETE").assertDoesNotExist()
    }

    private fun renderReports(
        reports: List<PronunciationReport>,
        includeResolved: Boolean = false,
        isBusy: Boolean = false,
        onSetIncludeResolved: (Boolean) -> Unit = {},
        onDelete: (PronunciationReport) -> Unit = {},
    ) {
        compose.setContent {
            TtsRoadTheme {
                PronunciationReportsBody(
                    padding = PaddingValues(0.dp),
                    reports = reports,
                    isLoading = false,
                    error = null,
                    includeResolved = includeResolved,
                    isBusy = isBusy,
                    onSetIncludeResolved = onSetIncludeResolved,
                    onOpenReader = null,
                    onDelete = onDelete,
                    onRefresh = {},
                )
            }
        }
    }

    private fun renderPlayer(
        canReportPronunciation: Boolean,
        onReportPronunciation: () -> Unit = {},
    ) {
        compose.setContent {
            TtsRoadTheme {
                PlayerScreenBody(
                    playerState = PlayerUiState(
                        title = "Chapter 47",
                        fictionTitle = "A Test Serial",
                        isPlaying = true,
                        hasMedia = true,
                        positionMs = 283_500L,
                        durationMs = 1_800_000L,
                        queue = List(4) { QueueItem("chapter:$it", "Chapter $it") },
                        currentIndex = 1,
                        hasNext = true,
                    ),
                    skipIntervalMs = 30_000L,
                    sleepTimerState = SleepTimerState(),
                    actionFeedback = null,
                    canRead = true,
                    canBookmark = true,
                    canReportPronunciation = canReportPronunciation,
                    canJumpBack = true,
                    canOpenQueue = true,
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
                    onReportPronunciation = onReportPronunciation,
                    onOpenJumpBack = {},
                    onOpenChapters = {},
                    onOpenQueue = {},
                )
            }
        }
    }
}
