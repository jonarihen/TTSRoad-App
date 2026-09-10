package dk.perspektiva.ttsroad.data

import kotlinx.coroutines.launch
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

private class FakeSkipsSessionStore(private var state: SessionState) : SessionStore {
    override suspend fun current() = state
    override suspend fun saveLogin(baseUrl: String, response: LoginResponse) = Unit
    override suspend fun clearToken() { state = state.copy(token = null) }
}

private const val SkipsBody = """
{"api_version":1,"chapter_id":10,"has_timings":true,"rule_count":1,"audio_duration":60.0,
 "segments":[{"start_seconds":50.0,"end_seconds":59.0,"duration_seconds":9.0,"label":"rule","preview":"plug"}],
 "total_skipped_seconds":9.0}
"""

class PlaybackSkipsRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: TtsRoadRepository

    @Before fun start() {
        server = MockWebServer().apply { start() }
        repository = TtsRoadRepository(
            FakeSkipsSessionStore(
                SessionState(serverUrl = server.url("/").toString(), token = "token", username = "reader"),
            ),
        )
    }

    @After fun stop() { server.shutdown() }

    private suspend fun enable() {
        server.enqueue(MockResponse().setBody("""{"capabilities":{"playback_skips":true}}"""))
        repository.refreshCurrentCapabilities()
        server.takeRequest()
    }

    @Test fun `unsupported capability fails safe without a request`() = runTest {
        assertTrue(repository.playbackSkips(10).isEmpty)
        assertEquals(0, server.requestCount)
    }

    @Test fun `fetch parses validates and authenticates`() = runTest {
        enable()
        server.enqueue(MockResponse().setBody(SkipsBody).setHeader("ETag", "\"one\""))
        val skips = repository.playbackSkips(10)
        val request = server.takeRequest()
        assertEquals("/api/mobile/chapters/10/skips", request.path)
        assertEquals("Bearer token", request.getHeader("Authorization"))
        assertEquals(50_000, skips.segments.single().startMs)
        assertFalse(skips.isEmpty)
    }

    @Test fun `cached ETag revalidates and 304 returns memory value`() = runTest {
        enable()
        server.enqueue(MockResponse().setBody(SkipsBody).setHeader("ETag", "\"one\""))
        val first = repository.playbackSkips(10)
        server.enqueue(MockResponse().setResponseCode(304))
        val second = repository.playbackSkips(10)
        server.takeRequest()
        assertEquals("\"one\"", server.takeRequest().getHeader("If-None-Match"))
        assertTrue(first === second)
    }

    @Test fun `failure uses cache and first failure is empty`() = runTest {
        enable()
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(repository.playbackSkips(10).isEmpty)
        server.enqueue(MockResponse().setBody(SkipsBody))
        val cached = repository.playbackSkips(10)
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(cached, repository.playbackSkips(10))
    }

    @Test fun `cancellation is rethrown`() = runTest {
        enable()
        server.enqueue(MockResponse().setResponseCode(500))
        val job = launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            repository.playbackSkips(10)
        }
        job.cancel()
        assertTrue(job.isCancelled)
    }

    @Test fun `mismatched chapter response is rejected and not cached`() = runTest {
        enable()
        server.enqueue(MockResponse().setBody(SkipsBody.replace("\"chapter_id\":10", "\"chapter_id\":11")))
        assertTrue(repository.playbackSkips(10).isEmpty)
        server.enqueue(MockResponse().setResponseCode(304))
        assertTrue(repository.playbackSkips(10).isEmpty)
        server.takeRequest()
        assertNull(server.takeRequest().getHeader("If-None-Match"))
    }
}

class PlaybackSkipsModelTest {
    @Test fun `invalid rows are dropped and valid rows sorted`() {
        val model = ChapterSkips.from(
            ChapterSkipsResponse(
                chapterId = 10,
                hasTimings = true,
                segments = listOf(
                    SkipSegmentWire(5.0, 4.0),
                    SkipSegmentWire(20.0, 30.0),
                    SkipSegmentWire(2.0, 3.0),
                ),
            ),
            10,
        )
        assertEquals(listOf(2_000L, 20_000L), model.segments.map { it.startMs })
    }

    @Test fun `matching rules without timings are reported`() {
        assertTrue(ChapterSkips(10, hasTimings = false, ruleCount = 1).needsTimings)
        assertFalse(ChapterSkips(10, hasTimings = true, ruleCount = 1).needsTimings)
    }
}
