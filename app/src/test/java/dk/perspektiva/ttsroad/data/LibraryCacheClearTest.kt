package dk.perspektiva.ttsroad.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #444: a refresh cancelled by sign-out must not put the previous account's shelf back. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryCacheClearTest {
    private val main = StandardTestDispatcher()
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        Dispatchers.resetMain()
    }

    @Test
    fun `clear during the snapshot write leaves the library empty`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"scope":"followed","following_ids":[7],"fictions":[{"id":7,"title":"Book"}],
                       "continue_listening":[],"recent_chapters":[],"server_time":"t1"}""",
                ),
        )
        val writing = CompletableDeferred<Unit>()
        val cache = LibraryCache(
            repository = TtsRoadRepository(
                FakeSessionStore(SessionState(serverUrl = server.url("/").toString(), token = "token")),
            ),
            snapshots = LibrarySnapshotStore(kotlin.io.path.createTempFile().toFile()),
            // The snapshot write is where sign-out lands: suspend there until clear() cancels it.
            accountKey = {
                writing.complete(Unit)
                CompletableDeferred<String>().await()
            },
        )

        cache.refreshLibrary()
        val deadline = System.currentTimeMillis() + 10_000
        while (!writing.isCompleted) {
            assertTrue("refresh never reached the snapshot write", System.currentTimeMillis() < deadline)
            main.scheduler.advanceUntilIdle()
            Thread.sleep(5)
        }

        cache.clear()
        main.scheduler.advanceUntilIdle()

        assertNull(cache.library.value.value)
    }
}
