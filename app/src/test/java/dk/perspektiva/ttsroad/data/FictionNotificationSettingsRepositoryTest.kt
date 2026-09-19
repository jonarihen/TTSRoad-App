package dk.perspektiva.ttsroad.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FictionNotificationSettingsRepositoryTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private suspend fun repository(supported: Boolean): TtsRoadRepository {
        val store = FakeSessionStore(
            SessionState(serverUrl = server.url("/").toString(), token = "secret"),
        )
        val repository = TtsRoadRepository(store)
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"capabilities":{"backlog_notifications":$supported}}""",
            ),
        )
        repository.refreshCurrentCapabilities()
        server.takeRequest()
        return repository
    }

    @Test
    fun `get uses global fiction path and bearer auth and decodes response`() = runTest {
        val repository = repository(true)
        server.enqueue(json("""{"mode":"backlog","backlog_hours":2.5,"remaining_seconds":900.0}"""))

        val result = repository.fictionNotificationSettings(42)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/fictions/42/notification-settings", request.path)
        assertEquals("Bearer secret", request.getHeader("Authorization"))
        val settings = (result as FictionNotificationSettingsResult.Loaded).settings
        assertEquals(NotificationModeBacklog, settings.mode)
        assertEquals(2.5, settings.backlogHours, 0.0)
        assertEquals(900.0, settings.remainingSeconds, 0.0)
    }

    @Test
    fun `patch sends only exact accepted body`() = runTest {
        val repository = repository(true)
        server.enqueue(json("""{"mode":"every","backlog_hours":5.0,"remaining_seconds":0.0}"""))

        repository.updateFictionNotificationSettings(
            7,
            FictionNotificationSettingsRequest(NotificationModeEvery, 5.0),
        )

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/fictions/7/notification-settings", request.path)
        assertEquals("Bearer secret", request.getHeader("Authorization"))
        assertEquals(mapOf("mode" to "every", "backlog_hours" to 5.0), jsonMap(request.body.readUtf8()))
    }

    @Test
    fun `unsupported capability makes no settings request`() = runTest {
        val repository = repository(false)

        assertEquals(FictionNotificationSettingsResult.Unsupported, repository.fictionNotificationSettings(1))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `server detail is retained`() = runTest {
        val repository = repository(true)
        server.enqueue(json("""{"detail":"Follow this fiction to configure notifications"}""").setResponseCode(404))

        val result = repository.fictionNotificationSettings(9)

        assertEquals(
            "Follow this fiction to configure notifications",
            (result as FictionNotificationSettingsResult.Refused).message,
        )
    }

    @Test
    fun `hours validation and remaining labels cover boundaries`() {
        assertNull(validBacklogHours("0"))
        assertNull(validBacklogHours("1000.1"))
        assertEquals(1.0, validBacklogHours("1"))
        assertEquals(1000.0, validBacklogHours("1000"))
        assertEquals("15 min remaining until the backlog notice", remainingBacklogLabel(900.0))
        assertEquals("2 h remaining until the backlog notice", remainingBacklogLabel(7200.0))
    }

    private fun json(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun jsonMap(body: String): Map<String, Any?> {
        val adapter = com.squareup.moshi.Moshi.Builder().build().adapter(Map::class.java)
        @Suppress("UNCHECKED_CAST")
        return adapter.fromJson(body) as Map<String, Any?>
    }
}
