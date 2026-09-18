package dk.perspektiva.ttsroad.player

import dk.perspektiva.ttsroad.data.FakeSessionStore
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.data.parseSessionEnd
import java.io.File
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingProgressSignOutTest {
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
