package dk.perspektiva.ttsroad

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dk.perspektiva.ttsroad.core.ServiceLocator
import dk.perspektiva.ttsroad.download.OfflineDownloads
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.SessionStore
import dk.perspektiva.ttsroad.data.LoginResponse
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import dk.perspektiva.ttsroad.nav.AppScreen
import dk.perspektiva.ttsroad.nav.SettingsCategory
import dk.perspektiva.ttsroad.nav.navigateTo
import dk.perspektiva.ttsroad.nav.popScreen
import dk.perspektiva.ttsroad.nav.saveKey
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp", application = Application::class)
class SettingsNavigationTest {
    @get:Rule val compose = createComposeRule()

    /**
     * Rendering the Storage category builds the process-wide [OfflineDownloads], whose init
     * coroutines open the Media3 download index and register a broadcast receiver. Left running,
     * that lands after this class's Robolectric sandbox is gone and throws on a thread nobody is
     * awaiting — reported against whichever test starts next, not this one (#248).
     */
    @After
    fun stopBackgroundDownloads() {
        ServiceLocator.resetOfflineDownloadsForTest()
    }

    @Test
    fun `all category rows are reachable and accessible on a narrow phone`() {
        var selected: SettingsCategory? = null
        compose.setContent { TtsRoadTheme { SettingsRootScreen { selected = it } } }
        for (category in SettingsCategory.entries) {
            compose.onNodeWithText("${category.title} ›").performScrollTo()
                .assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
                .performClick()
            assertEquals(category, selected)
        }
        compose.onNodeWithText("SIGN OUT").assertDoesNotExist()
        compose.onNodeWithText("DELETE ALL DOWNLOADS").assertDoesNotExist()
    }

    @Test
    fun `top bar and system back return from category through settings`() {
        lateinit var dispatcher: OnBackPressedDispatcher
        compose.setContent {
            var stack by remember { mutableStateOf(listOf<AppScreen>(AppScreen.Library, AppScreen.Settings)) }
            val screen = stack.last()
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            val back = { stack = stack.popScreen() }
            BackHandler(enabled = stack.size > 1, onBack = back)
            TtsRoadTheme {
                Column {
                    AppTopBar(
                        title = (screen as? AppScreen.SettingsDetail)?.category?.title ?: screen.saveKey,
                        canGoBack = stack.size > 1,
                        onBack = back,
                    )
                    when (screen) {
                        AppScreen.Settings -> SettingsRootScreen {
                            stack = stack.navigateTo(AppScreen.SettingsDetail(it))
                        }
                        AppScreen.Library -> Text("Previous screen")
                        else -> Text("Category controls")
                    }
                }
            }
        }
        compose.onNodeWithText("Profile ›").performClick()
        compose.onNodeWithText("PROFILE").assertIsDisplayed()
        compose.onNodeWithText("BACK").performClick()
        compose.onNodeWithText("Profile ›").assertIsDisplayed().performClick()
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.onNodeWithText("Profile ›").assertIsDisplayed()
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.onNodeWithText("Previous screen").assertIsDisplayed()
    }

    @Test
    fun `profile saved state survives a device sessions round trip`() {
        compose.setContent {
            var screen by remember { mutableStateOf<AppScreen>(AppScreen.SettingsDetail(SettingsCategory.Profile)) }
            val holder = rememberSaveableStateHolder()
            TtsRoadTheme {
                holder.SaveableStateProvider(screen.saveKey) {
                    if (screen is AppScreen.SettingsDetail) {
                        var value by rememberSaveable { mutableStateOf(0) }
                        Column {
                            TextButton(onClick = { value++ }) { Text("Value $value") }
                            TextButton(onClick = { screen = AppScreen.Devices }) { Text("Devices") }
                        }
                    } else {
                        TextButton(onClick = { screen = AppScreen.SettingsDetail(SettingsCategory.Profile) }) {
                            Text("Return to profile")
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Value 0").performClick()
        compose.onNodeWithText("Devices").performClick()
        compose.onNodeWithText("Return to profile").performClick()
        compose.onNodeWithText("Value 1").assertIsDisplayed()
    }

    private fun show(
        category: SettingsCategory,
        repository: TtsRoadRepository = ServiceLocator.repository(ApplicationProvider.getApplicationContext<Application>()),
        session: SessionState = SessionState(username = "Listener"),
    ) {
        compose.setContent {
            TtsRoadTheme {
                SettingsScreen(
                    padding = PaddingValues(),
                    session = session,
                    repository = repository,
                    category = category,
                    onOpenDevices = {},
                )
            }
        }
    }

    private fun controls(vararg labels: String) {
        for (label in labels) compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `profile retains session and sign out without unrelated controls`() {
        show(SettingsCategory.Profile)
        controls("Listener", "DEVICE SESSIONS", "SIGN OUT")
        compose.onNodeWithText("CHECK FOR UPDATES").assertDoesNotExist()
        compose.onNodeWithText("ACCOUNT SECURITY").assertDoesNotExist()
    }

    @Test
    fun `playback retains every baseline preference`() {
        show(SettingsCategory.Playback)
        controls("SKIP INTERVAL", "PLAYBACK SPEED", "SLEEP TIMER DEFAULT",
            "MARK CHAPTERS PLAYED AUTOMATICALLY", "SKIP SILENCE", "VOLUME BOOST")
        compose.onNodeWithText("SIGN OUT").assertDoesNotExist()
    }

    @Test
    fun `storage retains download cache and destructive controls`() {
        show(SettingsCategory.Storage)
        controls("DOWNLOAD ON WI-FI ONLY", "KEEP CHAPTERS AHEAD", "KEEP STREAMED AUDIO",
            "STORAGE USED", "CLEAR STREAMED AUDIO", "DELETE ALL DOWNLOADS")
    }

    @Test
    fun `notifications always offers the system settings action`() {
        show(SettingsCategory.Notifications)
        controls("OPEN NOTIFICATION SETTINGS")
    }

    @Test
    fun `about retains updates and server information`() {
        show(SettingsCategory.About)
        controls("CHECK FOR UPDATES")
        compose.onNodeWithText("SIGN OUT").assertDoesNotExist()
    }

    @Test
    fun `capable library retains feeds backups shelf and admin exports`() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(if (request.path == "/api/mobile/capabilities") {
                        """{"capabilities":{"feed_urls":true,"listening_state_backup":true,"bulk_unfollow":true,"audiobook_export":true}}"""
                    } else {
                        "{}"
                    })
            }
            server.start()
            val session = SessionState(serverUrl = server.url("/").toString(), token = "test", isAdmin = true)
            val repository = TtsRoadRepository(object : SessionStore {
                override suspend fun current() = session
                override suspend fun saveLogin(baseUrl: String, response: LoginResponse) = Unit
                override suspend fun clearToken() = Unit
            })
            runBlocking { repository.refreshCurrentCapabilities() }
            show(SettingsCategory.Library, repository, session)
            controls("REGENERATE LINKS", "SAVE A COPY", "RESTORE", "EMPTY MY SHELF")
            compose.onNodeWithText("AUDIOBOOK EXPORTS").assertExists()
        }
    }

    @Test
    fun `unsupported library tools show an explanation not unsafe actions`() {
        show(SettingsCategory.Library)
        controls("THIS SERVER DOES NOT OFFER LIBRARY SHARING OR BACKUP TOOLS.")
        for (label in listOf("REGENERATE LINKS", "SAVE A COPY", "RESTORE", "EMPTY MY SHELF")) {
            compose.onNodeWithText(label).assertDoesNotExist()
        }
    }
}
