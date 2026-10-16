package dk.perspektiva.ttsroad

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import dk.perspektiva.ttsroad.player.PlayerUiState
import dk.perspektiva.ttsroad.player.SleepTimerState
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #351: the scrubber was read as a bare "37 percent", and a TalkBack swipe moved it by Compose's
 * default 5% of the range — two minutes of a forty-minute chapter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SeekSliderSemanticsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `the slider has a name and says the position in time`() {
        renderPlayer()

        compose.onNodeWithContentDescription("Position")
            .assert(hasStateDescription("12 minutes 3 seconds of 41 minutes"))
    }

    @Test
    fun `one accessibility step is one skip interval`() {
        renderPlayer()

        val config = compose.onNodeWithContentDescription("Position").fetchSemanticsNode().config
        val range = config[SemanticsProperties.ProgressBarRangeInfo]
        val increment = (range.range.endInclusive - range.range.start) / (range.steps + 1)
        assertEquals(30_000f, increment, 1f)
    }

    @Test
    fun `custom actions skip by the configured interval`() {
        var back = 0
        var forward = 0
        renderPlayer(onSkipBack = { back++ }, onSkipForward = { forward++ })

        val actions = compose.onNodeWithContentDescription("Position").fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        assertEquals(listOf("Back 30s", "Forward 30s"), actions.map { it.label })
        compose.runOnIdle {
            actions[0].action()
            actions[1].action()
            actions[1].action()
        }
        assertEquals(1, back)
        assertEquals(2, forward)
    }

    @Test
    fun `spoken durations read as words`() {
        assertEquals("0 seconds", spokenDuration(0L))
        assertEquals("1 minute", spokenDuration(60_000L))
        assertEquals("1 hour 2 minutes 1 second", spokenDuration(3_721_000L))
    }

    private fun renderPlayer(onSkipBack: () -> Unit = {}, onSkipForward: () -> Unit = {}) {
        compose.setContent {
            TtsRoadTheme {
                PlayerScreenBody(
                    playerState = PlayerUiState(
                        title = "Chapter 1",
                        fictionTitle = "A Serial",
                        isPlaying = true,
                        hasMedia = true,
                        positionMs = 723_000L,
                        durationMs = 2_460_000L,
                    ),
                    skipIntervalMs = 30_000L,
                    sleepTimerState = SleepTimerState(),
                    actionFeedback = null,
                    canRead = false,
                    canBookmark = false,
                    canReportPronunciation = false,
                    canJumpBack = false,
                    canOpenQueue = false,
                    onRetry = {},
                    onSeek = {},
                    onPreviousChapter = {},
                    onSkipBack = onSkipBack,
                    onTogglePlayPause = {},
                    onSkipForward = onSkipForward,
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
