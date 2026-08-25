package dk.perspektiva.ttsroad.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeAccountSessionStore(
    var state: SessionState,
) : SessionStore {
    override suspend fun current(): SessionState = state

    override suspend fun saveLogin(baseUrl: String, response: LoginResponse) {
        state = SessionState(
            serverUrl = normalizeBaseUrl(baseUrl),
            token = response.token,
            username = response.user.username,
            isAdmin = response.user.isAdmin,
            serverName = response.server?.name ?: "TTSRoad",
            deviceId = response.deviceId,
            expiresAt = response.expiresAt,
        )
    }

    override suspend fun clearToken() {
        state = state.copy(token = null)
    }
}

/** Native password and two-factor account management (#118). */
class AccountSecurityRepositoryTest {
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

    private fun json(body: String, code: Int = 200) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private suspend fun repository(
        supported: Boolean = true,
    ): Pair<TtsRoadRepository, FakeAccountSessionStore> {
        val store = FakeAccountSessionStore(
            SessionState(
                serverUrl = server.url("/").toString(),
                token = "old-token",
                username = "reader",
                serverName = "My Road",
                deviceId = 7,
            ),
        )
        val repository = TtsRoadRepository(store)
        server.enqueue(
            json(
                """
                {"api_version": 1, "server": {"name": "My Road"},
                 "capabilities": {"account_security": $supported}, "limits": {}}
                """.trimIndent(),
            ),
        )
        repository.refreshCurrentCapabilities()
        return repository to store
    }

    @Test
    fun `password change adopts the replacement token without losing server identity`() = runTest {
        val (repository, store) = repository()
        server.enqueue(
            json(
                """
                {"api_version": 1, "status": "ok", "token": "fresh-token",
                 "token_type": "bearer", "device_id": 19,
                 "expires_at": "2026-10-01T12:00:00Z",
                 "user": {"id": 4, "username": "reader", "is_admin": true}}
                """.trimIndent(),
            ),
        )

        val result = repository.changePassword("old pass", "a much better pass")

        server.takeRequest() // capabilities
        val request = server.takeRequest()
        assertEquals("/api/mobile/account/password", request.path)
        assertEquals("POST", request.method)
        assertEquals("Bearer old-token", request.getHeader("Authorization"))
        assertTrue(request.body.readUtf8().contains("\"current_password\":\"old pass\""))
        assertTrue(result is AccountActionResult.Done)
        assertEquals("fresh-token", store.state.token)
        assertEquals(19, store.state.deviceId)
        assertEquals("My Road", store.state.serverName)
        assertTrue(store.state.isAdmin)
    }

    @Test
    fun `the replacement credential is used by the very next account call`() = runTest {
        val (repository, _) = repository()
        server.enqueue(
            json(
                """
                {"token": "fresh-token", "token_type": "bearer", "device_id": 19,
                 "user": {"id": 4, "username": "reader"}}
                """.trimIndent(),
            ),
        )
        server.enqueue(json("""{"enabled": false, "recovery_codes_remaining": 0}"""))

        repository.changePassword("old", "new")
        val status = repository.twoFactorStatus()

        server.takeRequest()
        server.takeRequest()
        val statusRequest = server.takeRequest()
        assertEquals("/api/mobile/account/2fa", statusRequest.path)
        assertEquals("Bearer fresh-token", statusRequest.getHeader("Authorization"))
        assertFalse(status?.enabled ?: true)
    }

    @Test
    fun `a rejected password is an answer and leaves the session untouched`() = runTest {
        val (repository, store) = repository()
        server.enqueue(json("""{"detail":"Current password is incorrect."}""", code = 400))

        val result = repository.changePassword("wrong", "new")

        assertEquals(
            "Current password is incorrect.",
            (result as AccountActionResult.Refused).message,
        )
        assertEquals("old-token", store.state.token)
    }

    @Test
    fun `two-factor setup enable recovery and disable use their distinct contracts`() = runTest {
        val (repository, _) = repository()
        server.enqueue(
            json(
                """
                {"api_version": 1, "secret": "JBSWY3DPEHPK3PXP",
                 "otpauth_uri": "otpauth://totp/TTSRoad:reader?secret=JBSWY3DPEHPK3PXP"}
                """.trimIndent(),
            ),
        )
        server.enqueue(
            json(
                """{"api_version": 1, "enabled": true,
                     "recovery_codes": ["first-code", "second-code"]}""",
            ),
        )
        server.enqueue(
            json("""{"api_version": 1, "recovery_codes": ["replacement-code"]}"""),
        )
        server.enqueue(json("""{"api_version": 1, "enabled": false}"""))

        val setup = repository.startTwoFactorSetup() as AccountActionResult.Done
        val enabled = repository.enableTwoFactor(" 123456 ") as AccountActionResult.Done
        val reissued = repository.reissueRecoveryCodes() as AccountActionResult.Done
        val disabled = repository.disableTwoFactor("still secret") as AccountActionResult.Done

        server.takeRequest()
        assertEquals("/api/mobile/account/2fa/setup", server.takeRequest().path)
        val enableRequest = server.takeRequest()
        assertEquals("/api/mobile/account/2fa/enable", enableRequest.path)
        assertTrue(enableRequest.body.readUtf8().contains("\"code\":\"123456\""))
        assertEquals("/api/mobile/account/2fa/recovery-codes", server.takeRequest().path)
        val disableRequest = server.takeRequest()
        assertEquals("/api/mobile/account/2fa/disable", disableRequest.path)
        assertTrue(disableRequest.body.readUtf8().contains("\"password\":\"still secret\""))

        assertEquals("JBSWY3DPEHPK3PXP", setup.value.secret)
        assertEquals(listOf("first-code", "second-code"), enabled.value.recoveryCodes)
        assertEquals(listOf("replacement-code"), reissued.value.recoveryCodes)
        assertFalse(disabled.value.enabled)
    }

    @Test
    fun `a bad authenticator code keeps the server explanation`() = runTest {
        val (repository, _) = repository()
        server.enqueue(json("""{"detail":"That code didn't match. Try again."}""", code = 400))

        val result = repository.enableTwoFactor("000000")

        assertEquals(
            "That code didn't match. Try again.",
            (result as AccountActionResult.Refused).message,
        )
    }

    @Test
    fun `an older server is gated without making an account request`() = runTest {
        val (repository, _) = repository(supported = false)

        val password = repository.changePassword("old", "new")
        val setup = repository.startTwoFactorSetup()

        assertTrue(password is AccountActionResult.Unsupported)
        assertTrue(setup is AccountActionResult.Unsupported)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `recovery codes stay outstanding until explicitly acknowledged`() {
        assertFalse(hasUnsavedRecoveryCodes(null))
        assertFalse(hasUnsavedRecoveryCodes(emptyList()))
        assertTrue(hasUnsavedRecoveryCodes(listOf("one")))
    }
}
