package dk.perspektiva.ttsroad.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException

class EbookExportRepositoryTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private suspend fun loggedInStore() = FakeSessionStore(SessionState()).also { store ->
        store.saveLogin(
            server.url("/").toString(),
            LoginResponse(token = "secret", user = MobileUser(id = 1, username = "reader")),
        )
    }

    private suspend fun repository(enabled: Boolean, store: FakeSessionStore? = null): TtsRoadRepository {
        val repository = TtsRoadRepository(store ?: loggedInStore())
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"capabilities":{"ebook_export":$enabled}}""",
            ),
        )
        repository.refreshCurrentCapabilities()
        return repository
    }

    @Test
    fun `export streams authenticated epub from non-mobile path`() = runTest {
        val repository = repository(true)
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/epub+zip")
                .setHeader("Content-Disposition", "attachment; filename=\"server-book.epub\"")
                .setBody("epub bytes"),
        )

        val result = repository.exportEbook(42) as EbookExportResult.Ready

        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("/api/fictions/42/export.epub", request.path)
        assertEquals("Bearer secret", request.getHeader("Authorization"))
        assertEquals("server-book.epub", result.filename)
        result.body.use { assertEquals("epub bytes", it.string()) }
    }

    @Test
    fun `unsupported export does not call server`() = runTest {
        val repository = repository(false)

        assertEquals(EbookExportResult.Unsupported, repository.exportEbook(42))
        server.takeRequest()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `export 401 ends the rejected session with the server reason`() = runTest {
        val store = loggedInStore()
        val repository = repository(true, store)
        server.enqueue(
            MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
                .setBody("""{"detail":{"message":"This device session expired. Sign in again.","reason":"token_expired"}}"""),
        )

        val thrown = runCatching { repository.exportEbook(42) }.exceptionOrNull()

        assertEquals(401, (thrown as HttpException).code())
        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("/api/fictions/42/export.epub", request.path)
        assertEquals("Bearer secret", request.getHeader("Authorization"))
        assertEquals(2, server.requestCount)
        assertEquals(1, store.clearTokenCalls)
        assertNull(store.current().token)
        assertEquals(SessionEndReason.Expired, repository.sessionEnd.value?.reason)
        assertEquals("This device session expired. Sign in again.", repository.sessionEnd.value?.message)
    }

    @Test
    fun `late export 401 does not clear a newer login`() = runTest {
        val store = loggedInStore()
        val repository = repository(true, store)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                started.countDown()
                if (!release.await(10, TimeUnit.SECONDS)) error("Export response was not released")
                return MockResponse().setResponseCode(401)
                    .setBody("""{"detail":{"reason":"token_revoked"}}""")
            }
        }
        val old = async(Dispatchers.IO) { runCatching { repository.exportEbook(42) } }
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS))
            store.saveLogin(
                server.url("/").toString(),
                LoginResponse(token = "new-token", user = MobileUser(id = 2, username = "other")),
            )
            release.countDown()
            assertEquals(401, (old.await().exceptionOrNull() as HttpException).code())
            assertEquals(0, store.clearTokenCalls)
            assertEquals("new-token", store.current().token)
            assertNull(repository.sessionEnd.value)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `conflict detail is returned verbatim`() = runTest {
        val store = loggedInStore()
        val repository = repository(true, store)
        server.enqueue(
            MockResponse().setResponseCode(409).setHeader("Content-Type", "application/json")
                .setBody("""{"detail":"No chapters are available for export."}"""),
        )

        val result = repository.exportEbook(42)

        assertTrue(result is EbookExportResult.Refused)
        assertEquals(
            "No chapters are available for export.",
            (result as EbookExportResult.Refused).message,
        )
        assertEquals(0, store.clearTokenCalls)
        assertNull(repository.sessionEnd.value)
    }

    @Test
    fun `export 404 is refused without ending the session`() = runTest {
        val store = loggedInStore()
        val repository = repository(true, store)
        server.enqueue(MockResponse().setResponseCode(404))

        assertEquals(EbookExportResult.Refused("Fiction not found."), repository.exportEbook(42))
        assertEquals(0, store.clearTokenCalls)
        assertEquals("secret", store.current().token)
        assertNull(repository.sessionEnd.value)
    }
}
