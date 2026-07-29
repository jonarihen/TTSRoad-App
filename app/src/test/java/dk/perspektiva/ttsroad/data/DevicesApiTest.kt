package dk.perspektiva.ttsroad.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The devices payloads decode by JSON key, so these bodies are copied from `build.md` rather than
 * written to match the DTOs. A rename on either side has to fail here.
 */
class DevicesApiTest {
    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun repository() = TtsRoadRepository(
        object : SessionStore {
            private var state = SessionState(
                serverUrl = server.url("/").toString(),
                token = "live-token",
                username = "admin",
            )

            override suspend fun current(): SessionState = state
            override suspend fun saveLogin(baseUrl: String, response: LoginResponse) = Unit
            override suspend fun clearToken() {
                state = state.copy(token = null)
            }
        },
    )

    @Test
    fun `the devices list decodes every documented field`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"api_version":1,"devices":[
                  {"id":42,"user_id":1,"device_name":"Pixel 8",
                   "created_at":"2026-07-28T12:00:00Z","last_used_at":"2026-07-29T11:00:00Z",
                   "expires_at":"2026-10-26T12:00:00Z","last_ip":"10.0.0.4",
                   "status":"active","is_current":true},
                  {"id":7,"user_id":1,"device_name":"Old phone",
                   "created_at":"2026-01-02T09:30:00Z","last_used_at":null,
                   "expires_at":"2026-04-02T09:30:00Z","last_ip":null,
                   "status":"expired","is_current":false}
                ]}
                """.trimIndent(),
            ),
        )

        val response = repository().devices()

        assertEquals(1, response.apiVersion)
        assertEquals(2, response.devices.size)
        val current = response.devices.first()
        assertEquals(42, current.id)
        assertEquals("Pixel 8", current.deviceName)
        assertEquals("2026-07-28T12:00:00Z", current.createdAt)
        assertEquals("2026-07-29T11:00:00Z", current.lastUsedAt)
        assertEquals("2026-10-26T12:00:00Z", current.expiresAt)
        assertEquals("10.0.0.4", current.lastIp)
        assertEquals("active", current.status)
        assertTrue(current.isCurrent)

        val stale = response.devices[1]
        assertEquals(null, stale.lastUsedAt)
        assertEquals(null, stale.lastIp)
        assertEquals("expired", stale.status)
        assertTrue(!stale.isCurrent)
    }

    @Test
    fun `revoking one device sends a DELETE for that id`() = runTest {
        server.enqueue(MockResponse().setBody("""{"status":"ok","revoked":true,"token_id":7}"""))

        val response = repository().revokeDevice(7)

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/mobile/devices/7", request.path)
        assertEquals("Bearer live-token", request.getHeader("Authorization"))
        assertTrue(response.revoked)
        assertEquals(7, response.tokenId)
    }

    @Test
    fun `revoking the others posts to revoke-others and reports the count`() = runTest {
        server.enqueue(MockResponse().setBody("""{"status":"ok","revoked_count":3}"""))

        val response = repository().revokeOtherDevices()

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/mobile/devices/revoke-others", request.path)
        assertEquals(3, response.revokedCount)
    }

    @Test
    fun `a login response carries the new device id and expiry`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"token":"ttsr_x","token_type":"bearer","device_id":42,
                 "expires_at":"2026-10-26T12:00:00Z",
                 "user":{"id":1,"username":"admin","is_admin":true},
                 "server":{"name":"TTSRoad","base_url":"https://ttsroad.example.com","api_version":1}}
                """.trimIndent(),
            ),
        )
        var saved: LoginResponse? = null
        val repository = TtsRoadRepository(
            object : SessionStore {
                override suspend fun current(): SessionState = SessionState()
                override suspend fun saveLogin(baseUrl: String, response: LoginResponse) {
                    saved = response
                }

                override suspend fun clearToken() = Unit
            },
        )

        val result = repository.login(
            baseUrl = server.url("/").toString(),
            username = "admin",
            password = "correct",
            deviceName = "Pixel 8",
        )

        assertEquals(LoginResult.Success, result)
        assertEquals(42, saved?.deviceId)
        assertEquals("2026-10-26T12:00:00Z", saved?.expiresAt)
    }

    @Test
    fun `a revoked session found on the devices call signs the app out`() = runTest {
        val repository = repository()
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"detail":{"message":"This device session was revoked. Sign in again.",""" +
                    """"reason":"token_revoked"}}""",
            ),
        )

        runCatching { repository.devices() }

        assertEquals(AuthFailureReason.Revoked, repository.sessionEnded.value?.reason)
    }
}
