package dk.perspektiva.ttsroad

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.nav.AppRoot
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The stable chrome and compact-height coexistence introduced by #161. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h360dp")
class PrimaryNavigationLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `all four roots fit on a narrow phone and report the selected one`() {
        var selected: AppRoot? = null
        compose.setContent {
            TtsRoadTheme {
                PrimaryNavigationBar(
                    selected = AppRoot.Browse,
                    onSelect = { selected = it },
                )
            }
        }

        for (label in listOf("HOME", "BROWSE", "LISTENING", "SETTINGS")) {
            compose.onNodeWithText(label).assertIsDisplayed()
        }
        compose.onNodeWithText("BROWSE").assertIsSelected()
        compose.onNodeWithText("LISTENING").performClick()
        assertEquals(AppRoot.Listening, selected)
    }

    @Test
    fun `the mini player sits above navigation at compact height`() {
        compose.setContent {
            TtsRoadTheme {
                AppBottomBar(
                    selected = AppRoot.Home,
                    onSelect = {},
                    miniPlayer = {
                        Box(modifier = Modifier.fillMaxWidth().height(48.dp)) {
                            Text("MINI PLAYER")
                        }
                    },
                )
            }
        }

        val mini = compose.onNodeWithText("MINI PLAYER").fetchSemanticsNode().boundsInRoot
        val home = compose.onNodeWithText("HOME").fetchSemanticsNode().boundsInRoot
        assertTrue("mini player must be above primary navigation", mini.bottom <= home.top)
        compose.onNodeWithText("SETTINGS").assertIsDisplayed()
    }

    @Test
    fun `the top bar has stable back and title chrome only`() {
        var backs = 0
        compose.setContent {
            TtsRoadTheme {
                AppTopBar(title = "Ashes", canGoBack = true, onBack = { backs++ })
            }
        }

        compose.onNodeWithText("ASHES").assertIsDisplayed()
        compose.onNodeWithText("BACK").performClick()
        assertEquals(1, backs)
        compose.onNodeWithText("PLAYER").assertDoesNotExist()
        compose.onNodeWithText("SETTINGS").assertDoesNotExist()
    }

    @Test
    fun `the listening hub keeps local stats but hides unsupported server destinations`() {
        compose.setContent {
            TtsRoadTheme {
                ListeningScreenBody(
                    hasMedia = false,
                    hasHistory = false,
                    canOpenQueue = false,
                    canOpenBookmarks = false,
                    canOpenPronunciationReports = false,
                    canOpenLogs = false,
                )
            }
        }

        compose.onNodeWithText("Player").assertExists().assertIsNotEnabled()
        compose.onNodeWithText("Listening stats").assertExists()
        compose.onNodeWithText("Up next").assertDoesNotExist()
        compose.onNodeWithText("Bookmarks").assertDoesNotExist()
        compose.onNodeWithText("Pronunciation reports").assertDoesNotExist()
        compose.onNodeWithText("Server log").assertDoesNotExist()
        compose.onNodeWithText("New chapters").assertDoesNotExist()
    }

    @Test
    fun `all five content destinations moved from settings live in listening`() {
        compose.setContent {
            TtsRoadTheme {
                ListeningScreenBody(
                    hasMedia = true,
                    hasHistory = true,
                    canOpenQueue = true,
                    canOpenBookmarks = true,
                    canOpenPronunciationReports = true,
                    canOpenLogs = true,
                )
            }
        }

        for (destination in listOf(
            "Up next",
            "Bookmarks",
            "Listening stats",
            "Pronunciation reports",
            "Server log",
        )) {
            compose.onNodeWithText(destination).assertExists()
        }
    }

    @Test
    fun `new chapters appears only where the server can report them, and carries its count`() {
        compose.setContent {
            TtsRoadTheme {
                ListeningScreenBody(
                    hasMedia = false,
                    hasHistory = false,
                    canOpenQueue = false,
                    canOpenBookmarks = false,
                    canOpenPronunciationReports = false,
                    canOpenLogs = false,
                    canOpenNewChapters = true,
                    unreadNewChapters = 3,
                )
            }
        }

        // Counted including chapters still converting: the whole point is that a chapter you were
        // told about stays counted until it can actually be played.
        compose.onNodeWithText("New chapters (3)").assertExists()
    }

    @Test
    fun `new chapters wears no count when there is nothing waiting`() {
        compose.setContent {
            TtsRoadTheme {
                ListeningScreenBody(
                    hasMedia = false,
                    hasHistory = false,
                    canOpenQueue = false,
                    canOpenBookmarks = false,
                    canOpenPronunciationReports = false,
                    canOpenLogs = false,
                    canOpenNewChapters = true,
                    unreadNewChapters = 0,
                )
            }
        }

        compose.onNodeWithText("New chapters").assertExists()
        compose.onNodeWithText("New chapters (0)").assertDoesNotExist()
    }

    /**
     * The bar has to be a bar.
     *
     * It shipped in 0.14.0 measuring the full height of the window. Each tab was a `Column` with
     * `Spacer(Modifier.weight(1f))` above and below its label and only a `heightIn(min = 56.dp)`
     * to bound it — and a Column with a weighted child takes its *maximum* height constraint,
     * which in a Scaffold's `bottomBar` slot is the whole screen. The body's bottom inset then
     * became the height of the window and every destination was laid out off-screen, so the app
     * was four giant labels that appeared to do nothing when tapped.
     *
     * Every other test in this file passed throughout: they assert that labels are displayed and
     * that clicks arrive, and all of that is just as true of a bar that is 360 dp tall. Nothing
     * measured it, so nothing caught it.
     */
    @Test
    fun `the navigation bar is a bar, not the whole screen`() {
        compose.setContent {
            TtsRoadTheme {
                AppBottomBar(selected = AppRoot.Home, onSelect = {}, miniPlayer = null)
            }
        }

        // The window is 360 dp tall in this class's qualifiers, and at mdpi that is 360 px.
        val barPx = compose.onRoot().fetchSemanticsNode().size.height
        assertTrue("navigation bar is ${barPx}px tall on a 360px screen", barPx < 180)
    }

    /**
     * The other half of the same claim: a bar that collapses is as broken as one that fills the
     * screen, and a bound with no floor would be satisfied by zero.
     */
    @Test
    fun `the navigation bar still clears a 48 dp touch target`() {
        compose.setContent {
            TtsRoadTheme {
                AppBottomBar(selected = AppRoot.Home, onSelect = {}, miniPlayer = null)
            }
        }

        compose.onNodeWithText("HOME").assertHeightIsAtLeast(48.dp)
    }
}
