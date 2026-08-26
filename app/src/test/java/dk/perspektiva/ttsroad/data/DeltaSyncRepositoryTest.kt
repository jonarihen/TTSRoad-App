package dk.perspektiva.ttsroad.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class DeltaSyncRepositoryTest {
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

    private fun json(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private suspend fun repository(supported: Boolean = true): TtsRoadRepository {
        val repository = TtsRoadRepository(
            FakeSessionStore(
                SessionState(serverUrl = server.url("/").toString(), token = "token"),
            ),
        )
        server.enqueue(
            json(
                """
                {"api_version":1,"server":{"name":"TTSRoad"},
                 "capabilities":{"delta_sync":$supported},"limits":{}}
                """.trimIndent(),
            ),
        )
        repository.refreshCurrentCapabilities()
        return repository
    }

    @Test
    fun `cursor is sent to the index library and chapter endpoints`() = runTest {
        val repository = repository()
        server.enqueue(
            json(
                """
                {"server_time":"t2","updated_since":"t1","delta":true,
                 "changed":{"library":true,"fictions":[],"playback":0,"bookmarks":0},
                 "deleted":{"fictions":[],"chapters":[],"bookmarks":[]}}
                """.trimIndent(),
            ),
        )
        server.enqueue(
            json(
                """
                {"scope":"followed","following_ids":[],"fictions":[],
                 "continue_listening":[],"recent_chapters":[],"server_time":"t2",
                 "updated_since":"t1","delta":true,"deleted":[]}
                """.trimIndent(),
            ),
        )
        server.enqueue(
            json(
                """
                {"fiction":{"id":7,"title":"Book"},"chapters":[],"server_time":"t2",
                 "updated_since":"t1","delta":true,"deleted":[]}
                """.trimIndent(),
            ),
        )

        assertEquals("t2", repository.deltaSync("t1")?.serverTime)
        repository.library(updatedSince = "t1")
        repository.chapters(7, updatedSince = "t1")

        server.takeRequest()
        assertEquals("/api/mobile/sync?updated_since=t1", server.takeRequest().path)
        assertEquals(
            "/api/mobile/library?scope=followed&updated_since=t1",
            server.takeRequest().path,
        )
        assertEquals(
            "/api/mobile/fictions/7/chapters?playable_only=false&include_excluded=false&updated_since=t1",
            server.takeRequest().path,
        )
    }

    @Test
    fun `an older server never receives a sync probe`() = runTest {
        val repository = repository(supported = false)

        assertNull(repository.deltaSync("t1"))
        assertEquals(1, server.requestCount)
    }
}
