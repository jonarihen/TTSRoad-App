package dk.perspektiva.ttsroad

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dk.perspektiva.ttsroad.core.ServiceLocator
import dk.perspektiva.ttsroad.data.BrowseScope
import dk.perspektiva.ttsroad.data.LoginResponse
import dk.perspektiva.ttsroad.data.MobileUser
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.SessionStore
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp", application = Application::class)
class SettingsNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun keepLayoutTestsOutOfMedia3() {
        ServiceLocator.disableDownloadManagerForTest()
    }

    @After
    fun restoreRealDownloadConstruction() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        compose.runOnIdle { ServiceLocator.libraryCache(context).clear() }
        runBlocking {
            ServiceLocator.tokenStore(context).clearAll()
            ServiceLocator.repository(context).refreshCurrentCapabilities()
        }
        ServiceLocator.restoreDownloadManagerAfterTest()
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

    private fun emptyShelfRoundTrip(
        removed: Int = 1,
        failureCode: Int? = null,
        unsupported: Boolean = false,
        deltaSync: Boolean = true,
    ) {
        MockWebServer().use { server ->
            val following = AtomicBoolean(true)
            val bulkUnfollow = AtomicBoolean(true)
            val requests = CopyOnWriteArrayList<RecordedRequest>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val url = request.requestUrl!!
                    val body = when (url.encodedPath) {
                        "/api/mobile/capabilities" ->
                            """{"capabilities":{"follows":true,"bulk_unfollow":${bulkUnfollow.get()},"delta_sync":$deltaSync}}"""
                        "/api/mobile/library/follows" -> {
                            if (failureCode != null) return MockResponse().setResponseCode(failureCode)
                            following.set(false)
                            """{"status":"ok","removed":$removed,"following_ids":[]}"""
                        }
                        "/api/mobile/library" -> {
                            val all = url.queryParameter("scope") == "all"
                            val followed = following.get()
                            val fictions = if (all || followed) {
                                """{"id":7,"title":"Cached book","following":$followed}"""
                            } else {
                                ""
                            }
                            val chapters = if (all || followed) {
                                """{"id":70,"fiction_id":7,"title":"Cached chapter"}"""
                            } else {
                                ""
                            }
                            """{"scope":"${if (all) "all" else "followed"}","following_ids":${if (followed) "[7]" else "[]"},"fictions":[$fictions],"continue_listening":[$chapters],"recent_chapters":[$chapters],"server_time":"${if (followed) "t1" else "t2"}"}"""
                        }
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            server.start()
            val context = ApplicationProvider.getApplicationContext<Application>()
            runBlocking {
                ServiceLocator.tokenStore(context).saveLogin(
                    server.url("/").toString(),
                    LoginResponse(token = "test", user = MobileUser(id = 1, username = "Listener")),
                )
                ServiceLocator.browsePreferences(context).setScope(BrowseScope.All)
            }
            val repository = ServiceLocator.repository(context)
            val cache = ServiceLocator.libraryCache(context)
            val session = runBlocking { ServiceLocator.tokenStore(context).current() }
            runBlocking { repository.refreshCurrentCapabilities() }
            compose.runOnIdle { cache.clear() }
            compose.setContent {
                var stack by remember { mutableStateOf(listOf<AppScreen>(AppScreen.Library)) }
                val screen = stack.last()
                val holder = rememberSaveableStateHolder()
                TtsRoadTheme {
                    Column(Modifier.fillMaxSize()) {
                        Row {
                            TextButton(onClick = { stack = stack.navigateTo(AppScreen.Library) }) { Text("Home tab") }
                            TextButton(onClick = { stack = stack.navigateTo(AppScreen.Fictions) }) { Text("Browse tab") }
                            TextButton(onClick = { stack = stack.navigateTo(AppScreen.SettingsDetail(SettingsCategory.Library)) }) {
                                Text("Shelf settings")
                            }
                            TextButton(onClick = { stack = stack.popScreen() }) { Text("Go back") }
                        }
                        Box(Modifier.weight(1f)) {
                            holder.SaveableStateProvider(screen.saveKey) {
                                when (screen) {
                                    AppScreen.Library -> LibraryScreen(
                                        padding = PaddingValues(),
                                        playbackController = ServiceLocator.playbackController(context),
                                        onOpenFiction = {},
                                        onOpenPlayer = {},
                                        onBrowseFictions = {},
                                    )
                                    AppScreen.Fictions -> FictionsScreen(
                                        padding = PaddingValues(),
                                        repository = repository,
                                        onOpenFiction = {},
                                        onOpenReader = {},
                                    )
                                    else -> SettingsScreen(
                                        padding = PaddingValues(),
                                        session = session,
                                        repository = repository,
                                        category = SettingsCategory.Library,
                                        onOpenDevices = {},
                                    )
                                }
                            }
                        }
                    }
                }
            }
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                !cache.library.value.isInitialLoad && !cache.library.value.isRefreshing
            }
            assertNull("Library failed: ${cache.library.value}; requests: ${requests.map { it.path }}", cache.library.value.error)
            val originalShelf = cache.library.value.value!!
            assertEquals(listOf(7), originalShelf.fictions.map { it.id })
            compose.onNodeWithText("Browse tab").performClick()
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                !cache.browseAll.value.isInitialLoad && !cache.browseAll.value.isRefreshing
            }
            assertNull(cache.browseAll.value.error)
            compose.onNodeWithText("FOLLOWING  1").assertIsDisplayed()
            val originalBrowse = cache.browseAll.value.value!!
            compose.onNodeWithText("Home tab").performClick()
            compose.onNodeWithText("Shelf settings").performClick()
            compose.onNodeWithText("EMPTY MY SHELF").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("EMPTY SHELF")).fetchSemanticsNodes().isNotEmpty()
            }
            if (unsupported) {
                bulkUnfollow.set(false)
                runBlocking { repository.refreshCurrentCapabilities(forceRefresh = true) }
            }
            compose.onNodeWithText("EMPTY SHELF").performClick()
            val note = when {
                unsupported -> "THIS SERVER CANNOT EMPTY A SHELF IN ONE CALL."
                failureCode != null -> "COULD NOT EMPTY YOUR SHELF:"
                removed == 0 -> "YOUR SHELF WAS ALREADY EMPTY."
                else -> "ONE BOOK REMOVED FROM YOUR SHELF."
            }
            compose.waitUntil(10_000) {
                if (unsupported) {
                    compose.onAllNodes(hasText("EMPTY SHELF")).fetchSemanticsNodes().isEmpty()
                } else {
                    compose.onAllNodes(hasText(note, substring = true)).fetchSemanticsNodes().isNotEmpty()
                }
            }
            val succeeded = failureCode == null && !unsupported
            if (succeeded) {
                assertNull(cache.library.value.value)
                assertNull(cache.browseAll.value.value)
            } else {
                assertSame(originalShelf, cache.library.value.value)
                assertSame(originalBrowse, cache.browseAll.value.value)
            }
            val beforeReturn = requests.size
            compose.onNodeWithText("Go back").performClick()
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                !cache.library.value.isInitialLoad && !cache.library.value.isRefreshing
            }
            assertNull("Library failed: ${cache.library.value}; requests: ${requests.map { it.path }}", cache.library.value.error)
            if (succeeded) {
                compose.onNodeWithText("NO FICTIONS FOUND").assertExists()
                compose.onNodeWithText("Cached book").assertDoesNotExist()
                val shelf = cache.library.value.value!!
                assertTrue(shelf.fictions.isEmpty())
                assertTrue(shelf.followingIds.isEmpty())
                assertTrue(shelf.continueListening.isEmpty())
                assertTrue(shelf.recentChapters.isEmpty())
            } else {
                assertSame(originalShelf, cache.library.value.value)
            }
            compose.onNodeWithText("Browse tab").performClick()
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                !cache.browseAll.value.isInitialLoad && !cache.browseAll.value.isRefreshing
            }
            assertNull(cache.browseAll.value.error)
            compose.onNodeWithText("FOLLOWING  ${if (succeeded) 0 else 1}").assertIsDisplayed()
            val browse = cache.browseAll.value.value!!
            assertEquals(listOf("Cached book"), browse.fictions.map { it.title })
            if (succeeded) {
                assertFalse(browse.fictions.single().following)
                assertTrue(browse.followingIds.isEmpty())
                val reloads = requests.drop(beforeReturn)
                assertEquals(listOf("followed", "all"), reloads.map { it.requestUrl!!.queryParameter("scope") })
                reloads.forEach { assertNull(it.requestUrl!!.queryParameter("updated_since")) }
                compose.onNodeWithText("FOLLOWING  0").performClick()
                compose.waitUntil(10_000) {
                    compose.onAllNodes(hasText("Cached book")).fetchSemanticsNodes().isEmpty()
                }
            } else {
                assertSame(originalBrowse, browse)
                assertEquals(beforeReturn, requests.size)
            }
            assertEquals(if (unsupported) 0 else 1, requests.count { it.method == "DELETE" })
            compose.runOnIdle { cache.clear() }
            runBlocking { ServiceLocator.tokenStore(context).clearAll() }
        }
    }

    @Test
    fun `emptying a cached shelf reloads Home and Browse on return with delta sync`() {
        emptyShelfRoundTrip()
    }

    @Test
    fun `emptying a cached shelf reloads Home and Browse without delta sync`() {
        emptyShelfRoundTrip(deltaSync = false)
    }

    @Test
    fun `already empty success still invalidates a stale cached shelf`() {
        emptyShelfRoundTrip(removed = 0)
    }

    @Test
    fun `failed empty shelf preserves Home and Browse caches on return`() {
        emptyShelfRoundTrip(failureCode = 500)
    }

    @Test
    fun `unsupported empty shelf preserves Home and Browse caches on return`() {
        emptyShelfRoundTrip(unsupported = true)
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
