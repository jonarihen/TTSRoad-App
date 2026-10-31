package dk.perspektiva.ttsroad.data

import androidx.annotation.VisibleForTesting
import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
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
import dk.perspektiva.ttsroad.download.DownloadCacheKeys
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
    /**
     * Other addresses of this same server, each checked before it was saved (#372). Never sent the
     * token while they sit here: [serverUrl] is the one origin requests are authenticated on, and a
     * saved address is checked again before it can become that. See `ServerAddresses.kt`.
     */
    val alternateUrls: List<String> = emptyList(),
    /**
     * The identity the server reports about itself — its configured `base_url`, in the form
     * `DownloadCacheKeys.serverIdentity` gives it — recorded at sign-in or at the last address check.
     * What another address has to match before the token goes there. Null for a session signed in
     * before this existed, or on a server that does not report one.
     */
    val serverIdentity: String? = null,
    /**
     * The server's random, persisted `instance_id` (#437), recorded at sign-in or at the last
     * address check. Preferred over [serverIdentity] when checking another address, because unlike
     * `base_url` it is not configuration two installs can share. Null for a session signed in to a
     * server before 1.5.7, or before this was recorded.
     */
    val serverInstanceId: String? = null,
    /** The account's id as the server numbered it at sign-in, when known. */
    val userId: Int? = null,
) {
    /** Every address this session may be reached on: the one in use first, then the saved ones. */
    val knownAddresses: List<String>
        get() = listOf(serverUrl).filter { it.isNotBlank() } + alternateUrls

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

    /**
     * Put [address] in use for [owner]'s session, keeping its token, and record [serverIdentity] and
     * [serverInstanceId] — each only when non-null; null keeps what is recorded. The address being
     * left joins [SessionState.alternateUrls].
     *
     * Returns false and changes nothing unless [owner] is still the live session — a sign-out or a
     * different sign-in while the address was being checked must not have its address replaced.
     * The caller has already checked [address]; this only stores the result.
     */
    suspend fun useAddress(
        owner: SessionState,
        address: String,
        serverIdentity: String?,
        serverInstanceId: String? = null,
    ): Boolean = false

    /** As [useAddress], but only saves [address] among the alternates; the address in use stays. */
    suspend fun rememberAddress(
        owner: SessionState,
        address: String,
        serverIdentity: String?,
        serverInstanceId: String? = null,
    ): Boolean = false

    /** Drop [address] from the saved alternates. Never touches the address in use. */
    suspend fun forgetAddress(address: String) = Unit
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
        val AlternateUrls = stringPreferencesKey("alternate_urls")
        val ServerIdentity = stringPreferencesKey("server_identity")
        val UserId = intPreferencesKey("user_id")
        val ServerInstanceId = stringPreferencesKey("server_instance_id")
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
                alternateUrls = decodeAlternates(prefs[Keys.AlternateUrls]),
                serverIdentity = prefs[Keys.ServerIdentity],
                serverInstanceId = prefs[Keys.ServerInstanceId],
                userId = prefs[Keys.UserId],
            )
        }

    override suspend fun current(): SessionState = session.first()

    /** Whether any token is stored, opened or not — a Keystore failure leaves one here (#448). */
    suspend fun hasStoredToken(): Boolean =
        context.sessionDataStore.data.first()[Keys.Token] != null

    override suspend fun saveLogin(baseUrl: String, response: LoginResponse): Unit = sessionMutation.withLock {
        context.sessionDataStore.edit { prefs ->
            val address = normalizeBaseUrl(baseUrl)
            val reported = DownloadCacheKeys.serverIdentity(response.server?.baseUrl)
            val previousIdentity = prefs[Keys.ServerIdentity]
            val reportedInstance = normalizeInstanceId(response.server?.instanceId)
            val previousInstance = prefs[Keys.ServerInstanceId]
            // Saved addresses were confirmed to be this server. They survive signing in again at
            // the same address (an expired token, a password change) unless the server now names
            // itself differently; signing in anywhere else starts the list over, so another
            // server's addresses are never offered here. The account is not part of it: no saved
            // address is sent a token without being checked again, as whoever is signed in then.
            // Two instance ids settle it on their own (#437): a server whose BASE_URL was corrected
            // is still the same server, and one reinstalled behind the same BASE_URL is not.
            val sameServer = if (reportedInstance != null && previousInstance != null) {
                reportedInstance == previousInstance
            } else {
                reported == null || previousIdentity == null || reported == previousIdentity
            }
            val keepAddresses = sameServerAddress(prefs[Keys.ServerUrl], address) && sameServer
            if (!keepAddresses) prefs.remove(Keys.AlternateUrls)
            val identity = reported ?: previousIdentity?.takeIf { keepAddresses }
            identity?.let { prefs[Keys.ServerIdentity] = it } ?: prefs.remove(Keys.ServerIdentity)
            val instance = reportedInstance ?: previousInstance?.takeIf { keepAddresses }
            instance?.let { prefs[Keys.ServerInstanceId] = it } ?: prefs.remove(Keys.ServerInstanceId)
            prefs[Keys.UserId] = response.user.id
            prefs[Keys.ServerUrl] = address
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
            prefs.remove(Keys.UserId)
        }
    }

    override suspend fun useAddress(
        owner: SessionState,
        address: String,
        serverIdentity: String?,
        serverInstanceId: String?,
    ): Boolean =
        editOwnedSession(owner) { prefs, live ->
            val next = normalizeBaseUrl(address)
            prefs[Keys.AlternateUrls] = encodeAlternates(alternatesAfterSwitch(live.serverUrl, live.alternateUrls, next))
            prefs[Keys.ServerUrl] = next
            recordIdentity(prefs, serverIdentity, serverInstanceId)
        }

    override suspend fun rememberAddress(
        owner: SessionState,
        address: String,
        serverIdentity: String?,
        serverInstanceId: String?,
    ): Boolean =
        editOwnedSession(owner) { prefs, live ->
            val added = normalizeBaseUrl(address)
            prefs[Keys.AlternateUrls] = encodeAlternates(alternatesAfterRemember(live.serverUrl, live.alternateUrls, added))
            recordIdentity(prefs, serverIdentity, serverInstanceId)
        }

    private fun recordIdentity(prefs: MutablePreferences, serverIdentity: String?, serverInstanceId: String?) {
        serverIdentity?.let { prefs[Keys.ServerIdentity] = it }
        normalizeInstanceId(serverInstanceId)?.let { prefs[Keys.ServerInstanceId] = it }
    }

    override suspend fun forgetAddress(address: String): Unit = sessionMutation.withLock {
        context.sessionDataStore.edit { prefs ->
            val remaining = alternatesAfterForget(decodeAlternates(prefs[Keys.AlternateUrls]), address)
            if (remaining.isEmpty()) prefs.remove(Keys.AlternateUrls) else prefs[Keys.AlternateUrls] = encodeAlternates(remaining)
        }
    }

    /** Runs [change] only while [owner] is still the live session, under the same lock as sign-in. */
    private suspend fun editOwnedSession(
        owner: SessionState,
        change: (MutablePreferences, SessionState) -> Unit,
    ): Boolean = sessionMutation.withLock {
        val live = current()
        if (!live.isLoggedIn || !live.isSameSession(owner)) return@withLock false
        context.sessionDataStore.edit { change(it, live) }
        true
    }

    /** Test fixture reset; sign-out goes through the session-owned paths above. */
    @VisibleForTesting
    internal suspend fun clearAll(): Unit = sessionMutation.withLock {
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
 * [normalizeBaseUrl], but only once the host could be a real one — the gate on the login screen's
 * capability probe, so obviously incomplete input (`https://`, `https://home.`, `https://192.168.1`,
 * a port still missing its digits) never becomes a lookup.
 *
 * Accepted: a dotted name whose last label is at least two letters, an IPv4 or bracketed IPv6
 * literal, and a single-label LAN name such as `nas` or `localhost` (#513). Single labels used to
 * be refused because `https://n` on its way to `https://nas` looks the same as a finished name; the
 * login screen's debounce now keeps those keystrokes from becoming DNS lookups instead. A label of
 * digits alone is still refused, since that is an IPv4 address being typed.
 */
fun probeableBaseUrl(input: String): String? {
    val normalized = runCatching { normalizeBaseUrl(input) }.getOrNull() ?: return null
    val url = normalized.toHttpUrlOrNull() ?: return null
    val authority = normalized.substringAfter("://").substringBefore('/').substringBefore('?')
    if (authority.endsWith(":")) return null
    val host = url.host
    val complete = host.contains(':') || // IPv6; okhttp has already validated it
        Ipv4.matches(host) ||
        CompleteDomain.matches(host) ||
        SingleLabelHost.matches(host)
    return normalized.takeIf { complete }
}

private val LeadingScheme = Regex("^(https?)://", RegexOption.IGNORE_CASE)
private val OtherScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
private val Ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
private val CompleteDomain = Regex("^([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+[a-z]([a-z0-9-]*[a-z0-9])$")
private val SingleLabelHost = Regex("^(?=[a-z0-9-]*[a-z])[a-z0-9]([a-z0-9-]*[a-z0-9])?$")

