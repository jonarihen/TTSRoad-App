package dk.perspektiva.ttsroad.data

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-disk format for the bearer token.
 *
 * Everything here is about not losing a session by accident. Getting the format wrong does not
 * crash — it signs someone out silently, months after the change, on a phone they were relying on
 * to keep playing overnight.
 */
class TokenEnvelopeTest {

    @Test
    fun `an envelope round-trips`() {
        val nonce = ByteArray(TokenNonceBytes) { it.toByte() }
        val ciphertext = byteArrayOf(9, 8, 7, 6, 5)

        val decoded = decodeTokenEnvelope(encodeTokenEnvelope(nonce, ciphertext))

        assertArrayEquals(nonce, decoded!!.nonce)
        assertArrayEquals(ciphertext, decoded.ciphertext)
    }

    @Test
    fun `a token written before encryption existed is recognised as plaintext`() {
        // The upgrade case. Treating a real token as a malformed envelope would sign the user out
        // the moment they installed 0.9.0.
        assertFalse(isEncryptedToken("abcdef0123456789"))
        assertNull(decodeTokenEnvelope("abcdef0123456789"))
    }

    @Test
    fun `an encrypted value is recognised as encrypted`() {
        val encoded = encodeTokenEnvelope(ByteArray(TokenNonceBytes), byteArrayOf(1, 2, 3))

        assertTrue(isEncryptedToken(encoded))
        assertTrue(encoded.startsWith(TokenEnvelopePrefix))
    }

    @Test
    fun `null is neither encrypted nor decodable`() {
        assertFalse(isEncryptedToken(null))
        assertNull(decodeTokenEnvelope(null))
    }

    @Test
    fun `corrupt input decodes to null rather than throwing`() {
        // This runs while restoring a session at startup. A throw here is a crash on the first
        // frame; null is "signed out", which is recoverable by signing back in.
        assertNull(decodeTokenEnvelope(TokenEnvelopePrefix + "not-base64!!!"))
        assertNull(decodeTokenEnvelope(TokenEnvelopePrefix))
    }

    @Test
    fun `a value too short to hold a nonce is rejected`() {
        val truncated = TokenEnvelopePrefix +
            Base64.getEncoder().encodeToString(ByteArray(TokenNonceBytes))

        // Exactly a nonce and nothing sealed under it cannot be a real envelope.
        assertNull(decodeTokenEnvelope(truncated))
    }

    @Test
    fun `the format is versioned so a future change is distinguishable`() {
        // If the prefix were unversioned, a later format would be fed to this decoder and produce
        // garbage rather than a clean "cannot read this".
        assertTrue(TokenEnvelopePrefix.startsWith("enc"))
        assertFalse(isEncryptedToken("enc2:whatever"))
    }

    @Test
    fun `a nonce full of high bytes survives encoding`() {
        // Base64 of signed bytes is an easy place to introduce a sign-extension bug.
        val nonce = ByteArray(TokenNonceBytes) { 0xFF.toByte() }
        val ciphertext = ByteArray(32) { 0x80.toByte() }

        val decoded = decodeTokenEnvelope(encodeTokenEnvelope(nonce, ciphertext))

        assertArrayEquals(nonce, decoded!!.nonce)
        assertArrayEquals(ciphertext, decoded.ciphertext)
    }

    @Test
    fun `envelopes compare by content, not identity`() {
        val a = TokenEnvelope(ByteArray(TokenNonceBytes), byteArrayOf(1, 2))
        val b = TokenEnvelope(ByteArray(TokenNonceBytes), byteArrayOf(1, 2))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
