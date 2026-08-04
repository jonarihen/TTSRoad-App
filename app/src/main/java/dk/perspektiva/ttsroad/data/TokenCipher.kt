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

    override fun seal(plaintext: String): String? = runCatching {
        val cipher = Cipher.getInstance(Transformation)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        encodeTokenEnvelope(cipher.iv, cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }.getOrNull()

    override fun open(stored: String?): String? {
        if (stored == null) return null
        // A token from a build before encryption. Still valid — reading it is what stops an upgrade
        // silently signing the user out. It is replaced with an envelope on the next write.
        if (!isEncryptedToken(stored)) return stored
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
