package dk.perspektiva.ttsroad

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
 * #101: every control below the cover was laid out past the bottom of a short window.
 *
 * The cover was the only weighted child of the player's root `Column`, so it absorbed whatever
 * height was spare — and when there was none spare it collapsed to zero and Compose had nothing
 * left to try. Title, scrubber, buffer bar, the 68 dp transport row and the whole tertiary action
 * area were simply placed below the window, with no scroll and no alternative layout.
 *
 * On a player that is not a cosmetic problem. Pause, the sleep timer and chapter navigation being
 * unreachable in landscape is the failure, and landscape is where a phone spends its time in a car
 * mount.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerLayoutTest {
    @get:Rule val compose = createComposeRule()

    /** Every action the player offers, by the content description or label a user would hit. */
    private val transportControls = listOf(
        "Previous chapter",
        // Lower-case s: content descriptions are not the AARIS uppercase label style.
        "Back 30s",
        "Pause",
        "Forward 30s",
        "Next chapter",
    )

    @Test
    @Config(sdk = [34], qualifiers = "w640dp-h360dp")
    fun `every player action is reachable in landscape`() {
        renderPlayer()

        for (description in transportControls) {
            compose.onNodeWithContentDescription(description).performScrollTo().assertIsDisplayed()
        }
        for (label in listOf("SPEED 1×", "SLEEP", "READ", "BOOKMARK", "SAID WRONG", "JUMP BACK")) {
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("CHAPTERS 2/12").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w640dp-h360dp")
    fun `the primary control still works once scrolled to in landscape`() {
        var toggled = 0
        renderPlayer(onTogglePlayPause = { toggled++ })

        compose.onNodeWithContentDescription("Pause").performScrollTo().performClick()

        assertEquals(1, toggled)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w640dp-h360dp")
    fun `a short window scrolls`() {
        renderPlayer()

        compose.onNode(hasScrollAction()).assertExists()
    }

    /**
     * Portrait must *not* scroll. The tall layout gives its leftover height to the cover, and a
     * scrolling container has no leftover height to give — so if this ever starts passing, the
     * artwork has silently stopped filling the screen on every phone held the normal way.
     */
    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp")
    fun `portrait keeps the tall layout and does not scroll`() {
        renderPlayer()

        compose.onNode(hasScrollAction()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
    }

    /**
     * The second way to run out of room, and the one a viewport-only breakpoint misses entirely: an
     * ordinary portrait phone at a large display size. The window is a perfectly normal 640 dp and
     * the text in it is half again as tall, so the fixed content outgrows the screen exactly as it
     * does in landscape.
     */
    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h640dp", fontScale = 1.5f)
    fun `a large display size takes the same exit as a short window`() {
        renderPlayer()

        compose.onNode(hasScrollAction()).assertExists()
        compose.onNodeWithContentDescription("Pause").performScrollTo().assertIsDisplayed()
    }

    private fun renderPlayer(onTogglePlayPause: () -> Unit = {}) {
        compose.setContent {
            TtsRoadTheme {
                PlayerScreenBody(
                    playerState = PlayerUiState(
                        title = "Chapter 47: The Lighthouse Keeper Does Not Sleep",
                        fictionTitle = "Some Very Long Serial Indeed",
                        isPlaying = true,
                        hasMedia = true,
                        positionMs = 61_000L,
                        durationMs = 1_800_000L,
                        bufferedPercentage = 40,
                        queue = List(12) { QueueItem(mediaId = "chapter:$it", title = "Chapter $it") },
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
                    onRetry = {},
                    onSeek = {},
                    onPreviousChapter = {},
                    onSkipBack = {},
                    onTogglePlayPause = onTogglePlayPause,
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
