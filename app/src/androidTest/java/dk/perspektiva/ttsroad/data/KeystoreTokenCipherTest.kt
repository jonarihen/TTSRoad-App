package dk.perspektiva.ttsroad.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real cipher, against the real Android Keystore — which only exists on a device, so this
 * cannot live in `app/src/test`. The format around it is covered on the JVM by [TokenEnvelopeTest];
 * what is only checkable here is that a key can actually be generated and used.
 *
 * Run with the rest of the instrumented suite:
 *
 * ```
 * ./gradlew connectedAndroidTest -PttsroadTestBuildType=release
 * ```
 */
@RunWith(AndroidJUnit4::class)
class KeystoreTokenCipherTest {

    private val cipher = KeystoreTokenCipher()

    @Test
    fun aSealedTokenOpensBackToItself() {
        val token = "a-bearer-token-value-0123456789"

        val sealed = cipher.seal(token)

        assertTrue("sealing failed outright", sealed != null)
        assertEquals(token, cipher.open(sealed))
    }

    @Test
    fun theStoredFormIsNotTheToken() {
        // The whole point: what lands on disk must not be readable as the credential.
        val token = "a-bearer-token-value-0123456789"

        val sealed = cipher.seal(token)!!

        assertNotEquals(token, sealed)
        assertTrue("not an envelope: $sealed", isEncryptedToken(sealed))
        assertTrue("token leaked into the envelope", !sealed.contains(token))
    }

    @Test
    fun sealingTwiceProducesDifferentCiphertext() {
        // A fresh GCM nonce per seal. Identical output would leak that the token was unchanged
        // across writes, and reusing a nonce with the same key breaks GCM outright.
        val token = "a-bearer-token-value-0123456789"

        assertNotEquals(cipher.seal(token), cipher.seal(token))
    }

    @Test
    fun aPlaintextTokenFromAnOlderBuildIsPassedThrough() {
        // The upgrade path. Refusing this would sign out everyone who already had a session.
        val legacy = "written-before-encryption-existed"

        assertEquals(legacy, cipher.open(legacy))
    }

    @Test
    fun anUnreadableEnvelopeOpensToNullRatherThanThrowing() {
        // What a wiped or rotated key looks like. "Signed out" is recoverable; a crash at startup
        // on every launch is not.
        assertNull(cipher.open(TokenEnvelopePrefix + "AAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
        assertNull(cipher.open(TokenEnvelopePrefix + "not-base64!!!"))
    }

    @Test
    fun openingNothingIsNull() {
        assertNull(cipher.open(null))
    }
}
