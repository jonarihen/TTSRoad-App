package dk.perspektiva.ttsroad.data

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #327: a cold start with no network shows last time's library, marked as such, not an error page. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryCacheOfflineTest {
    private lateinit var server: MockWebServer
    private val dir = Files.createTempDirectory("library-cache-offline").toFile()
    private val snapshots = LibrarySnapshotStore(File(dir, LibrarySnapshotStore.FileName))

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        Dispatchers.resetMain()
        dir.deleteRecursively()
    }

    private fun cache(account: String? = "account-a") = LibraryCache(
        repository = TtsRoadRepository(
            FakeSessionStore(SessionState(serverUrl = server.url("/").toString(), token = "token")),
        ),
        snapshots = snapshots,
        accountKey = { account },
    )

    private fun LibraryCache.refreshAndSettle(): Cached<LibraryResponse> {
        refreshLibrary()
        return runBlocking { withTimeout(10_000) { library.first { !it.isRefreshing } } }
    }

    private fun libraryJson() = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"scope":"followed","following_ids":[7],"fictions":[{"id":7,"title":"Book"}],
               "continue_listening":[],"recent_chapters":[],"server_time":"t1"}""",
        )

    @Test
    fun `a library that loaded is saved, and a later cold start offline shows it with a notice`() {
        server.enqueue(libraryJson())
        val online = cache().refreshAndSettle()
        assertNull(online.error)
        assertNotNull(snapshots.read("account-a"))

        val offlineCache = cache()
        server.shutdown()
        val offline = offlineCache.refreshAndSettle()

        assertEquals(listOf(7), offline.value?.followingIds)
        assertTrue(offline.error.orEmpty().startsWith("Saved copy from "))
    }

    @Test
    fun `another account starting offline gets the error, not the saved library`() {
        server.enqueue(libraryJson())
        cache().refreshAndSettle()

        val offlineCache = cache(account = "account-b")
        server.shutdown()
        val offline = offlineCache.refreshAndSettle()

        assertNull(offline.value)
        assertNotNull(offline.error)
    }
}
