package dk.perspektiva.ttsroad.data

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Marks a request the shared auth interceptor must leave alone. The header never reaches the wire —
 * the interceptor strips it. Discovery runs while the user is still typing a server URL, so a token
 * left over from a previous session must not travel to an unrelated host.
 */
const val NoAuthHeader = "X-TtsRoad-No-Auth"

interface TtsRoadApi {
    @Headers("$NoAuthHeader: 1")
    @GET("api/mobile/capabilities")
    suspend fun capabilities(): CapabilitiesResponse

    @POST("api/mobile/login")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    @POST("api/mobile/logout")
    suspend fun logout(): LogoutResponse

    @GET("api/mobile/me")
    suspend fun me(): CurrentUserResponse

    @GET("api/mobile/devices")
    suspend fun devices(): DevicesResponse

    // Both revoke calls answer with a small status object whose shape is not part of the documented
    // contract, so the body is ignored and the list is refetched instead.
    @DELETE("api/mobile/devices/{token_id}")
    suspend fun revokeDevice(@Path("token_id") tokenId: Int)

    /** Revokes every other mobile session; the token making the request is deliberately kept. */
    @POST("api/mobile/devices/revoke-others")
    suspend fun revokeOtherDevices()

    /**
     * [scope] is `followed` (the caller's shelf) or `all` (everything on the server, for browsing
     * and following from). A server without per-user libraries ignores it and answers the whole
     * shared list either way, which is why the response echoes the scope it actually applied.
     */
    @GET("api/mobile/library")
    suspend fun library(
        @Query("scope") scope: String = LibraryScopeFollowed,
    ): LibraryResponse

    @GET("api/mobile/fictions/{fiction_id}/chapters")
    suspend fun chapters(
        @Path("fiction_id") fictionId: Int,
        @Query("playable_only") playableOnly: Boolean = false,
        @Query("include_excluded") includeExcluded: Boolean = false,
    ): ChaptersResponse

    /**
     * Returns the raw [Response] rather than the body so the caller can see the `ETag` and a `304`.
     * Chapter text never changes after conversion, so revalidation is the normal path for any
     * chapter opened twice, and re-downloading a megabyte of cues to be told nothing changed is
     * exactly what the conditional request avoids.
     *
     * A null [ifNoneMatch] is omitted by Retrofit, which is the first-fetch case.
     */
    @GET("api/mobile/chapters/{chapter_id}/readalong")
    suspend fun readAlong(
        @Path("chapter_id") chapterId: Int,
        @Header("If-None-Match") ifNoneMatch: String? = null,
    ): Response<ReadAlongResponse>

    @POST("api/mobile/playback/progress")
    suspend fun saveProgress(@Body request: PlaybackProgressRequest): PlaybackProgressResponse

    @POST("api/mobile/playback/mark")
    suspend fun markPlayback(@Body request: PlaybackMarkRequest): PlaybackMarkResponse

    /** Put a fiction on this account's shelf. 404 when the fiction does not exist. */
    @POST("api/mobile/fictions/{fiction_id}/follow")
    suspend fun followFiction(@Path("fiction_id") fictionId: Int): FollowResponse

    /** Take it off. The fiction stays on the server, reachable through `scope=all`. */
    @DELETE("api/mobile/fictions/{fiction_id}/follow")
    suspend fun unfollowFiction(@Path("fiction_id") fictionId: Int): FollowResponse
}

