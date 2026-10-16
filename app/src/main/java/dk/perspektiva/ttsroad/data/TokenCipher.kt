package dk.perspektiva.ttsroad.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals and opens the bearer token.
 *
 * An interface so [TokenStore] can be tested without the Android Keystore, which does not exist on
 * the JVM.
 */
interface TokenCipher {
    /** Seal [plaintext] for storage, or return null if sealing is not possible on this device. */
    fun seal(plaintext: String): String?

    /**
     * Open [stored], which may be an envelope or a token written before encryption existed.
     *
     * Returns null only when the value was encrypted and cannot be read — a key wiped by a factory
     * reset, a lock-screen change, or a restore onto another device.
     */
    fun open(stored: String?): String?
}

/**
 * Real implementation, backed by an AES-GCM key held in the Android Keystore.
 *
 * The key never leaves the keystore and is not extractable, so the token on disk is unreadable to
 * anything that merely gets at the app's files — which was the exposure: a ~90-day credential
 * sitting in plaintext in a DataStore.
 *
 * Deliberately **not** tied to device unlock. Playback starts from Android Auto and from the media
 * notification with the phone locked, and the service needs the token to stream. Requiring
 * authentication would trade a real feature for protection against an attacker who already has an
 * unlocked device.
 */
class KeystoreTokenCipher : TokenCipher {

    private val lastOpened = LastOpenedToken(::decrypt)

    override fun seal(plaintext: String): String? = runCatching {
        val cipher = Cipher.getInstance(Transformation)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        encodeTokenEnvelope(cipher.iv, cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }.getOrNull()?.also { lastOpened.remember(it, plaintext) }

    override fun open(stored: String?): String? {
        if (stored == null) return null
        // A token from a build before encryption. Still valid — reading it is what stops an upgrade
        // silently signing the user out. It is replaced with an envelope on the next write.
        if (!isEncryptedToken(stored)) return stored
        return lastOpened.open(stored)
    }

    private fun decrypt(stored: String): String? {
        val envelope = decodeTokenEnvelope(stored) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(Transformation)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TagBits, envelope.nonce))
            String(cipher.doFinal(envelope.ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    /** The app's key, generated on first use. */
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(Provider).apply { load(null) }
        (keyStore.getEntry(KeyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, Provider)
        generator.init(
            KeyGenParameterSpec.Builder(
                KeyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val Provider = "AndroidKeyStore"
        const val KeyAlias = "ttsroad_session_token"
        const val Transformation = "AES/GCM/NoPadding"
        const val TagBits = 128
    }
}

/**
 * Remembers the last envelope that was opened and what it opened to.
 *
 * Every API call and every cover image reads the session, and each read opens the stored token:
 * without this, a library grid of fifty covers is fifty Android Keystore round trips for the same
 * unchanged value. The stored value is the cache key, so a new sign-in (a new envelope with a fresh
 * nonce) or a sign-out (no value at all) is never answered from a stale entry.
 *
 * Only successful opens are remembered: a value that could not be opened is tried again next time,
 * in case the keystore failure was transient. A failure is also retried once on the spot, as a seal
 * is — a one-off Keystore hiccup right after boot would otherwise read as signed out (#448).
 */
internal class LastOpenedToken(private val decrypt: (String) -> String?) {
    private class Entry(val stored: String, val plaintext: String)

    // One immutable entry behind a volatile reference: concurrent readers see either the old pair
    // or the new one, never a key from one and a value from the other.
    @Volatile private var entry: Entry? = null

    fun open(stored: String): String? {
        entry?.takeIf { it.stored == stored }?.let { return it.plaintext }
        return (decrypt(stored) ?: decrypt(stored))?.also { entry = Entry(stored, it) }
    }

    /** Record a value just sealed, so the first read after sign-in does not decrypt it again. */
    fun remember(stored: String, plaintext: String) {
        entry = Entry(stored, plaintext)
    }
}
