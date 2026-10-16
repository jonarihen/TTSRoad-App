package dk.perspektiva.ttsroad.data

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #437: the client half of `POST /api/mobile/session-proof`.
 *
 * The vector was computed outside this codebase with Python's `hashlib`/`hmac`, using the server's
 * own `hash_mobile_token` (`hashlib.sha256(token.encode("utf-8")).hexdigest()`, in
 * `app/services/mobile_auth.py`) as the key, exactly as the server contract specifies:
 *
 * ```python
 * key = hashlib.sha256(token.encode("utf-8")).hexdigest().encode("ascii")
 * mac = hmac.new(key, b"ttsroad-session-proof\x00" + nonce, hashlib.sha256).digest()
 * base64.urlsafe_b64encode(mac).rstrip(b"=")
 * ```
 *
 * The token deliberately contains non-ASCII characters, so a client hashing anything but UTF-8
 * fails here rather than in the field.
 */
class SessionProofTest {
    private val token = "ttsroad-test-token-æøå"
    private val nonce = ByteArray(32) { it.toByte() }

    @Test
    fun `the proof matches the server's algorithm`() {
        assertEquals("zKIdAItgyVMI9s2MaK2tM3m_XRWjYtSsyhsWnx3oy5E", SessionProof.expected(token, nonce))
    }

    @Test
    fun `the nonce is sent as unpadded base64url of the raw bytes`() {
        assertEquals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", SessionProof.encodeNonce(nonce))
    }

    @Test
    fun `a fresh nonce is 32 bytes and not repeated`() {
        val first = SessionProof.newNonce()
        val second = SessionProof.newNonce()
        assertEquals(32, first.size)
        assertFalse(first.contentEquals(second))
        assertEquals(32, Base64.getUrlDecoder().decode(SessionProof.encodeNonce(first)).size)
    }

    @Test
    fun `only the right proof for this token and this nonce matches`() {
        val proof = SessionProof.expected(token, nonce)

        assertTrue(SessionProof.matches(token, nonce, proof))
        assertFalse("another token", SessionProof.matches("other-token", nonce, proof))
        assertFalse("another nonce", SessionProof.matches(token, ByteArray(32) { 1 }, proof))
        assertFalse("padded is not what the server sends", SessionProof.matches(token, nonce, "$proof="))
        assertFalse(SessionProof.matches(token, nonce, null))
        assertFalse(SessionProof.matches(token, nonce, ""))
    }

    @Test
    fun `the key is the hex digest's characters, not its bytes`() {
        // A client that keyed the HMAC with the 32 raw digest bytes would produce this instead.
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply {
            init(javax.crypto.spec.SecretKeySpec(digest, "HmacSHA256"))
        }
        val wrong = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal("ttsroad-session-proof".toByteArray() + 0.toByte() + nonce))
        assertNotEquals(wrong, SessionProof.expected(token, nonce))
    }

    @Test
    fun `instance ids compare without case or surrounding space, and blank is absent`() {
        assertEquals(
            "3f0c9a52-7d1e-4f63-9a0b-2c4e8d6b1f70",
            normalizeInstanceId(" 3F0C9A52-7D1E-4F63-9A0B-2C4E8D6B1F70 "),
        )
        assertNull(normalizeInstanceId("  "))
        assertNull(normalizeInstanceId(null))
    }
}
