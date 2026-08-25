package dk.perspektiva.ttsroad

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.data.AudioInfo
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.PlaybackInfo
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
 * #104: several high-frequency controls made their touch area exactly as small as their glyph.
 *
 * The chapter-row actions were the worst of them at 36 dp, and they are the ones where a near miss
 * *does something*: Read, Download, Mark played and Play sit next to each other, so a finger that
 * lands 6 dp off starts a chapter instead of downloading it. These are the controls used while
 * walking and reaching one-handed, which is exactly when a finger lands 6 dp off.
 *
 * Every assertion here measures the node that takes the tap, not the glyph drawn inside it. That
 * distinction is the fix: visual size and interaction size were the same box and are now two.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class TouchTargetTest {
    @get:Rule val compose = createComposeRule()

    /**
     * The four sizes `TransportIconButton` is actually called at: 36 dp on a chapter row, 42 dp in
     * the mini player, 46 dp for the player's secondary transport, 68 dp for play/pause. Testing
     * the component across its whole range covers the mini player too, which cannot be rendered on
     * the JVM — it takes a live `PlaybackController` bound to the media service.
     */
    @Test
    fun `a transport button is a 48 dp target at every size it is drawn`() {
        val drawnSizes = listOf(36.dp, 42.dp, 46.dp, 68.dp)
        compose.setContent {
            TtsRoadTheme {
                Column {
                    drawnSizes.forEach { drawn ->
                        TransportIconButton(
                            icon = Icons.Default.PlayArrow,
                            contentDescription = "Play at ${drawn.value.toInt()}",
                            enabled = true,
                            size = drawn,
                        ) {}
                    }
                }
            }
        }

        drawnSizes.forEach { drawn ->
            compose.onNodeWithContentDescription("Play at ${drawn.value.toInt()}")
                .assertIsAtLeastATouchTarget()
        }
    }

    @Test
    fun `the tap lands on the target, not only on the glyph`() {
        var taps = 0
        compose.setContent {
            TtsRoadTheme {
                TransportIconButton(
                    icon = Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    enabled = true,
                    size = 36.dp,
                ) { taps++ }
            }
        }

        compose.onNodeWithContentDescription("Play").performClick()

        assertEquals(1, taps)
    }

    @Test
    fun `every chapter-row action is a 48 dp target`() {
        compose.setContent {
            TtsRoadTheme {
                ChapterRow(
                    chapter = chapter,
                    fiction = null,
                    onPlay = {},
                    onMarkPlayed = {},
                    onOpenReader = {},
                    onToggleDownload = {},
                )
            }
        }

        for (description in chapterRowActions) {
            compose.onNodeWithContentDescription(description).assertIsAtLeastATouchTarget()
        }
    }

    /**
     * Four 48 dp targets in a row on a 360 dp phone is 192 dp of the width, so this is the case
     * where enlarging them could have made two of them share a strip of pixels — and an overlap is
     * worse than a small target, because the wrong action wins in a way nothing signals.
     */
    @Test
    fun `enlarged chapter-row targets do not overlap each other`() {
        compose.setContent {
            TtsRoadTheme {
                ChapterRow(
                    chapter = chapter,
                    fiction = null,
                    onPlay = {},
                    onMarkPlayed = {},
                    onOpenReader = {},
                    onToggleDownload = {},
                )
            }
        }

        val bounds = chapterRowActions.map { description ->
            description to compose.onNodeWithContentDescription(description)
                .getUnclippedBoundsInRoot()
                .let { Rect(it.left.value, it.top.value, it.right.value, it.bottom.value) }
        }
        for ((firstName, first) in bounds) {
            for ((secondName, second) in bounds) {
                if (firstName == secondName) continue
                assertTrue(
                    "$firstName and $secondName overlap: $first vs $second",
                    first.overlaps(second).not(),
                )
            }
        }
    }

    @Test
    fun `every player action is a 48 dp target`() {
        compose.setContent {
            TtsRoadTheme {
                PlayerScreenBody(
                    playerState = PlayerUiState(
                        title = "Chapter 47",
                        isPlaying = true,
                        hasMedia = true,
                        durationMs = 1_800_000L,
                        queue = List(4) { QueueItem("chapter:$it", "Chapter $it") },
                        hasNext = true,
                    ),
                    skipIntervalMs = 30_000L,
                    sleepTimerState = SleepTimerState(),
                    bookmarkFeedback = null,
                    canRead = true,
                    canBookmark = true,
                    canJumpBack = true,
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
                    onOpenJumpBack = {},
                    onOpenChapters = {},
                )
            }
        }

        for (description in listOf("Previous chapter", "Back 30s", "Pause", "Forward 30s", "Next chapter")) {
            compose.onNodeWithContentDescription(description).assertIsAtLeastATouchTarget()
        }
        // Tertiary actions are TextButtons, which Material stops at 40 dp. Width is not asserted:
        // a label sets it well past 48 dp, and forcing a minimum would only pad short labels.
        for (label in listOf("SPEED 1×", "SLEEP", "READ", "BOOKMARK", "JUMP BACK")) {
            compose.onNodeWithText(label).assertHeightIsAtLeast(MinimumTarget)
        }
    }

    /**
     * "Off" is the shortest label the reader sheet has, and the chips are as wide as their labels,
     * so width is the half of this that actually bites — the taller-than-48 dp text style meant the
     * height was never the problem, whatever the issue estimated.
     */
    @Test
    fun `a reader option chip is a 48 dp target and says which one is chosen`() {
        compose.setContent {
            TtsRoadTheme {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderOptionChip(label = "Off", selected = false, onClick = {})
                    ReaderOptionChip(label = "Night", selected = true, onClick = {})
                }
            }
        }

        compose.onNodeWithText("OFF").assertIsAtLeastATouchTarget()
        // Selection was signalled by colour alone, which a screen reader cannot read out.
        compose.onNodeWithText("NIGHT").assertIsSelected()
    }

    private fun SemanticsNodeInteraction.assertIsAtLeastATouchTarget() = this
        .assertWidthIsAtLeast(MinimumTarget)
        .assertHeightIsAtLeast(MinimumTarget)

    private companion object {
        /**
         * Deliberately 48 dp written out, not `ui.MinTouchTargetSize`.
         *
         * Asserting against the same constant the production code reads makes every test here
         * vacuous — set the constant to zero and the code shrinks, the assertion shrinks with it,
         * and the suite stays green. Which is exactly what happened when this was checked. The
         * figure is Android's, not this app's, so the test states it independently.
         */
        val MinimumTarget = 48.dp

        val chapterRowActions = listOf("Read along", "Download for offline", "Mark played", "Play")

        val chapter = ChapterSummary(
            id = 1,
            fictionId = 7,
            title = "Chapter 12: A Long Enough Title To Take The Width",
            displayNumber = 12.0,
            status = "ready",
            audio = AudioInfo(url = "https://example.invalid/audio/12.mp3"),
            playback = PlaybackInfo(positionSeconds = 30.0),
        )
    }
}
