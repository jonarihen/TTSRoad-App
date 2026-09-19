package dk.perspektiva.ttsroad.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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

    private suspend fun repository(enabled: Boolean): TtsRoadRepository {
        val store = FakeSessionStore(SessionState())
        store.saveLogin(
            server.url("/").toString(),
            LoginResponse(token = "secret", user = MobileUser(id = 1, username = "reader")),
        )
        val repository = TtsRoadRepository(store)
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
    fun `conflict detail is returned verbatim`() = runTest {
        val repository = repository(true)
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
    }
}
