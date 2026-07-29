package dk.perspektiva.ttsroad.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Marks a request the auth interceptor must leave alone. It is stripped before the request goes
 * out; see the interceptor in [TtsRoadRepository].
 */
const val NoAuthHeader = "X-TtsRoad-No-Auth"

interface TtsRoadApi {
    /**
     * Public capability discovery. Unauthenticated on purpose — the login screen calls it against
     * a URL the user is still typing, which must never receive the previous server's token.
     */
    @Headers("X-TtsRoad-No-Auth: 1")
    @GET("api/mobile/capabilities")
    suspend fun capabilities(): CapabilitiesResponse

    @POST("api/mobile/login")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    @POST("api/mobile/logout")
    suspend fun logout(): LogoutResponse

    @GET("api/mobile/me")
    suspend fun me(): CurrentUserResponse

    @GET("api/mobile/library")
    suspend fun library(): LibraryResponse

    @GET("api/mobile/fictions/{fiction_id}/chapters")
    suspend fun chapters(
        @Path("fiction_id") fictionId: Int,
        @Query("playable_only") playableOnly: Boolean = false,
        @Query("include_excluded") includeExcluded: Boolean = false,
    ): ChaptersResponse

    @POST("api/mobile/playback/progress")
    suspend fun saveProgress(@Body request: PlaybackProgressRequest): PlaybackProgressResponse

    @POST("api/mobile/playback/mark")
    suspend fun markPlayback(@Body request: PlaybackMarkRequest): PlaybackMarkResponse
}

