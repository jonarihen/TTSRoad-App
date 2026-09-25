package dk.perspektiva.ttsroad.player

import androidx.compose.ui.test.junit4.v2.createComposeRule
import dk.perspektiva.ttsroad.data.FakeSessionStore
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.data.parseSessionEnd
import dk.perspektiva.ttsroad.resolvedSession
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingProgressSignOutTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var server: MockWebServer
    private lateinit var file: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        file = File(RuntimeEnvironment.getApplication().filesDir, "pending_progress.json")
        if (file.exists()) file.delete()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun loggedInStore() = FakeSessionStore(
        SessionState(serverUrl = server.url("/").toString(), token = "stale-token", username = "admin"),
    )

    private fun pendingStore(clock: () -> Long = System::currentTimeMillis) =
        PendingProgressStore(RuntimeEnvironment.getApplication(), clock)

    @Test
    fun `cold launch keeps persisted progress until the session restores and syncs`() = runTest {
        pendingStore { 1_700_000_000_000L }.record(1, 7, 30.0, false)
        val sessions = MutableSharedFlow<SessionState>(replay = 1)
        var resolved: SessionState? = null
        var signedOut = 0
        compose.setContent {
            resolved = resolvedSession(sessions, onSignedIn = {}, onSignedOut = {
                signedOut++
                pendingStore().clear()
            })
        }
        compose.waitForIdle()
        assertNull(resolved)
        assertEquals(0, signedOut)
        assertEquals(1, pendingStore().pending().size)
        assertTrue(file.exists())

        val restored = loggedInStore()
        compose.runOnIdle {
            sessions.tryEmit(SessionState(serverUrl = server.url("/").toString(), token = "stale-token"))
        }
        compose.waitForIdle()
        assertTrue(resolved?.isLoggedIn == true)
        assertEquals(0, signedOut)
        assertEquals(1, pendingStore().pending().size)

        val repository = TtsRoadRepository(restored)
        server.enqueue(MockResponse().setBody("""{"capabilities":{"batch_progress":true},"limits":{}}"""))
        repository.refreshCurrentCapabilities()
        server.enqueue(MockResponse().setBody("""{"accepted":[{"chapter_id":7}],"rejected":[],"server_state":[]}"""))
        val result = ProgressSync(repository, pendingStore()).flush()

        server.takeRequest()
        assertEquals("/api/mobile/playback/sync", server.takeRequest().path)
        assertEquals(1, result.accepted)
        assertTrue(result.drained)
    }

    @Test
    fun `first real signed out session clears progress without a previous account`() {
        pendingStore().record(1, 7, 30.0, false)
        val sessions = MutableSharedFlow<SessionState>(replay = 1)
        var signedOut = 0
        compose.setContent {
            resolvedSession(sessions, onSignedIn = {}, onSignedOut = {
                signedOut++
                pendingStore().clear()
            })
        }
        compose.waitForIdle()
        assertEquals(0, signedOut)
        compose.runOnIdle { sessions.tryEmit(SessionState()) }
        compose.waitForIdle()

        assertEquals(1, signedOut)
        assertFalse(file.exists())
    }

    @Test
    fun `restored session ending stops playback and clears progress`() {
        pendingStore().record(1, 7, 30.0, false)
        val sessions = MutableSharedFlow<SessionState>(replay = 1)
        var signedIn = 0
        var signedOut = 0
        compose.setContent {
            resolvedSession(sessions, onSignedIn = { signedIn++ }, onSignedOut = {
                signedOut++
                pendingStore().clear()
            })
        }
        compose.runOnIdle { sessions.tryEmit(SessionState(serverUrl = server.url("/").toString(), token = "t")) }
        compose.waitForIdle()
        assertEquals(1, signedIn)
        assertTrue(file.exists())

        compose.runOnIdle { sessions.tryEmit(SessionState(serverUrl = server.url("/").toString())) }
        compose.waitForIdle()
        assertEquals(1, signedOut)
        assertFalse(file.exists())
    }

    @Test
    fun `logout clears the pending queue and its file`() = runTest {
        val store = pendingStore()
        store.record(fictionId = 1, chapterId = 7, positionSeconds = 30.0, isPlayed = false)
        val repository = TtsRoadRepository(loggedInStore(), onSessionCleared = store::clear)
        server.enqueue(MockResponse().setBody("""{"status":"ok","revoked":true}"""))

        repository.logout()

        assertTrue(store.isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun `endSession clears the pending queue and its file`() = runTest {
        val store = pendingStore()
        store.record(fictionId = 1, chapterId = 7, positionSeconds = 30.0, isPlayed = false)
        val repository = TtsRoadRepository(loggedInStore(), onSessionCleared = store::clear)

        repository.endSession(parseSessionEnd(null))

        assertTrue(store.isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun `nothing flushes under the next account after sign-out`() = runTest {
        val store = pendingStore()
        store.record(fictionId = 1, chapterId = 7, positionSeconds = 30.0, isPlayed = false)
        val sessionStore = loggedInStore()
        val repository = TtsRoadRepository(sessionStore, onSessionCleared = store::clear)
        server.enqueue(MockResponse().setBody("""{"status":"ok","revoked":true}"""))

        repository.logout()

        server.enqueue(
            MockResponse().setBody(
                """{"token":"fresh","token_type":"bearer","user":{"id":1,"username":"other"}}""",
            ),
        )
        repository.login(
            baseUrl = server.url("/").toString(),
            username = "other",
            password = "correct",
            deviceName = "Pixel",
        )
        val result = ProgressSync(repository, store).flush()

        assertTrue(result.drained)
        assertEquals(0, result.sent)
        assertEquals(2, server.requestCount)
    }
}
