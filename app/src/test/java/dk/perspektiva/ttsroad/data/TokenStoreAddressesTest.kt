package dk.perspektiva.ttsroad.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #372: the saved addresses of the signed-in server, as persisted next to the session. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TokenStoreAddressesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private object PrefixCipher : TokenCipher {
        override fun seal(plaintext: String): String = "${TokenEnvelopePrefix}sealed-$plaintext"
        override fun open(stored: String?): String? = stored?.removePrefix("${TokenEnvelopePrefix}sealed-")
    }

    private val store = TokenStore(context, PrefixCipher)

    private fun login(
        token: String,
        username: String = "admin",
        baseUrl: String? = "https://tts.example",
        instanceId: String? = null,
        deviceId: Int? = null,
    ) =
        LoginResponse(
            token = token,
            deviceId = deviceId,
            user = MobileUser(id = 1, username = username),
            server = ServerInfo(baseUrl = baseUrl, instanceId = instanceId),
        )

    private val instance = "3f0c9a52-7d1e-4f63-9a0b-2c4e8d6b1f70"

    @After
    fun clear() = runTest { store.clearAll() }

    @Test
    fun `sign-in records the server's identity and the account id`() = runTest {
        store.saveLogin("https://lan.example", login("t1"))

        assertEquals("tts.example", store.current().serverIdentity)
        assertEquals(1, store.current().userId)
    }

    @Test
    fun `switching keeps the token, sealed, and remembers the address left behind`() = runTest {
        store.saveLogin("https://lan.example", login("t1"))
        val owner = store.current()

        assertTrue(store.useAddress(owner, "https://ts.example", "tts.example"))

        val session = store.current()
        assertEquals("https://ts.example/", session.serverUrl)
        assertEquals("t1", session.token)
        assertTrue(session.isLoggedIn)
        assertEquals(listOf("https://lan.example/"), session.alternateUrls)

        assertTrue(store.useAddress(session, "https://lan.example/", "tts.example"))
        assertEquals("https://lan.example/", store.current().serverUrl)
        assertEquals(listOf("https://ts.example/"), store.current().alternateUrls)
    }

    @Test
    fun `nothing is stored for a session that is no longer the live one`() = runTest {
        store.saveLogin("https://lan.example", login("t1"))
        val stale = store.current()
        store.saveLogin("https://lan.example", login("t2"))

        assertFalse(store.useAddress(stale, "https://ts.example", "tts.example"))
        assertFalse(store.rememberAddress(stale, "https://ts.example", "tts.example"))
        assertEquals("https://lan.example/", store.current().serverUrl)
        assertEquals(emptyList<String>(), store.current().alternateUrls)

        store.clearToken()
        assertFalse(store.useAddress(store.current(), "https://ts.example", "tts.example"))
    }

    @Test
    fun `saved addresses survive signing in again at the same address as the same account`() = runTest {
        store.saveLogin("https://lan.example", login("t1"))
        store.rememberAddress(store.current(), "https://ts.example", "tts.example")
        store.clearToken()

        assertEquals("kept across a sign-out, like the address itself", listOf("https://ts.example/"), store.current().alternateUrls)
        assertNull(store.current().userId)

        store.saveLogin("https://LAN.example/", login("t2"))
        assertEquals(listOf("https://ts.example/"), store.current().alternateUrls)
    }

    @Test
    fun `another address or another server identity starts the list over`() = runTest {
        store.saveLogin("https://lan.example", login("t1"))
        store.rememberAddress(store.current(), "https://ts.example", "tts.example")
        store.saveLogin("https://elsewhere.example", login("t2"))
        assertEquals(emptyList<String>(), store.current().alternateUrls)

        store.rememberAddress(store.current(), "https://ts.example", "tts.example")
        store.saveLogin("https://elsewhere.example", login("t3", baseUrl = "https://other.example"))
        assertEquals(emptyList<String>(), store.current().alternateUrls)
        assertEquals("other.example", store.current().serverIdentity)
    }

    @Test
    fun `another account at the same address keeps the server's addresses, which are re-checked before use`() = runTest {
        store.saveLogin("https://lan.example", login("t1"))
        store.rememberAddress(store.current(), "https://ts.example", "tts.example")
        store.clearToken()

        store.saveLogin("https://lan.example", login("t2", username = "guest"))

        assertEquals(listOf("https://ts.example/"), store.current().alternateUrls)
        assertEquals("guest", store.current().username)
    }

    @Test
    fun `a password change rotates the token and keeps the saved addresses, identity and account id`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val address = server.url("/").toString()
            store.saveLogin(address, login("old-token", instanceId = instance, deviceId = 7))
            store.rememberAddress(store.current(), "https://ts.example", "tts.example")
            val repository = TtsRoadRepository(store)
            server.enqueue(
                MockResponse().setBody(
                    """{"api_version":1,"server":{"name":"TTSRoad","base_url":"https://tts.example"},
                       "capabilities":{"account_security":true},"limits":{}}""",
                ),
            )
            repository.refreshCurrentCapabilities()
            server.enqueue(
                MockResponse().setBody(
                    """{"api_version":1,"status":"ok","token":"fresh-token","token_type":"bearer",
                       "device_id":19,"user":{"id":1,"username":"admin","is_admin":false}}""",
                ),
            )

            val result = repository.changePassword("old pass", "a much better pass")

            assertEquals(AccountActionResult.Done(Unit), result)
            val session = store.current()
            assertEquals("fresh-token", session.token)
            assertEquals(address, session.serverUrl)
            assertEquals(listOf("https://ts.example/"), session.alternateUrls)
            assertEquals("tts.example", session.serverIdentity)
            assertEquals("the password response has no server block; the instance id stays", instance, session.serverInstanceId)
            assertEquals("the proof is asked for the rotated session, not the revoked one", 19, session.deviceId)
            assertEquals(1, session.userId)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `forgetting removes a saved address and never the one in use`() = runTest {
        store.saveLogin("https://lan.example", login("t1"))
        store.rememberAddress(store.current(), "https://ts.example", "tts.example")

        store.forgetAddress("https://lan.example/")
        assertEquals(listOf("https://ts.example/"), store.current().alternateUrls)

        store.forgetAddress("https://TS.example")
        assertEquals(emptyList<String>(), store.current().alternateUrls)
        assertEquals("https://lan.example/", store.current().serverUrl)
        assertTrue(store.current().isLoggedIn)
    }

    @Test
    fun `sign-out of everything forgets the addresses too`() = runTest {
        store.saveLogin("https://lan.example", login("t1"))
        store.rememberAddress(store.current(), "https://ts.example", "tts.example")

        store.clearAll()

        assertEquals(emptyList<String>(), store.current().alternateUrls)
        assertNull(store.current().serverIdentity)
    }

    @Test
    fun `sign-in records the instance id and the device id the session proof needs`() = runTest {
        store.saveLogin("https://lan.example", login("t1", instanceId = " 3F0C9A52-7D1E-4F63-9A0B-2C4E8D6B1F70", deviceId = 42))

        assertEquals(instance, store.current().serverInstanceId)
        assertEquals(42, store.current().deviceId)
    }

    @Test
    fun `the instance id survives sign-out and a sign-in on a server that does not report one`() = runTest {
        store.saveLogin("https://lan.example", login("t1", instanceId = instance))
        store.clearToken()
        assertEquals(instance, store.current().serverInstanceId)

        store.saveLogin("https://lan.example", login("t2"))
        assertEquals(instance, store.current().serverInstanceId)
    }

    @Test
    fun `instance ids decide whether the saved addresses are still this server's`() = runTest {
        store.saveLogin("https://lan.example", login("t1", instanceId = instance))
        store.rememberAddress(store.current(), "https://ts.example", "tts.example")

        // BASE_URL corrected on the server: same install, so the addresses stay.
        store.saveLogin("https://lan.example", login("t2", baseUrl = "https://renamed.example", instanceId = instance))
        assertEquals(listOf("https://ts.example/"), store.current().alternateUrls)
        assertEquals("renamed.example", store.current().serverIdentity)

        // Reinstalled behind the same BASE_URL: another server, so they go.
        val other = "00000000-0000-4000-8000-000000000000"
        store.saveLogin("https://lan.example", login("t3", baseUrl = "https://renamed.example", instanceId = other))
        assertEquals(emptyList<String>(), store.current().alternateUrls)
        assertEquals(other, store.current().serverInstanceId)
    }

    @Test
    fun `an address check records a confirmed instance id and a null keeps what is recorded`() = runTest {
        store.saveLogin("https://lan.example", login("t1"))
        assertTrue(store.rememberAddress(store.current(), "https://ts.example", "tts.example", instance.uppercase()))
        assertEquals(instance, store.current().serverInstanceId)

        assertTrue(store.useAddress(store.current(), "https://ts.example", null, null))
        assertEquals(instance, store.current().serverInstanceId)
        assertEquals("tts.example", store.current().serverIdentity)
    }
}
