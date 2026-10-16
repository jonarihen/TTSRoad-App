package dk.perspektiva.ttsroad.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TokenStoreSealingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val warnings = mutableListOf<String>()

    /** Seals by prefixing, after refusing the first [failures] attempts. */
    private class FlakyCipher(private var failures: Int) : TokenCipher {
        var attempts = 0
        override fun seal(plaintext: String): String? {
            attempts++
            if (failures > 0) {
                failures--
                return null
            }
            return "${TokenEnvelopePrefix}sealed-$plaintext"
        }

        override fun open(stored: String?): String? = stored?.removePrefix("${TokenEnvelopePrefix}sealed-")
    }

    private fun login(token: String) = LoginResponse(token = token, user = MobileUser(id = 1, username = "admin"))

    @After
    fun clear() = runTest { TokenStore(context, FlakyCipher(0)).clearAll() }

    @Test
    fun `a transient sealing failure is retried and the token is stored sealed`() = runTest {
        val cipher = FlakyCipher(failures = 1)
        val store = TokenStore(context, cipher) { warnings += it }

        store.saveLogin("https://tts.example", login("secret-token"))

        assertEquals(2, cipher.attempts)
        assertEquals("secret-token", store.current().token)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a persistent sealing failure stores plaintext and warns without the token`() = runTest {
        val cipher = FlakyCipher(failures = Int.MAX_VALUE)
        val store = TokenStore(context, cipher) { warnings += it }

        store.saveLogin("https://tts.example", login("secret-token"))

        assertEquals(2, cipher.attempts)
        // Still signed in: refusing would leave Android Auto with no token to stream with.
        assertEquals("secret-token", store.current().token)
        assertEquals(1, warnings.size)
        assertFalse(warnings.single().contains("secret-token"))
    }

    @Test
    fun `a plaintext token left by a failed seal is re-sealed on the next launch`() = runTest {
        TokenStore(context, FlakyCipher(failures = Int.MAX_VALUE)) { warnings += it }
            .saveLogin("https://tts.example", login("secret-token"))

        val cipher = FlakyCipher(failures = 0)
        val relaunched = TokenStore(context, cipher) { warnings += it }
        relaunched.encryptStoredTokenIfNeeded()

        assertEquals(1, cipher.attempts)
        assertEquals("secret-token", relaunched.current().token)
        // Sealing again is a no-op once the stored value is an envelope.
        relaunched.encryptStoredTokenIfNeeded()
        assertEquals(1, cipher.attempts)
    }
}
