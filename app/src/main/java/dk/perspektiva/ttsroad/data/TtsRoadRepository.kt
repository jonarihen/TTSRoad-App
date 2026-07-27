package dk.perspektiva.ttsroad.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
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

class TtsRoadRepository(private val tokenStore: TokenStore) {
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
            val builder = chain.request().newBuilder()
            authHeader?.let { builder.header("Authorization", it) }
            chain.proceed(builder.build())
        }
        .build()

    /**
     * The shared client, for non-Retrofit callers that must talk to the same server — currently
     * Coil, which needs the bearer token to load cover art. Reuses this client's connection pool.
     */
    val httpClient: OkHttpClient
        get() = client

    private val apiCache = HashMap<String, TtsRoadApi>()

    suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        deviceName: String,
        totpCode: String? = null,
    ): LoginResult = withContext(Dispatchers.IO) {
        val normalized = normalizeBaseUrl(baseUrl)
        authHeader = null
        try {
            val response = api(normalized).login(
                LoginRequest(
                    username = username.trim(),
                    password = password,
                    deviceName = deviceName.trim().ifBlank { "Android" },
                    totpCode = totpCode?.trim()?.ifBlank { null },
                ),
            )
            tokenStore.saveLogin(normalized, response)
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
        val session = tokenStore.current()
        if (!session.isLoggedIn) return@withContext null
        authHeader = session.authorizationHeader
        api(session.serverUrl).saveProgress(
            PlaybackProgressRequest(
                fictionId = fictionId,
                chapterId = chapterId,
                positionSeconds = positionSeconds.coerceAtLeast(0.0),
                isPlayed = isPlayed,
            ),
        )
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
        withContext(Dispatchers.IO) {
            val session = tokenStore.current()
            require(session.isLoggedIn) { "Not logged in" }
            authHeader = session.authorizationHeader
            block(api(session.serverUrl))
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

