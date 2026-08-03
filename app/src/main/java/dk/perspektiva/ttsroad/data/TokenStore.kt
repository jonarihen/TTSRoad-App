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

class TokenStore(private val context: Context) : SessionStore {
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
                token = prefs[Keys.Token],
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
            prefs[Keys.Token] = response.token
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
}

fun normalizeBaseUrl(input: String): String {
    val trimmed = input.trim().trimEnd('/')
    require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        "Server URL must start with http:// or https://"
    }
    return "$trimmed/"
}

