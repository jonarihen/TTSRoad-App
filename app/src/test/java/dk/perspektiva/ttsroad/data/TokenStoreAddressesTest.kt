package dk.perspektiva.ttsroad.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
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

    private fun login(token: String, username: String = "admin", baseUrl: String? = "https://tts.example") =
        LoginResponse(
            token = token,
            user = MobileUser(id = 1, username = username),
            server = ServerInfo(baseUrl = baseUrl),
        )

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
}
