package dk.perspektiva.ttsroad.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ttsroad_session",
)

data class SessionState(
    val serverUrl: String = "",
    val token: String? = null,
    val username: String? = null,
    val isAdmin: Boolean = false,
    val serverName: String = "TTSRoad",
    /** Which mobile session this token is, so the devices screen can mark the row for this phone. */
    val deviceId: Int? = null,
    /**
     * When the token lapses if it goes unused, as the server wrote it.
     *
     * Kept for display only. Every authenticated request renews the expiry server-side, so a client
     * that watched this timestamp and pre-emptively signed out would be wrong far more often than
     * right.
     */
    val expiresAt: String? = null,
) {
    val isLoggedIn: Boolean
        get() = !serverUrl.isBlank() && !token.isNullOrBlank()

    val authorizationHeader: String?
        get() = token?.takeIf { it.isNotBlank() }?.let { "Bearer $it" }
}

/**
 * The slice of session storage the repository needs. Kept separate from [TokenStore] so
 * repository tests can substitute an in-memory store instead of a DataStore-backed one.
 */
interface SessionStore {
    suspend fun current(): SessionState
    suspend fun saveLogin(baseUrl: String, response: LoginResponse)
    suspend fun clearToken()
}

class TokenStore(
    private val context: Context,
    private val cipher: TokenCipher = KeystoreTokenCipher(),
) : SessionStore {
    private object Keys {
        val ServerUrl = stringPreferencesKey("server_url")
        val Token = stringPreferencesKey("token")
        val Username = stringPreferencesKey("username")
        val IsAdmin = booleanPreferencesKey("is_admin")
        val ServerName = stringPreferencesKey("server_name")
        val DeviceId = intPreferencesKey("device_id")
        val ExpiresAt = stringPreferencesKey("expires_at")
    }

    val session: Flow<SessionState> = context.sessionDataStore.data
        .catch { throwable ->
            if (throwable is IOException) {
                emit(emptyPreferences())
            } else {
                throw throwable
            }
        }
        .map { prefs ->
            SessionState(
                serverUrl = prefs[Keys.ServerUrl].orEmpty(),
                // Reads both shapes: an envelope, and a plaintext token written before encryption
                // existed. Null here means the stored token could not be opened at all — a key
                // wiped by a factory reset or a lock-screen change — which surfaces as "signed
                // out" rather than as a crash on the first frame.
                token = cipher.open(prefs[Keys.Token]),
                username = prefs[Keys.Username],
                isAdmin = prefs[Keys.IsAdmin] ?: false,
                serverName = prefs[Keys.ServerName] ?: "TTSRoad",
                deviceId = prefs[Keys.DeviceId],
                expiresAt = prefs[Keys.ExpiresAt],
            )
        }

    override suspend fun current(): SessionState = session.first()

    override suspend fun saveLogin(baseUrl: String, response: LoginResponse) {
        context.sessionDataStore.edit { prefs ->
            prefs[Keys.ServerUrl] = normalizeBaseUrl(baseUrl)
            // Sealing can fail on a device whose keystore refuses to generate a key. Storing the
            // raw token then is the lesser evil: the alternative is an app that cannot sign in at
            // all, and this is exactly what every build before 0.9.0 did anyway.
            prefs[Keys.Token] = cipher.seal(response.token) ?: response.token
            prefs[Keys.Username] = response.user.username
            prefs[Keys.IsAdmin] = response.user.isAdmin
            prefs[Keys.ServerName] = response.server?.name ?: "TTSRoad"
            // Absent on servers that predate the devices endpoints, so remove rather than write a
            // sentinel — a stale device id would mark the wrong row as "this device".
            response.deviceId?.let { prefs[Keys.DeviceId] = it } ?: prefs.remove(Keys.DeviceId)
            response.expiresAt?.let { prefs[Keys.ExpiresAt] = it } ?: prefs.remove(Keys.ExpiresAt)
        }
    }

    override suspend fun clearToken() {
        context.sessionDataStore.edit { prefs ->
            prefs.remove(Keys.Token)
            prefs.remove(Keys.Username)
            prefs.remove(Keys.IsAdmin)
            prefs.remove(Keys.DeviceId)
            prefs.remove(Keys.ExpiresAt)
        }
    }

    suspend fun clearAll() {
        context.sessionDataStore.edit { it.clear() }
    }

    /**
     * Re-write a token stored in plaintext by an older build as an envelope.
     *
     * Without this the plaintext would sit on disk until the next sign-in, which for a session that
     * renews on every request could be months — so the upgrade would fix nothing for exactly the
     * people who already have a token.
     *
     * A no-op when there is no token, when it is already sealed, or when sealing fails. Safe to
     * call on every launch.
     */
    suspend fun encryptStoredTokenIfNeeded() {
        context.sessionDataStore.edit { prefs ->
            val stored = prefs[Keys.Token] ?: return@edit
            if (isEncryptedToken(stored)) return@edit
            cipher.seal(stored)?.let { prefs[Keys.Token] = it }
        }
    }
}

fun normalizeBaseUrl(input: String): String {
    val trimmed = input.trim().trimEnd('/')
    require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        "Server URL must start with http:// or https://"
    }
    return "$trimmed/"
}

