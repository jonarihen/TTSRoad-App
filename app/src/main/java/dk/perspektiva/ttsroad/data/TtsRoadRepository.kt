package dk.perspektiva.ttsroad.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/** Outcome of a mobile login attempt. */
sealed interface LoginResult {
    data object Success : LoginResult
    /** Password was accepted but a valid 2FA code is required; resubmit with [totpCode]. */
    data object TotpRequired : LoginResult
    data class Failure(val message: String) : LoginResult
}

class TtsRoadRepository(private val tokenStore: SessionStore) {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    // One shared client (and one Retrofit per base URL) so connections, the TLS
    // session, and thread pools are reused across calls — a new OkHttpClient per
    // request would force a fresh handshake every time (e.g. each progress save).
    @Volatile
    private var authHeader: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            // Capability discovery opts out: it runs against a URL the user is still typing, and
            // handing an arbitrary host the bearer token for the last server would leak it.
            if (request.header(NoAuthHeader) != null) {
                chain.proceed(request.newBuilder().removeHeader(NoAuthHeader).build())
            } else {
                val builder = request.newBuilder()
                authHeader?.let { builder.header("Authorization", it) }
                chain.proceed(builder.build())
            }
        }
        .build()

    private val apiCache = HashMap<String, TtsRoadApi>()

    private val _sessionExpired = MutableStateFlow(false)

    /**
     * True once an authenticated call came back 401 and the stored token was dropped, so the
     * login screen can say why it is being shown. Cleared by a successful [login].
     */
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        deviceName: String,
        totpCode: String? = null,
    ): LoginResult = withContext(Dispatchers.IO) {
        authHeader = null
        try {
            // Inside the try: normalizeBaseUrl throws on a missing http:// or https://
            // scheme, and that is a user-correctable typo, not a crash.
            val normalized = normalizeBaseUrl(baseUrl)
            val response = api(normalized).login(
                LoginRequest(
                    username = username.trim(),
                    password = password,
                    deviceName = deviceName.trim().ifBlank { "Android" },
                    totpCode = totpCode?.trim()?.ifBlank { null },
                ),
            )
            tokenStore.saveLogin(normalized, response)
            _sessionExpired.value = false
            LoginResult.Success
        } catch (e: HttpException) {
            val body = e.response()?.errorBody()?.string()
            if (e.code() == 401 && body?.contains("totp_required") == true) {
                LoginResult.TotpRequired
            } else {
                LoginResult.Failure(parseDetailMessage(body) ?: "Invalid username or password")
            }
        } catch (e: Exception) {
            LoginResult.Failure(e.message ?: "Login failed")
        }
    }

    /** Pull a human-readable message out of FastAPI's `{"detail": ...}` error body. */
    private fun parseDetailMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            val parsed = moshi.adapter(Any::class.java).fromJson(body) as? Map<*, *>
            when (val detail = parsed?.get("detail")) {
                is String -> detail
                is Map<*, *> -> detail["message"] as? String
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        val session = tokenStore.current()
        runCatching {
            if (session.isLoggedIn) {
                authHeader = session.authorizationHeader
                api(session.serverUrl).logout()
            }
        }
        tokenStore.clearToken()
    }

    /**
     * Ask a server what optional features it has.
     *
     * A `404` is the documented answer from a server built before discovery existed, and comes
     * back as [ServerCapabilities.Baseline] rather than an error — that pairing is exactly what
     * this endpoint exists to make safe. Everything else (unreachable host, TLS failure, a body
     * that is not JSON) still throws, so callers can tell an old server from no server.
     */
    suspend fun capabilities(baseUrl: String): ServerCapabilities = withContext(Dispatchers.IO) {
        try {
            ServerCapabilities.from(api(normalizeBaseUrl(baseUrl)).capabilities())
        } catch (e: HttpException) {
            if (e.code() == 404) ServerCapabilities.Baseline else throw e
        }
    }

    suspend fun library(): LibraryResponse = withAuthorizedApi { it.library() }

    suspend fun chapters(
        fictionId: Int,
        playableOnly: Boolean = false,
        includeExcluded: Boolean = false,
    ): ChaptersResponse = withAuthorizedApi {
        it.chapters(
            fictionId = fictionId,
            playableOnly = playableOnly,
            includeExcluded = includeExcluded,
        )
    }

    suspend fun saveProgress(
        fictionId: Int,
        chapterId: Int,
        positionSeconds: Double,
        isPlayed: Boolean,
    ): PlaybackProgressResponse? = withContext(Dispatchers.IO) {
        // Progress saves fire in the background; a missing token is not worth an exception.
        if (!tokenStore.current().isLoggedIn) return@withContext null
        authorized {
            it.saveProgress(
                PlaybackProgressRequest(
                    fictionId = fictionId,
                    chapterId = chapterId,
                    positionSeconds = positionSeconds.coerceAtLeast(0.0),
                    isPlayed = isPlayed,
                ),
            )
        }
    }

    suspend fun markPlayed(chapterIds: List<Int>, played: Boolean): PlaybackMarkResponse =
        withAuthorizedApi {
            it.markPlayback(
                PlaybackMarkRequest(
                    chapterIds = chapterIds,
                    played = played,
                ),
            )
        }

    private suspend fun <T> withAuthorizedApi(block: suspend (TtsRoadApi) -> T): T =
        withContext(Dispatchers.IO) { authorized(block) }

    /**
     * Runs an authenticated call, turning a server-side 401 into a forced sign-out.
     *
     * A 401 on an authenticated endpoint means the stored token is no longer valid — revoked
     * from another device, pruned, or the server database was reset — so retrying can never
     * succeed. Dropping the token lets the session observer in `TtsRoadApp` fall back to the
     * login screen (which also stops playback) instead of every screen showing "HTTP 401
     * Unauthorized" until the user finds Settings > Sign out.
     *
     * [login] deliberately does not go through here: it answers 401 for a wrong password and
     * for `totp_required`, neither of which should clear a stored session.
     */
    private suspend fun <T> authorized(block: suspend (TtsRoadApi) -> T): T {
        val session = tokenStore.current()
        require(session.isLoggedIn) { "Not logged in" }
        authHeader = session.authorizationHeader
        return try {
            block(api(session.serverUrl))
        } catch (e: HttpException) {
            if (e.code() == 401) {
                authHeader = null
                tokenStore.clearToken()
                _sessionExpired.value = true
            }
            throw e
        }
    }

    private fun api(baseUrl: String): TtsRoadApi {
        val normalized = normalizeBaseUrl(baseUrl)
        return synchronized(apiCache) {
            apiCache.getOrPut(normalized) {
                Retrofit.Builder()
                    .baseUrl(normalized)
                    .client(client)
                    .addConverterFactory(MoshiConverterFactory.create(moshi))
                    .build()
                    .create(TtsRoadApi::class.java)
            }
        }
    }
}
