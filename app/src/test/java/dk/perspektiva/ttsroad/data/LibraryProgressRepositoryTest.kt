package dk.perspektiva.ttsroad.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The current and pre-aggregate forms of the library payload (#163). */
class LibraryProgressRepositoryTest {
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

    private fun repository() = TtsRoadRepository(
        FakeSessionStore(
            SessionState(serverUrl = server.url("/").toString(), token = "token"),
        ),
    )

    private fun json(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    @Test
    fun `the complete per-fiction aggregate is decoded`() = runTest {
        server.enqueue(
            json(
                """
                {"fictions":[{"id":1,"title":"Ashes","progress":{
                  "chapters_total":75,"chapters_ready":73,"chapters_played":12,
                  "chapters_unplayed":61,"duration_seconds":196692.0,
                  "duration_label":"54h 39m","remaining_seconds":150031.0,
                  "remaining_label":"41h 41m"}}]}
                """.trimIndent(),
            ),
        )

        val progress = repository().library().fictions.single().progress

        assertEquals(75, progress?.chaptersTotal)
        assertEquals(73, progress?.chaptersReady)
        assertEquals(12, progress?.chaptersPlayed)
        assertEquals(61, progress?.chaptersUnplayed)
        assertEquals(196_692.0, progress?.durationSeconds ?: -1.0, 0.001)
        assertEquals("54h 39m", progress?.durationLabel)
        assertEquals(150_031.0, progress?.remainingSeconds ?: -1.0, 0.001)
        assertEquals("41h 41m", progress?.remainingLabel)
    }

    @Test
    fun `an older server with no progress key still parses as unknown`() = runTest {
        server.enqueue(json("""{"fictions":[{"id":1,"title":"Ashes"}]}"""))

        val fiction = repository().library().fictions.single()

        assertNull(fiction.progress)
    }

    @Test
    fun `an explicit null aggregate is also unknown`() = runTest {
        server.enqueue(json("""{"fictions":[{"id":1,"title":"Ashes","progress":null}]}"""))

        val fiction = repository().library().fictions.single()

        assertNull(fiction.progress)
    }
}
