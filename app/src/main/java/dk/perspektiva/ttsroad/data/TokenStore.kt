package dk.perspektiva.ttsroad.data

import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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

    /**
     * Whether [other] is this same session: the same server and the same token (#318).
     *
     * The one comparison behind every "is this still the account that started it?" check, so a field
     * added to what identifies a session is added here once rather than at each of them. It says
     * nothing about being signed in — callers that need that combine it with [isLoggedIn].
     */
    fun isSameSession(other: SessionState?): Boolean =
        other != null && serverUrl == other.serverUrl && token == other.token

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
    /** Where a sealing failure is reported. Never handed the token. A parameter so tests can see it. */
    private val warn: (String) -> Unit = { Log.w(LogTag, it) },
) : SessionStore {
    private val sessionMutation = Mutex()

    internal suspend fun <T> withCurrentSession(owner: SessionState, block: () -> T): T? = sessionMutation.withLock {
        val live = current()
        if (!live.isLoggedIn || !live.isSameSession(owner)) return@withLock null
        block()
    }

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

    override suspend fun saveLogin(baseUrl: String, response: LoginResponse): Unit = sessionMutation.withLock {
        context.sessionDataStore.edit { prefs ->
            prefs[Keys.ServerUrl] = normalizeBaseUrl(baseUrl)
            prefs[Keys.Token] = sealForStorage(response.token)
            prefs[Keys.Username] = response.user.username
            prefs[Keys.IsAdmin] = response.user.isAdmin
            prefs[Keys.ServerName] = response.server?.name ?: "TTSRoad"
            // Absent on servers that predate the devices endpoints, so remove rather than write a
            // sentinel — a stale device id would mark the wrong row as "this device".
            response.deviceId?.let { prefs[Keys.DeviceId] = it } ?: prefs.remove(Keys.DeviceId)
            response.expiresAt?.let { prefs[Keys.ExpiresAt] = it } ?: prefs.remove(Keys.ExpiresAt)
        }
    }

    /**
     * The value to write for [token]: an envelope, or — only after sealing has failed twice — the
     * plaintext token.
     *
     * Policy when the Keystore will not seal:
     * - **Retry once.** Keystore failures are often transient (the keystore daemon busy just after
     *   boot, a key generation that races another), and a second attempt costs nothing.
     * - **Then store plaintext, and say so in the log.** Keeping the token in memory only is not an
     *   option: playback is started by Android Auto and the media notification in a process that
     *   may have been reaped since sign-in, and it needs the stored token to stream. Refusing to
     *   sign in at all would be worse still. Plaintext is exactly what every build before 0.9.0
     *   stored, and it is not permanent: [encryptStoredTokenIfNeeded] re-seals it on every launch.
     *
     * The warning names the failure, never the token.
     */
    private fun sealForStorage(token: String): String {
        repeat(SealAttempts) { cipher.seal(token)?.let { return it } }
        warn("Keystore could not seal the session token; storing it unencrypted until the next launch re-seals it")
        return token
    }

    override suspend fun clearToken(): Unit = sessionMutation.withLock {
        context.sessionDataStore.edit { prefs ->
            prefs.remove(Keys.Token)
            prefs.remove(Keys.Username)
            prefs.remove(Keys.IsAdmin)
            prefs.remove(Keys.DeviceId)
            prefs.remove(Keys.ExpiresAt)
        }
    }

    suspend fun clearAll(): Unit = sessionMutation.withLock {
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
                ?: warn("Keystore could not seal the stored session token; it stays unencrypted until the next launch")
        }
    }

    private companion object {
        const val LogTag = "TokenStore"
        const val SealAttempts = 2
    }
}

/**
 * The server address as typed or pasted, made into the form every other part of the app compares.
 *
 * Forgiving about what people actually enter (#361): surrounding whitespace, a scheme capitalised by
 * the keyboard (`Https://`), a pasted address landing after a scheme already in the field
 * (`https://https://home.example.dk` — the pasted, inner scheme wins), a bare host with no scheme at
 * all (assumed `https://`), and any number of trailing slashes. Anything that is not http(s) is
 * still refused, with wording the login screen shows as is.
 */
fun normalizeBaseUrl(input: String): String {
    var rest = input.trim()
    var scheme: String? = null
    while (true) {
        val match = LeadingScheme.find(rest) ?: break
        scheme = match.groupValues[1].lowercase()
        rest = rest.substring(match.value.length).trimStart()
    }
    require(scheme != null || !OtherScheme.containsMatchIn(rest)) {
        "Server URL must start with http:// or https://"
    }
    val address = rest.trimEnd('/')
    require(address.isNotEmpty() && !address.startsWith("/")) {
        "Enter the server's address, like https://ttsroad.example.com"
    }
    return "${scheme ?: "https"}://$address/"
}

/**
 * [normalizeBaseUrl], but only once the host looks finished — the gate on the login screen's
 * capability probe, so half-typed hostnames (`https://ho`, `https://home.`) never become DNS lookups.
 *
 * "Finished" means a dotted name whose last label is at least two letters, an IPv4 or bracketed IPv6
 * literal, or `localhost`. A single-label LAN name such as `nas` is not probed, but signing in with
 * it still works; only the early "found TTSRoad x.y" hint is skipped.
 */
fun probeableBaseUrl(input: String): String? {
    val normalized = runCatching { normalizeBaseUrl(input) }.getOrNull() ?: return null
    val url = normalized.toHttpUrlOrNull() ?: return null
    val authority = normalized.substringAfter("://").substringBefore('/').substringBefore('?')
    if (authority.endsWith(":")) return null
    val host = url.host
    val complete = host == "localhost" ||
        host.contains(':') || // IPv6; okhttp has already validated it
        Ipv4.matches(host) ||
        CompleteDomain.matches(host)
    return normalized.takeIf { complete }
}

private val LeadingScheme = Regex("^(https?)://", RegexOption.IGNORE_CASE)
private val OtherScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
private val Ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
private val CompleteDomain = Regex("^([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+[a-z]([a-z0-9-]*[a-z0-9])$")

