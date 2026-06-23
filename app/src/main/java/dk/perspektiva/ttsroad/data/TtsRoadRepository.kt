package dk.perspektiva.ttsroad.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class TtsRoadRepository(private val tokenStore: TokenStore) {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        deviceName: String,
    ): LoginResponse = withContext(Dispatchers.IO) {
        val normalized = normalizeBaseUrl(baseUrl)
        val response = api(normalized, token = null).login(
            LoginRequest(
                username = username.trim(),
                password = password,
                deviceName = deviceName.trim().ifBlank { "Android" },
            ),
        )
        tokenStore.saveLogin(normalized, response)
        response
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        val session = tokenStore.current()
        runCatching {
            if (session.isLoggedIn) {
                api(session.serverUrl, session.token).logout()
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
        api(session.serverUrl, session.token).saveProgress(
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
            block(api(session.serverUrl, session.token))
        }

    private fun api(baseUrl: String, token: String?): TtsRoadApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                if (!token.isNullOrBlank()) {
                    builder.header("Authorization", "Bearer $token")
                }
                chain.proceed(builder.build())
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(normalizeBaseUrl(baseUrl))
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(TtsRoadApi::class.java)
    }
}

