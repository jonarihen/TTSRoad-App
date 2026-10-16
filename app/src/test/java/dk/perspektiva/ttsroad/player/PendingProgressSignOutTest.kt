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
import org.junit.Assert.assertNotNull
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

    private val owner get() = SessionState(serverUrl = server.url("/").toString(), token = "stale-token", username = "admin")

    @Test
    fun `cold launch keeps persisted progress until the session restores and syncs`() = runTest {
        pendingStore { 1_700_000_000_000L }.record(1, 7, 30.0, false, owner = owner)
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
    fun `first real signed out session is reported once`() {
        pendingStore().record(1, 7, 30.0, false, owner = owner)
        val sessions = MutableSharedFlow<SessionState>(replay = 1)
        var signedOut = 0
        compose.setContent {
            resolvedSession(sessions, onSignedIn = {}, onSignedOut = { signedOut++ })
        }
        compose.waitForIdle()
        assertEquals(0, signedOut)
        compose.runOnIdle { sessions.tryEmit(SessionState()) }
        compose.waitForIdle()

        assertEquals(1, signedOut)
        // Signed-out handling no longer owns the backlog; logout and login do (#311).
        assertTrue(file.exists())
    }

    @Test
    fun `restored session ending reports sign-out and keeps the owner-stamped backlog`() {
        pendingStore().record(1, 7, 30.0, false, owner = owner)
        val sessions = MutableSharedFlow<SessionState>(replay = 1)
        var signedIn = 0
        var signedOut = 0
        compose.setContent {
            resolvedSession(sessions, onSignedIn = { signedIn++ }, onSignedOut = { signedOut++ })
        }
        compose.runOnIdle { sessions.tryEmit(SessionState(serverUrl = server.url("/").toString(), token = "t")) }
        compose.waitForIdle()
        assertEquals(1, signedIn)
        assertTrue(file.exists())

        compose.runOnIdle { sessions.tryEmit(SessionState(serverUrl = server.url("/").toString())) }
        compose.waitForIdle()
        assertEquals(1, signedOut)
        assertEquals(1, pendingStore().pending().size)
    }

    @Test
    fun `logout clears the pending queue and its file`() = runTest {
        val store = pendingStore()
        store.record(fictionId = 1, chapterId = 7, positionSeconds = 30.0, isPlayed = false, owner = owner)
        val repository = TtsRoadRepository(loggedInStore(), onSessionCleared = store::clear)
        server.enqueue(MockResponse().setBody("""{"status":"ok","revoked":true}"""))

        repository.logout()

        assertTrue(store.isEmpty())
        assertFalse(file.exists())
    }

    private fun repositoryWith(store: PendingProgressStore, sessions: FakeSessionStore = loggedInStore()) =
        TtsRoadRepository(
            sessions,
            onSessionCleared = store::clear,
            onSignedIn = store::retainOwnedBy,
        )

    private fun loginResponse(username: String) = MockResponse().setBody(
        """{"token":"fresh","token_type":"bearer","user":{"id":1,"username":"$username"}}""",
    )

    private suspend fun TtsRoadRepository.loginAs(username: String) = login(
        baseUrl = server.url("/").toString(),
        username = username,
        password = "correct",
        deviceName = "Pixel",
    )

    @Test
    fun `endSession keeps the pending queue and its file`() = runTest {
        val store = pendingStore()
        store.record(fictionId = 1, chapterId = 7, positionSeconds = 30.0, isPlayed = false, owner = owner)
        val repository = repositoryWith(store)

        repository.endSession(parseSessionEnd(null))

        assertEquals(1, store.pending().size)
        assertTrue(file.exists())
    }

    @Test
    fun `a 401 followed by re-login to the same account flushes the queued progress`() = runTest {
        val store = pendingStore()
        store.record(fictionId = 1, chapterId = 7, positionSeconds = 30.0, isPlayed = false, owner = owner)
        val repository = repositoryWith(store)
        // The token expired on the flight: the first call after landing is refused.
        server.enqueue(MockResponse().setResponseCode(401))
        runCatching { repository.library() }
        assertNotNull(repository.sessionEnd.value)
        assertEquals(1, store.pending().size)

        server.enqueue(loginResponse("admin"))
        repository.loginAs("admin")
        server.enqueue(MockResponse().setBody("""{"capabilities":{"batch_progress":true},"limits":{}}"""))
        repository.refreshCurrentCapabilities()
        server.enqueue(MockResponse().setBody("""{"accepted":[{"chapter_id":7}],"rejected":[],"server_state":[]}"""))
        val result = ProgressSync(repository, store).flush()

        assertEquals(1, result.accepted)
        assertTrue(result.drained)
        repeat(3) { server.takeRequest() }
        val sync = server.takeRequest()
        assertEquals("/api/mobile/playback/sync", sync.path)
        assertEquals("Bearer fresh", sync.getHeader("Authorization"))
        assertTrue(store.isEmpty())
    }

    @Test
    fun `a 401 followed by login to a different account discards the queue`() = runTest {
        val store = pendingStore()
        store.record(fictionId = 1, chapterId = 7, positionSeconds = 30.0, isPlayed = false, owner = owner)
        val repository = repositoryWith(store)
        repository.endSession(parseSessionEnd(null))
        assertEquals(1, store.pending().size)

        server.enqueue(loginResponse("other"))
        repository.loginAs("other")

        assertTrue(store.isEmpty())
    }

    @Test
    fun `login to the same account on a different server discards the queue`() = runTest {
        val store = pendingStore()
        store.record(
            fictionId = 1,
            chapterId = 7,
            positionSeconds = 30.0,
            isPlayed = false,
            owner = owner.copy(serverUrl = "https://elsewhere.example/"),
        )
        val repository = repositoryWith(store)
        repository.endSession(parseSessionEnd(null))

        server.enqueue(loginResponse("admin"))
        repository.loginAs("admin")

        assertTrue(store.isEmpty())
    }

    @Test
    fun `nothing flushes under the next account after sign-out`() = runTest {
        val store = pendingStore()
        store.record(fictionId = 1, chapterId = 7, positionSeconds = 30.0, isPlayed = false, owner = owner)
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
