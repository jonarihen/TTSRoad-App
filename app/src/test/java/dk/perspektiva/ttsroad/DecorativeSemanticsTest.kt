package dk.perspektiva.ttsroad

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dk.perspektiva.ttsroad.player.PlayerUiState
import dk.perspektiva.ttsroad.player.SleepTimerState
import dk.perspektiva.ttsroad.ui.AarisColor
import dk.perspektiva.ttsroad.ui.MetaText
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import dk.perspektiva.ttsroad.ui.spokenMetaText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #355: TalkBack read a stray capital before every cover (the fallback initial drawn behind the
 * artwork) and "slash slash" before every section kicker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class DecorativeSemanticsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `a kicker is spoken without its slashes`() {
        compose.setContent {
            TtsRoadTheme { MetaText(text = "// Now playing", color = AarisColor.Accent) }
        }

        compose.onNodeWithText("NOW PLAYING", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("// NOW PLAYING", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("//", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `ordinary meta text is left alone`() {
        compose.setContent {
            TtsRoadTheme { MetaText(text = "12:03 left") }
        }

        compose.onNodeWithText("12:03 LEFT", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `spoken meta text drops only a leading kicker mark`() {
        assertEquals("NOW PLAYING", spokenMetaText("// Now playing"))
        assertEquals("A/B // C", spokenMetaText("a/b // c"))
    }

    @Test
    fun `the cover's fallback initial is hidden from accessibility services`() {
        compose.setContent {
            TtsRoadTheme {
                PlayerScreenBody(
                    playerState = PlayerUiState(
                        title = "Chapter 1",
                        fictionTitle = "Quiet Harbour",
                        hasMedia = true,
                        durationMs = 60_000L,
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

        compose.onNodeWithText("Chapter 1", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Q", useUnmergedTree = true).assertDoesNotExist()
    }
}
