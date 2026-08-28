package dk.perspektiva.ttsroad

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
}
