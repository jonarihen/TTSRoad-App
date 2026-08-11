package dk.perspektiva.ttsroad.data

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.PATCH
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

    @GET("api/mobile/library")
    suspend fun library(): LibraryResponse

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

    /**
     * Bookmarks. The same rows as the web's `/api/bookmarks` — both routers call into the one
     * service — so a mark made in the car is the same record as one made in the browser.
     *
     * [kind] defaults to `manual`: the `auto` rows are the jump-back breadcrumbs the web player
     * writes, and a list of user-chosen marks drowned in them would be useless.
     */
    @GET("api/mobile/bookmarks")
    suspend fun bookmarks(
        @Query("fiction_id") fictionId: Int? = null,
        @Query("chapter_id") chapterId: Int? = null,
        @Query("kind") kind: String? = BookmarkKindManual,
    ): BookmarksResponse

    @POST("api/mobile/bookmarks")
    suspend fun createBookmark(@Body request: CreateBookmarkRequest): BookmarkWriteResponse

    @PATCH("api/mobile/bookmarks/{bookmark_id}")
    suspend fun updateBookmark(
        @Path("bookmark_id") bookmarkId: Int,
        @Body request: UpdateBookmarkRequest,
    ): BookmarkWriteResponse

    @DELETE("api/mobile/bookmarks/{bookmark_id}")
    suspend fun deleteBookmark(@Path("bookmark_id") bookmarkId: Int): BookmarkDeleteResponse
}

