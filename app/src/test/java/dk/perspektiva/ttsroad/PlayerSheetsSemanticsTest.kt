package dk.perspektiva.ttsroad

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dk.perspektiva.ttsroad.player.PlayerUiState
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
 * TalkBack semantics of the player's toolbar toggle and its sheets: #358 (roles and state), #353
 * (the per-book switch and the "fell asleep at" field had no names) and #352 (errors that appear
 * in answer to a press are announced).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PlayerSheetsSemanticsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `skip silence is a named switch whose state is the toggle state, not the label`() {
        var skipSilence = false
        compose.setContent {
            TtsRoadTheme {
                PlayerScreenBody(
                    playerState = PlayerUiState(title = "Chapter 1", hasMedia = true),
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
                    skipSilence = skipSilence,
                    onToggleSkipSilence = { skipSilence = !skipSilence },
                )
            }
        }

        val toggle = compose.onNodeWithContentDescription("Skip silence")
        assertEquals(Role.Switch, toggle.role())
        toggle.assertIsOff()
        toggle.performClick()
        compose.waitForIdle()
        assertTrue(skipSilence)
    }

    @Test
    fun `the per-book speed switch is one toggleable row that carries its label`() {
        var pinned: Boolean? = null
        compose.setContent {
            TtsRoadTheme {
                PerBookSpeedSwitch(isPinned = false, speedLabel = "1.5×", onPinnedChange = { pinned = it })
            }
        }

        val row = compose.onNodeWithText("ONLY FOR THIS BOOK", substring = true)
        assertEquals(Role.Switch, row.role())
        row.assertIsOff()
        row.performClick()
        compose.waitForIdle()
        assertEquals(true, pinned)
    }

    @Test
    fun `a pinned book reads as on`() {
        compose.setContent {
            TtsRoadTheme {
                PerBookSpeedSwitch(isPinned = true, speedLabel = "1.5×", onPinnedChange = {})
            }
        }

        compose.onNodeWithText("ONLY FOR THIS BOOK", substring = true).assertIsOn()
    }

    @Test
    fun `speed presets are radio buttons that say which one is current`() {
        compose.setContent {
            TtsRoadTheme {
                Column {
                    SpeedOptionRow(label = "1.0×", selected = false, onClick = {})
                    SpeedOptionRow(label = "1.5×", selected = true, onClick = {})
                }
            }
        }

        val current = compose.onNodeWithText("1.5×", substring = true)
        assertEquals(Role.RadioButton, current.role())
        current.assertIsSelected()
        compose.onNodeWithText("1.0×").assertIsNotSelected()
    }

    @Test
    fun `fine speed buttons step by a twentieth and are 48 dp named targets`() {
        var speed by mutableStateOf(2.0f)
        compose.setContent {
            TtsRoadTheme {
                FineSpeedStepper(
                    speed = speed,
                    speedLabel = "${speed}x",
                    onSpeedChange = { speed = it },
                )
            }
        }

        val up = compose.onNodeWithTag("speed_step_up")
        up.assertContentDescriptionEquals("Faster")
        up.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        repeat(4) {
            up.performClick()
            compose.waitForIdle()
        }
        assertEquals(2.2f, speed, 0.0001f)
        compose.onNodeWithTag("speed_step_down").assertContentDescriptionEquals("Slower").performClick()
        compose.waitForIdle()
        assertEquals(2.15f, speed, 0.0001f)
    }

    @Test
    fun `fine speed buttons are disabled at the ends of the range`() {
        compose.setContent {
            TtsRoadTheme {
                Column {
                    FineSpeedStepper(speed = 3.0f, speedLabel = "3x", onSpeedChange = {})
                }
            }
        }

        compose.onNodeWithTag("speed_step_up").assertIsNotEnabled()
        compose.onNodeWithTag("speed_step_down").assertIsEnabled()
    }

    @Test
    fun `sleep timer rows are buttons that say what a tap does`() {
        compose.setContent {
            TtsRoadTheme { SleepTimerOption(label = "15 minutes") {} }
        }

        val row = compose.onNodeWithText("15 minutes")
        assertEquals(Role.Button, row.role())
        val onClick = row.fetchSemanticsNode().config[SemanticsActions.OnClick]
        assertEquals("Start sleep timer", onClick.label)
    }

    @Test
    fun `the mini player's titles say they open the player`() {
        var opened = false
        compose.setContent {
            TtsRoadTheme {
                MiniPlayerTitle(
                    state = PlayerUiState(title = "Chapter 7", fictionTitle = "A Serial", hasMedia = true),
                    onExpand = { opened = true },
                )
            }
        }

        val title = compose.onNodeWithText("Chapter 7", substring = true)
        assertEquals(Role.Button, title.role())
        assertEquals("Open player", title.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        title.performClick()
        compose.waitForIdle()
        assertTrue(opened)
    }

    @Test
    fun `the fell-asleep-at field has a label rather than only a placeholder`() {
        compose.setContent {
            TtsRoadTheme {
                Column {
                    JumpBackSleepTimeField(value = "2349", onValueChange = {}, error = null, onJump = {})
                }
            }
        }

        // With text in the field the placeholder is gone, so the label is what is left to name it.
        val field = compose.onNode(hasSetTextAction())
        val text = field.fetchSemanticsNode().config[SemanticsProperties.Text].joinToString(" ") { it.text }
        assertTrue("field reads \"$text\"", text.contains("FELL ASLEEP AT"))
    }

    @Test
    fun `a sleep-time error is a polite live region`() {
        compose.setContent {
            TtsRoadTheme {
                Column {
                    JumpBackSleepTimeField(
                        value = "99",
                        onValueChange = {},
                        error = "Use 24h time, e.g. 2349 or 23:49",
                        onJump = {},
                    )
                }
            }
        }

        assertEquals(LiveRegionMode.Polite, compose.onNodeWithText("USE 24H TIME", substring = true).liveRegion())
    }

    @Test
    fun `a failed jump is announced`() {
        compose.setContent {
            TtsRoadTheme { AnnouncedError(text = "Could not load that chapter") }
        }

        assertEquals(LiveRegionMode.Polite, compose.onNodeWithText("COULD NOT LOAD THAT CHAPTER").liveRegion())
    }

    @Test
    fun `a login failure is announced`() {
        compose.setContent {
            TtsRoadTheme { LoginNotice(text = "Invalid username or password") }
        }

        assertEquals(LiveRegionMode.Polite, compose.onNodeWithText("Invalid username or password").liveRegion())
    }

    private fun SemanticsNodeInteraction.role(): Role? =
        fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role)

    private fun SemanticsNodeInteraction.liveRegion(): LiveRegionMode? =
        fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion)
}
