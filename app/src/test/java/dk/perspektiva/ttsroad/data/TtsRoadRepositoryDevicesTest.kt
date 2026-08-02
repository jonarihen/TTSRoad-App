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

class TtsRoadRepositoryDevicesTest {
    private lateinit var server: MockWebServer

    private fun repository() = TtsRoadRepository(
        FakeSessionStore(
            SessionState(serverUrl = server.url("/").toString(), token = "token", username = "admin"),
        ),
    )

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    @Test
    fun `the device list parses every documented field`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"api_version":1,"devices":[
                  {"id":42,"device_name":"Pixel 8","created_at":"2026-07-01T09:15:00Z",
                   "last_used_at":"2026-08-01T18:04:00Z","expires_at":"2026-10-30T09:15:00Z",
                   "last_ip":"192.168.1.24","status":"active","is_current":true}
                ]}
                """.trimIndent(),
            ),
        )

        val devices = repository().devices()

        assertEquals(1, devices?.size)
        val device = devices!!.single()
        assertEquals(42, device.id)
        assertEquals("Pixel 8", device.deviceName)
        assertEquals("2026-07-01T09:15:00Z", device.createdAt)
        assertEquals("2026-08-01T18:04:00Z", device.lastUsedAt)
        assertEquals("2026-10-30T09:15:00Z", device.expiresAt)
        assertEquals("192.168.1.24", device.lastIp)
        assertEquals("active", device.status)
        assertTrue(device.isCurrent)
    }

    /** A session that has never been used has no IP, and the whole list must not fail over it. */
    @Test
    fun `a device with null fields still parses`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"api_version":1,"devices":[
                  {"id":7,"device_name":"Old tablet","created_at":"2026-07-01T09:15:00Z",
                   "last_used_at":null,"expires_at":null,"last_ip":null,"status":"expired",
                   "is_current":false}
                ]}
                """.trimIndent(),
            ),
        )

        val device = repository().devices()!!.single()

        assertEquals(7, device.id)
        assertNull(device.lastUsedAt)
        assertNull(device.expiresAt)
        assertNull(device.lastIp)
        assertEquals("expired", device.status)
        assertFalse(device.isCurrent)
    }

    @Test
    fun `an empty device list is a list, not a failure`() = runTest {
        server.enqueue(MockResponse().setBody("""{"api_version":1,"devices":[]}"""))

        assertEquals(emptyList<DeviceSession>(), repository().devices())
    }

    @Test
    fun `revoking one device deletes exactly that session`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))

        assertTrue(repository().revokeDevice(tokenId = 7))

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/mobile/devices/7", request.path)
    }

    @Test
    fun `revoking a device is reflected the next time the list is read`() = runTest {
        val repository = repository()
        server.enqueue(
            MockResponse().setBody(
                """{"api_version":1,"devices":[
                     {"id":1,"device_name":"Phone","is_current":true},
                     {"id":2,"device_name":"Tablet","is_current":false}]}""",
            ),
        )
        assertEquals(listOf(1, 2), repository.devices()?.map { it.id })

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        assertTrue(repository.revokeDevice(tokenId = 2))

        server.enqueue(
            MockResponse().setBody(
                """{"api_version":1,"devices":[
                     {"id":1,"device_name":"Phone","is_current":true}]}""",
            ),
        )
        assertEquals(listOf(1), repository.devices()?.map { it.id })
    }

    /**
     * Revoke-others is one server-side call precisely so the client cannot get the "which one am I"
     * question wrong: the token making the request is the one deliberately kept.
     */
    @Test
    fun `revoke-others is a single call that never deletes the current device`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))

        assertTrue(repository().revokeOtherDevices())

        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/mobile/devices/revoke-others", request.path)
    }

    @Test
    fun `revoke-others leaves the current device in the list`() = runTest {
        val repository = repository()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        assertTrue(repository.revokeOtherDevices())

        server.enqueue(
            MockResponse().setBody(
                """{"api_version":1,"devices":[
                     {"id":1,"device_name":"Phone","is_current":true}]}""",
            ),
        )
        val remaining = repository.devices()

        assertEquals(1, remaining?.size)
        assertTrue(remaining?.single()?.isCurrent == true)
    }

    /**
     * The devices API is additive and `api_version` did not change with it, so a 404 means an older
     * backend rather than a bug. The screen says "not supported"; nothing throws at the user.
     */
    @Test
    fun `an older server without the endpoint degrades quietly`() = runTest {
        val repository = repository()

        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"Not Found"}"""))
        assertNull(repository.devices())

        server.enqueue(MockResponse().setResponseCode(404))
        assertFalse(repository.revokeDevice(tokenId = 3))

        server.enqueue(MockResponse().setResponseCode(404))
        assertFalse(repository.revokeOtherDevices())
    }

    /** A 404 is "no such endpoint", not "your token is bad" — the session must survive it. */
    @Test
    fun `a 404 does not sign the user out`() = runTest {
        val store = FakeSessionStore(
            SessionState(serverUrl = server.url("/").toString(), token = "token", username = "admin"),
        )
        val repository = TtsRoadRepository(store)
        server.enqueue(MockResponse().setResponseCode(404))

        assertNull(repository.devices())

        assertEquals(0, store.clearTokenCalls)
        assertTrue(store.current().isLoggedIn)
        assertNull(repository.sessionEnd.value)
    }

    /** Anything other than a missing endpoint is a real failure the screen should report. */
    @Test
    fun `a server error on the device list is not swallowed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"detail":"boom"}"""))

        assertTrue(runCatching { repository().devices() }.isFailure)
    }

    @Test
    fun `a 401 on the device list still ends the session`() = runTest {
        val store = FakeSessionStore(
            SessionState(serverUrl = server.url("/").toString(), token = "stale", username = "admin"),
        )
        val repository = TtsRoadRepository(store)
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"detail":{"reason":"token_revoked"}}"""),
        )

        runCatching { repository.devices() }

        assertEquals(1, store.clearTokenCalls)
        assertEquals(SessionEndReason.Revoked, repository.sessionEnd.value?.reason)
    }

    @Test
    fun `the device list is requested with the bearer token`() = runTest {
        server.enqueue(MockResponse().setBody("""{"api_version":1,"devices":[]}"""))

        repository().devices()

        val request = server.takeRequest()
        assertEquals("/api/mobile/devices", request.path)
        assertEquals("Bearer token", request.getHeader("Authorization"))
    }
}
