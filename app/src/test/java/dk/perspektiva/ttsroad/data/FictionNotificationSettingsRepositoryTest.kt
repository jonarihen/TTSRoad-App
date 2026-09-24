package dk.perspektiva.ttsroad.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException

class FictionNotificationSettingsRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var store: FakeSessionStore

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private suspend fun repository(supported: Boolean?): TtsRoadRepository {
        store = FakeSessionStore(
            SessionState(serverUrl = server.url("/").toString(), token = "secret"),
        )
        val repository = TtsRoadRepository(store)
        val capabilities = supported?.let { """{"backlog_notifications":$it}""" } ?: "{}"
        server.enqueue(json("""{"capabilities":$capabilities}"""))
        repository.refreshCurrentCapabilities()
        server.takeRequest()
        return repository
    }

    @Test
    fun `get uses global fiction path and bearer auth and decodes response`() = runTest {
        val repository = repository(true)
        server.enqueue(
            json("""{"mode":"backlog","backlog_hours":2.5,"remaining_seconds":900.0,"backlog_armed":true}"""),
        )

        val result = repository.fictionNotificationSettings(42)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/fictions/42/notification-settings", request.path)
        assertEquals("Bearer secret", request.getHeader("Authorization"))
        val settings = (result as FictionNotificationSettingsResult.Loaded).settings
        assertEquals(NotificationModeBacklog, settings.mode)
        assertEquals(2.5, settings.backlogHours, 0.0)
        assertEquals(900.0, settings.remainingSeconds, 0.0)
        assertEquals(true, settings.backlogArmed)
    }

    @Test
    fun `get and patch decode true false missing and null armed status`() = runTest {
        val repository = repository(true)
        val fields = listOf(
            ",\"backlog_armed\":true" to true,
            ",\"backlog_armed\":false" to false,
            "" to null,
            ",\"backlog_armed\":null" to null,
        )
        for (method in listOf("GET", "PATCH")) {
            for ((field, expected) in fields) {
                server.enqueue(json("""{"mode":"backlog","backlog_hours":2,"remaining_seconds":0$field}"""))

                val result = requestSettings(repository, method) as FictionNotificationSettingsResult.Loaded

                assertEquals("$method $field", expected, result.settings.backlogArmed)
                assertEquals(method, server.takeRequest().method)
            }
        }
    }

    @Test
    fun `response construction without armed status stays source compatible`() {
        val settings = FictionNotificationSettings(NotificationModeBacklog, 2.0, 0.0)

        assertNull(settings.backlogArmed)
        assertEquals(FictionNotificationStatus.Unknown, fictionNotificationStatus(settings))
    }

    @Test
    fun `patch sends only exact accepted body and returns server settings`() = runTest {
        val repository = repository(true)
        for (mode in listOf(NotificationModeEvery, NotificationModeOff, NotificationModeBacklog)) {
            server.enqueue(
                json("""{"mode":"$mode","backlog_hours":2.5,"remaining_seconds":900.0,"backlog_armed":false}"""),
            )

            val result = repository.updateFictionNotificationSettings(
                7,
                FictionNotificationSettingsRequest(mode, 5.0),
            )

            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("/api/fictions/7/notification-settings", request.path)
            assertEquals("Bearer secret", request.getHeader("Authorization"))
            assertEquals(mapOf("mode" to mode, "backlog_hours" to 5.0), jsonMap(request.body.readUtf8()))
            assertEquals(
                FictionNotificationSettings(mode, 2.5, 900.0, false),
                (result as FictionNotificationSettingsResult.Loaded).settings,
            )
        }
    }

    @Test
    fun `false or missing capability makes no get or patch settings request`() = runTest {
        for (supported in listOf(false, null)) {
            val repository = repository(supported)
            val requestCount = server.requestCount

            for (method in listOf("GET", "PATCH")) {
                assertEquals(FictionNotificationSettingsResult.Unsupported, requestSettings(repository, method))
            }

            assertEquals(requestCount, server.requestCount)
        }
    }

    @Test
    fun `undiscovered capability makes no get or patch settings request`() = runTest {
        val repository = TtsRoadRepository(
            FakeSessionStore(SessionState(serverUrl = server.url("/").toString(), token = "secret")),
        )

        for (method in listOf("GET", "PATCH")) {
            assertEquals(FictionNotificationSettingsResult.Unsupported, requestSettings(repository, method))
        }

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `server details are retained for get and patch refusals`() = runTest {
        val repository = repository(true)
        val errors = listOf(
            Triple(404, """{"detail":"Follow this fiction to configure notifications"}""", "Follow this fiction to configure notifications"),
            Triple(403, """{"detail":{"message":"Permission denied"}}""", "Permission denied"),
            Triple(422, """{"detail":"Choose a valid threshold"}""", "Choose a valid threshold"),
        )
        for (method in listOf("GET", "PATCH")) {
            for ((code, body, message) in errors) {
                server.enqueue(json(body).setResponseCode(code))

                val result = requestSettings(repository, method)

                assertEquals(message, (result as FictionNotificationSettingsResult.Refused).message)
            }
        }
        assertEquals(0, store.clearTokenCalls)
        assertTrue(store.current().isLoggedIn)
    }

    @Test
    fun `get and patch use safe fallback for absent or unreadable server details`() = runTest {
        val repository = repository(true)
        for (method in listOf("GET", "PATCH")) {
            for (body in listOf("", "<html>private server failure</html>", "{}", """{"detail":" "}""", """{"detail":[]}""")) {
                server.enqueue(json(body).setResponseCode(500))

                val result = requestSettings(repository, method)

                assertEquals(
                    "The server would not load chapter notification settings.",
                    (result as FictionNotificationSettingsResult.Refused).message,
                )
            }
        }
        assertEquals(0, store.clearTokenCalls)
    }

    @Test
    fun `network failures become safe refusals for get and patch without ending session`() = runTest {
        val repository = repository(true)
        server.shutdown()

        for (method in listOf("GET", "PATCH")) {
            val result = requestSettings(repository, method)

            assertEquals(
                "Could not reach the server. Check your connection and try again.",
                (result as FictionNotificationSettingsResult.Refused).message,
            )
        }
        assertEquals(0, store.clearTokenCalls)
        assertTrue(store.current().isLoggedIn)
        assertNull(repository.sessionEnd.value)
    }

    @Test
    fun `unauthorized get and patch propagate and end the expired session`() = runTest {
        for (method in listOf("GET", "PATCH")) {
            val repository = repository(true)
            server.enqueue(
                json("""{"detail":{"message":"Sign in again.","reason":"token_expired"}}""")
                    .setResponseCode(401),
            )

            try {
                requestSettings(repository, method)
                fail("$method must propagate HTTP 401")
            } catch (e: HttpException) {
                assertEquals(401, e.code())
            }

            assertEquals(method, server.takeRequest().method)
            assertEquals(1, store.clearTokenCalls)
            assertFalse(store.current().isLoggedIn)
            assertNull(repository.authHeader)
            assertEquals(SessionEndReason.Expired, repository.sessionEnd.value?.reason)
            assertEquals("Sign in again.", repository.sessionEnd.value?.message)
        }
    }

    @Test
    fun `in flight get and patch cancellation propagates without a refusal or sign out`() = runTest {
        val repository = repository(true)
        for (method in listOf("GET", "PATCH")) {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            var result: FictionNotificationSettingsResult? = null
            var cancelled = false
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    result = requestSettings(repository, method)
                } catch (e: CancellationException) {
                    cancelled = true
                    throw e
                }
            }

            val request = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(request)
            assertEquals(method, request?.method)
            job.cancelAndJoin()

            assertTrue(cancelled)
            assertNull(result)
        }
        assertEquals(0, store.clearTokenCalls)
        assertTrue(store.current().isLoggedIn)
        assertNull(repository.sessionEnd.value)
    }

    @Test
    fun `backlog status trusts server armed flag regardless of remaining amount`() {
        for (seconds in listOf(0.0, 900.0, 7200.0, 14400.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            for ((armed, expected) in listOf(
                true to FictionNotificationStatus.Armed,
                false to FictionNotificationStatus.Waiting,
                null to FictionNotificationStatus.Unknown,
            )) {
                val settings = FictionNotificationSettings(NotificationModeBacklog, 2.0, seconds, armed)

                assertEquals("$armed at $seconds", expected, fictionNotificationStatus(settings))
            }
        }
    }

    @Test
    fun `every and off ignore armed flag and remaining amount`() {
        for (armed in listOf(true, false, null)) {
            for (seconds in listOf(0.0, 14400.0, Double.NaN)) {
                val every = FictionNotificationSettings(NotificationModeEvery, 2.0, seconds, armed)
                val off = every.copy(mode = NotificationModeOff)

                assertEquals(FictionNotificationStatus.Every, fictionNotificationStatus(every))
                assertEquals("Every chapter", notificationModeSummary(every))
                assertEquals("Notifications on — every new chapter.", notificationStatusDescription(every))
                assertEquals(FictionNotificationStatus.Off, fictionNotificationStatus(off))
                assertEquals("Off", notificationModeSummary(off))
                assertEquals("Notifications off.", notificationStatusDescription(off))
            }
        }
    }

    @Test
    fun `backlog summaries and descriptions explain alarm and rearming without claiming it triggered`() {
        for ((hours, label) in listOf(1.0 to "1 hour", 2.0 to "2 hours", 2.5 to "2.5 hours")) {
            for (seconds in listOf(0.0, 7200.0, 14400.0)) {
                val armed = FictionNotificationSettings(NotificationModeBacklog, hours, seconds, true)
                val waiting = armed.copy(backlogArmed = false)
                val unknown = armed.copy(backlogArmed = null)

                assertEquals("Alarm armed", notificationModeSummary(armed))
                assertEquals("Backlog alarm armed — alerts at $label.", notificationStatusDescription(armed))
                assertEquals("Alarm waiting", notificationModeSummary(waiting))
                assertEquals(
                    "Backlog alarm waiting — listen below $label to re-arm.",
                    notificationStatusDescription(waiting),
                )
                assertEquals("Status unavailable", notificationModeSummary(unknown))
                assertEquals("Backlog alarm — status unavailable.", notificationStatusDescription(unknown))
            }
        }
    }

    @Test
    fun `null and unsupported settings never imply a notification status`() {
        val settings = listOf(null) + listOf("", "future", "BACKLOG").flatMap { mode ->
            listOf(true, false, null).map { armed -> FictionNotificationSettings(mode, 2.0, 0.0, armed) }
        }
        for (value in settings) {
            assertEquals(FictionNotificationStatus.Unknown, fictionNotificationStatus(value))
            assertEquals("Status unavailable", notificationModeSummary(value))
            assertEquals("Notification status unavailable.", notificationStatusDescription(value))
        }
    }

    @Test
    fun `hours validation covers invalid and boundary values`() {
        for (value in listOf("", "invalid", "-1", "0", "NaN", "Infinity", "-Infinity", "1000.1")) {
            assertNull(value, validBacklogHours(value))
        }
        assertEquals(0.1, validBacklogHours("0.1"))
        assertEquals(1.0, validBacklogHours(" 1 "))
        assertEquals(2.5, validBacklogHours("2.5"))
        assertEquals(1000.0, validBacklogHours("1000"))
    }

    @Test
    fun `remaining label describes ready audio at zero subminute minute and hour boundaries`() {
        val labels = listOf(
            0.0 to "nothing",
            -0.0 to "nothing",
            Double.MIN_VALUE to "under a minute",
            1.0 to "under a minute",
            59.999 to "under a minute",
            60.0 to "1 min",
            60.1 to "2 min",
            900.0 to "15 min",
            3540.0 to "59 min",
            3599.0 to "1 h",
            3600.0 to "1 h",
            3600.1 to "1 h 1 min",
            5400.0 to "1 h 30 min",
            7200.0 to "2 h",
            7260.0 to "2 h 1 min",
        )
        for ((seconds, label) in labels) {
            assertEquals("$seconds", "Ready to listen: $label.", remainingBacklogLabel(seconds))
        }
    }

    @Test
    fun `invalid or negative remaining amounts have no label`() {
        for (seconds in listOf(-1.0, -Double.MIN_VALUE, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull("$seconds", remainingBacklogLabel(seconds))
        }
    }

    private suspend fun requestSettings(
        repository: TtsRoadRepository,
        method: String,
    ): FictionNotificationSettingsResult = when (method) {
        "GET" -> repository.fictionNotificationSettings(9)
        "PATCH" -> repository.updateFictionNotificationSettings(
            9,
            FictionNotificationSettingsRequest(NotificationModeBacklog, 2.0),
        )
        else -> error("Unexpected method: $method")
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
