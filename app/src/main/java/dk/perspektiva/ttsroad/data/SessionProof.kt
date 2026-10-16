package dk.perspektiva.ttsroad.data

import com.squareup.moshi.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * `POST /api/mobile/session-proof` (#437, server capability `session_proof`): a way to ask another
 * address whether it holds *this* session without presenting the token there.
 *
 * The server keeps only `sha256(token)` as lowercase hex, and the phone can compute the same string
 * from the token it holds. Both sides key an HMAC with it over a nonce the phone just chose; only a
 * server that has the session's row can answer with the right MAC. A server that does not — an
 * impostor repeating the real one's public `instance_id`, or the real one after the session was
 * revoked — answers the same shape with a proof that does not match, and the token stays home.
 *
 * The request is unauthenticated by design and is sent with [NoAuthHeader], so the bearer token
 * cannot ride along on it even by accident.
 */
data class SessionProofRequest(
    @param:Json(name = "device_id") val deviceId: Int,
    val nonce: String,
)

data class SessionProofResponse(
    val proof: String? = null,
    @param:Json(name = "instance_id") val instanceId: String? = null,
)

internal object SessionProof {
    /** 32 bytes, as the server contract suggests; it accepts 16–64. */
    const val NonceBytes = 32

    private const val ProofContext = "ttsroad-session-proof"
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun newNonce(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(NonceBytes).also(random::nextBytes)

    fun encodeNonce(nonce: ByteArray): String = encoder.encodeToString(nonce)

    /**
     * The proof a server holding [token]'s session must return for [nonce].
     *
     * The key is the 64 ASCII characters of the hex digest — exactly the string the server stores —
     * not the 32 bytes they spell. The message is the context label, one NUL, then the raw nonce
     * bytes (not their base64 form).
     */
    fun expected(token: String, nonce: ByteArray): String {
        val tokenHash = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(tokenHash.toByteArray(Charsets.US_ASCII), "HmacSHA256"))
        mac.update(ProofContext.toByteArray(Charsets.US_ASCII))
        mac.update(0.toByte())
        mac.update(nonce)
        return encoder.encodeToString(mac.doFinal())
    }

    /** Whether [proof] is the right answer for [token] and [nonce]. Constant-time in the proof. */
    fun matches(token: String, nonce: ByteArray, proof: String?): Boolean {
        if (proof.isNullOrEmpty()) return false
        val want = expected(token, nonce).toByteArray(Charsets.US_ASCII)
        return MessageDigest.isEqual(want, proof.toByteArray(Charsets.US_ASCII))
    }
}

/**
 * A server's `instance_id` in the one form the app compares: trimmed and lowercased (it is a UUID,
 * whose hex digits carry no case), or null when absent or blank — an older server.
 */
internal fun normalizeInstanceId(value: String?): String? = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
