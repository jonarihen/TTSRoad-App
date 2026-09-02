package dk.perspektiva.ttsroad

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dk.perspektiva.ttsroad.player.PlayerUiState
import dk.perspektiva.ttsroad.player.QueueItem
import dk.perspektiva.ttsroad.player.SleepTimerState
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /** The icon actions, which carry no visible label — the head group and the scrubber group. */
    private val iconActions = listOf(
        "Read along",
        "Chapters, 2 of 12",
        "Up next",
        "Jump back to where you were",
        "Bookmark this moment",
        "Report a mispronunciation",
    )

    @Test
    @Config(sdk = [34], qualifiers = "w640dp-h360dp")
    fun `every player action is reachable in landscape`() {
        renderPlayer()

        for (description in transportControls + iconActions) {
            compose.onNodeWithContentDescription(description).performScrollTo().assertIsDisplayed()
        }
        // What is left with a written label is the three settings, and they say their own state.
        for (label in listOf("SPEED 1×", "SLEEP", "SKIP SILENCE")) {
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp")
    fun `the chapter position survives the move to an icon`() {
        // The count was the one thing "CHAPTERS 53/246" said that a list glyph cannot, so it rides
        // along as a badge. Losing it would make the player the only screen that cannot say how far
        // through the book you are.
        renderPlayer()

        compose.onNodeWithText("2/12").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp")
    fun `marking the moment sits nearer the thumb than leaving the player does`() {
        // #159's split, kept but re-expressed for the layout that replaced the eight-button wall.
        //
        // The rule is still "changes or marks what is playing right now" against "takes me
        // somewhere else". What changed is how rank is spent: the marking group moved *down* onto
        // the scrubber, beside the transport row where the thumb already is, and the navigation
        // group moved up into the player's head, deliberately out of reach. Bottom is the prize on
        // a phone, so this asserts the opposite ordering to the one #159 left behind — and it is
        // the same rule, not a reversal of it.
        renderPlayer()

        val markTop = listOf("Bookmark this moment", "Report a mispronunciation")
            .map { compose.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot.top }
            .min()
        val leaveTop = listOf("Read along", "Chapters, 2 of 12", "Up next")
            .map { compose.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot.top }
            .max()

        assertTrue(
            "marking actions ($markTop) must sit below navigation actions ($leaveTop)",
            markTop > leaveTop,
        )
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp")
    fun `the marking group stays within reach of the transport row`() {
        // The other half of the claim above, and the one that would quietly rot: "below the head"
        // is satisfied by anything, including a control pushed under the toolbar at the very foot.
        // What the split is actually buying is adjacency to the controls the thumb is already on.
        renderPlayer()

        val bookmarkBottom = compose.onNodeWithContentDescription("Bookmark this moment")
            .fetchSemanticsNode().boundsInRoot.bottom
        val pauseTop = compose.onNodeWithContentDescription("Pause")
            .fetchSemanticsNode().boundsInRoot.top
        val screenHeight = compose.onRoot().fetchSemanticsNode().boundsInRoot.height

        assertTrue(
            "the bookmark ($bookmarkBottom) must sit above the transport row ($pauseTop)",
            bookmarkBottom <= pauseTop,
        )
        assertTrue(
            "the bookmark ($bookmarkBottom) must be in the lower half of an 891 dp window",
            bookmarkBottom > screenHeight / 2,
        )
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp")
    fun `the foot of the player holds three settings and nothing else`() {
        // The clutter this pass removed, asserted as an absence so it cannot creep back one label
        // at a time. These six were text buttons in the same FlowRow as SPEED and SLEEP.
        renderPlayer()

        for (gone in listOf("READ", "BOOKMARK", "SAID WRONG", "JUMP BACK", "UP NEXT")) {
            compose.onNodeWithText(gone).assertDoesNotExist()
        }
        compose.onNodeWithText("CHAPTERS 2/12").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp")
    fun `skip silence says which way it is set`() {
        // It moved out of the speed sheet, where it was a switch, onto a button that has to carry
        // its own state — a toggle you cannot read is one people press twice to find out.
        renderPlayer(skipSilence = true)

        compose.onNodeWithText("SKIP SILENCE ON").assertIsDisplayed()
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

    private fun renderPlayer(
        onTogglePlayPause: () -> Unit = {},
        skipSilence: Boolean = false,
    ) {
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
                    skipSilence = skipSilence,
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
