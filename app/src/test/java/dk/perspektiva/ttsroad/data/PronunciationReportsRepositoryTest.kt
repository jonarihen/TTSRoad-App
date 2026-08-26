package dk.perspektiva.ttsroad.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException

private class FakePronunciationReportSessionStore(
    private var state: SessionState = SessionState(),
) : SessionStore {
    override suspend fun current(): SessionState = state

    override suspend fun saveLogin(baseUrl: String, response: LoginResponse) {
        state = SessionState(
            serverUrl = normalizeBaseUrl(baseUrl),
            token = response.token,
            username = response.user.username,
        )
    }

    override suspend fun clearToken() {
        state = state.copy(token = null)
    }
}

/**
 * The mobile capture contract for "that word was pronounced wrong" (#125).
 *
 * These tests pin the parts a caller cannot see from a data class: capability gating, the exact
 * query and request keys, optional word handling, safe clock values and the delete/409 semantics.
 */
class PronunciationReportsRepositoryTest {
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

    private fun capabilities(supported: Boolean) = """
        {
          "api_version": 1,
          "server": {"name": "TTSRoad", "version": "1.6.0"},
          "capabilities": {"pronunciation_reports": $supported},
          "limits": {}
        }
    """.trimIndent()

    private fun json(body: String, responseCode: Int = 200) = MockResponse()
        .setResponseCode(responseCode)
        .setBody(body)
        .setHeader("Content-Type", "application/json")

    private suspend fun repository(supported: Boolean = true): TtsRoadRepository {
        val store = FakePronunciationReportSessionStore()
        store.saveLogin(
            server.url("/").toString(),
            LoginResponse(token = "t0ken", user = MobileUser(id = 1, username = "listener")),
        )
        val repository = TtsRoadRepository(store)
        server.enqueue(json(capabilities(supported)))
        repository.refreshCurrentCapabilities()
        return repository
    }

    @Test
    fun `the capability is explicit and absent means unsupported`() {
        val enabled = ServerCapabilities.from(
            CapabilitiesResponse(capabilities = mapOf("pronunciation_reports" to true)),
        )
        val vague = ServerCapabilities.from(
            CapabilitiesResponse(capabilities = mapOf("pronunciation_reports" to "yes")),
        )

        assertTrue(enabled.pronunciationReports)
        assertTrue(enabled.advertised["pronunciation_reports"] == true)
        assertFalse(vague.pronunciationReports)
        assertFalse(ServerCapabilities.Baseline.pronunciationReports)
        assertEquals("Report a mispronunciation", CapabilityCatalog.label("pronunciation_reports"))
    }

    @Test
    fun `the default list asks only for open reports and parses joined titles`() = runTest {
        val repository = repository()
        server.enqueue(
            json(
                """
                {"api_version": 1, "reports": [{
                  "id": 7,
                  "fiction_id": 1,
                  "fiction_title": "A Test Serial",
                  "fiction_slug": "a-test-serial",
                  "chapter_id": 10,
                  "chapter_number": 4,
                  "chapter_title": "Powerful",
                  "position_seconds": 283.5,
                  "word": "Kaelith",
                  "note": null,
                  "reported_by": "listener",
                  "resolved": false,
                  "resolved_at": null,
                  "created_at": "2026-08-26T09:14:02Z"
                }]}
                """.trimIndent(),
            ),
        )

        val reports = repository.pronunciationReports()

        server.takeRequest() // capabilities
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("false", request.requestUrl?.queryParameter("include_resolved"))
        assertNull(request.requestUrl?.queryParameter("fiction_id"))
        assertEquals("Bearer t0ken", request.getHeader("Authorization"))
        assertEquals(1, reports?.size)
        assertEquals("A Test Serial", reports?.single()?.fictionTitle)
        assertEquals(4.0, reports?.single()?.chapterNumber)
        assertEquals("Kaelith", reports?.single()?.word)
    }

    @Test
    fun `the list forwards fiction and resolved filters`() = runTest {
        val repository = repository()
        server.enqueue(json("""{"api_version": 1, "reports": []}"""))

        val reports = repository.pronunciationReports(fictionId = 12, includeResolved = true)

        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("12", request.requestUrl?.queryParameter("fiction_id"))
        assertEquals("true", request.requestUrl?.queryParameter("include_resolved"))
        assertEquals(emptyList<PronunciationReport>(), reports)
    }

    @Test
    fun `a capture needs no word and sends the fiction only when known`() = runTest {
        val repository = repository()
        server.enqueue(
            json(
                """{"api_version": 1, "report": {
                  "id": 7, "fiction_id": 1, "chapter_id": 10,
                  "position_seconds": 283.5, "word": null, "note": null
                }}""",
                responseCode = 201,
            ),
        )

        val created = repository.createPronunciationReport(
            chapterId = 10,
            positionSeconds = 283.5,
        )

        server.takeRequest()
        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertEquals("POST", request.method)
        assertEquals("/api/mobile/pronunciation-reports", request.path)
        assertTrue(body, "\"chapter_id\":10" in body)
        assertTrue(body, "\"position_seconds\":283.5" in body)
        assertTrue(body, "\"fiction_id\"" !in body)
        assertTrue(body, "\"word\"" !in body)
        assertTrue(body, "\"note\"" !in body)
        assertEquals(7, created?.id)
        assertNull(created?.word)
    }

    @Test
    fun `known optional context is trimmed and sent`() = runTest {
        val repository = repository()
        server.enqueue(
            json(
                """{"api_version": 1, "report": {
                  "id": 8, "fiction_id": 1, "chapter_id": 10,
                  "position_seconds": 12.0, "word": "Kaelith",
                  "note": "said Kay-el-ith every time"
                }}""",
                responseCode = 201,
            ),
        )

        repository.createPronunciationReport(
            chapterId = 10,
            fictionId = 1,
            positionSeconds = 12.0,
            word = "  Kaelith  ",
            note = "  said Kay-el-ith every time  ",
        )

        server.takeRequest()
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, "\"fiction_id\":1" in body)
        assertTrue(body, "\"word\":\"Kaelith\"" in body)
        assertTrue(body, "\"note\":\"said Kay-el-ith every time\"" in body)
    }

    @Test
    fun `invalid media positions flatten to zero before JSON serialization`() = runTest {
        for (position in listOf(-30.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val repository = repository()
            server.enqueue(
                json(
                    """{"api_version": 1, "report": {
                      "id": 8, "chapter_id": 10, "position_seconds": 0.0
                    }}""",
                    responseCode = 201,
                ),
            )

            repository.createPronunciationReport(chapterId = 10, positionSeconds = position)

            server.takeRequest()
            val body = server.takeRequest().body.readUtf8()
            assertTrue("position=$position body=$body", "\"position_seconds\":0" in body)
        }
    }

    @Test
    fun `deleting is idempotent when the report is already gone`() = runTest {
        val repository = repository()
        server.enqueue(MockResponse().setResponseCode(404))

        assertTrue(repository.deletePronunciationReport(7))

        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/mobile/pronunciation-reports/7", request.path)
    }

    @Test
    fun `the open report ceiling remains a readable 409`() = runTest {
        val repository = repository()
        server.enqueue(
            json(
                """{"detail":"You already have 500 open pronunciation reports."}""",
                responseCode = 409,
            ),
        )

        val thrown = runCatching {
            repository.createPronunciationReport(chapterId = 10, positionSeconds = 1.0)
        }.exceptionOrNull()

        assertTrue(thrown is HttpException)
        val http = thrown as HttpException
        assertEquals(409, http.code())
        val detail = http.response()?.errorBody()?.string().orEmpty()
        assertTrue(detail, "500 open pronunciation reports" in detail)
    }

    @Test
    fun `an older server is never called`() = runTest {
        val repository = repository(supported = false)

        assertNull(repository.pronunciationReports())
        assertNull(repository.createPronunciationReport(chapterId = 10, positionSeconds = 1.0))
        assertFalse(repository.deletePronunciationReport(7))

        server.takeRequest()
        assertEquals(1, server.requestCount)
    }
}
