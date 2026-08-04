package dk.perspektiva.ttsroad.data

import java.util.Base64

/**
 * How an encrypted token is written to disk, and how a token written by an older build is
 * recognised as not being one.
 *
 * Kept separate from the cipher so the format — the part a migration depends on and the part that
 * silently ruins a stored session if it is wrong — is plain JVM code with tests, rather than
 * something only reachable through the Android Keystore.
 *
 * `java.util.Base64` rather than `android.util.Base64`: it exists from API 26, which is this app's
 * `minSdk`, and it means these functions are testable without Robolectric.
 */

/**
 * Marks a stored value as ciphertext.
 *
 * Versioned from the start so a future format change can be told apart from this one instead of
 * being fed to the wrong decoder — the failure there is a session that cannot be read and a
 * sign-out the user did not ask for.
 */
internal const val TokenEnvelopePrefix = "enc1:"

/** Bytes of GCM nonce prepended to the ciphertext. 12 is what AES-GCM expects. */
internal const val TokenNonceBytes = 12

/** A decoded envelope: the nonce it was sealed with, and the sealed bytes. */
internal data class TokenEnvelope(val nonce: ByteArray, val ciphertext: ByteArray) {
    // ByteArray identity is referential, which would make every comparison in a test false.
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is TokenEnvelope &&
                    nonce.contentEquals(other.nonce) &&
                    ciphertext.contentEquals(other.ciphertext)
                )

    override fun hashCode(): Int = 31 * nonce.contentHashCode() + ciphertext.contentHashCode()
}

/**
 * Whether [stored] was written encrypted.
 *
 * Anything else is a token written by a build before encryption existed. It is still a valid
 * session and must keep working — refusing to read it would sign the user out on upgrade.
 */
internal fun isEncryptedToken(stored: String?): Boolean =
    stored != null && stored.startsWith(TokenEnvelopePrefix)

/** Serialise [nonce] and [ciphertext] into the single string DataStore holds. */
internal fun encodeTokenEnvelope(nonce: ByteArray, ciphertext: ByteArray): String =
    TokenEnvelopePrefix + Base64.getEncoder().encodeToString(nonce + ciphertext)

/**
 * Parse [stored] back into its parts, or null when it is not a well-formed envelope.
 *
 * Returns null rather than throwing for truncated, corrupt, or non-base64 input: this runs while
 * restoring a session, and the useful outcome of an unreadable token is "signed out", not a crash
 * at startup.
 */
internal fun decodeTokenEnvelope(stored: String?): TokenEnvelope? {
    if (!isEncryptedToken(stored)) return null
    val body = stored!!.removePrefix(TokenEnvelopePrefix)
    val bytes = runCatching { Base64.getDecoder().decode(body) }.getOrNull() ?: return null
    // Needs a full nonce and at least one byte sealed under it; GCM also appends a 16-byte tag, so
    // anything at or below the nonce length cannot be a real envelope.
    if (bytes.size <= TokenNonceBytes) return null
    return TokenEnvelope(
        nonce = bytes.copyOfRange(0, TokenNonceBytes),
        ciphertext = bytes.copyOfRange(TokenNonceBytes, bytes.size),
    )
}
