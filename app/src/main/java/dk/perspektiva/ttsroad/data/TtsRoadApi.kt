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

    /**
     * Batched, timestamped progress — the endpoint that can be *ordered* against writes from other
     * clients. Preferred over [saveProgress] whenever the server advertises `batch_progress`; see
     * `PendingProgressStore` for why the app queues positions rather than posting them directly.
     */
    @POST("api/mobile/playback/sync")
    suspend fun syncProgress(@Body request: PlaybackSyncRequest): PlaybackSyncResponse

    @POST("api/mobile/playback/mark")
    suspend fun markPlayback(@Body request: PlaybackMarkRequest): PlaybackMarkResponse

    /** The shared Up Next queue — the same rows the browser reads. */
    @GET("api/mobile/queue")
    suspend fun queue(): QueueResponse

    /** Every mutation, including `advance`, whose reply carries the item to play. */
    @POST("api/mobile/queue")
    suspend fun updateQueue(@Body request: QueueRequest): QueueAdvanceResponse

    /**
     * Server-side search across fiction metadata, chapter titles and the narration text itself.
     *
     * The last of those is the reason this exists: the app's local filter can only match what it
     * has already loaded, and cannot match chapter text at all. [fictionId] narrows the same query
     * to one book.
     */
    @GET("api/mobile/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("fiction_id") fictionId: Int? = null,
        @Query("limit") limit: Int? = null,
    ): SearchResponse

    /**
     * Fiction management. Admin-only server-side, and mirrored onto the mobile surface rather than
     * called across on `/api/fictions` so the add-fields-don't-rename guarantee and the mobile
     * contract test both cover it.
     *
     * A rejected URL comes back 400 and a fiction already tracked comes back 409, both with a
     * `detail` the caller should show rather than replace — the server knows which sites it accepts.
     */
    @POST("api/mobile/fictions")
    suspend fun addFiction(@Body request: AddFictionRequest): FictionWriteResponse

    /** Destroys the fiction, its chapters and its audio, for every account on the server. */
    @DELETE("api/mobile/fictions/{fiction_id}")
    suspend fun deleteFiction(@Path("fiction_id") fictionId: Int): FictionDeleteResponse

    /** Put a fiction on this account's shelf. 404 when the fiction does not exist. */
    @POST("api/mobile/fictions/{fiction_id}/follow")
    suspend fun followFiction(@Path("fiction_id") fictionId: Int): FollowResponse

    /** Take it off. The fiction stays on the server, reachable through `scope=all`. */
    @DELETE("api/mobile/fictions/{fiction_id}/follow")
    suspend fun unfollowFiction(@Path("fiction_id") fictionId: Int): FollowResponse

    /**
     * Account preferences. Note the path: these are `/api/me/...`, not `/api/mobile/...` — the same
     * rows the web console reads, which is the point of syncing them. The global auth middleware
     * resolves a bearer token before falling back to the session cookie, so the mobile token
     * authenticates here exactly as it does on the mobile routes.
     */
    @GET("api/me/preferences")
    suspend fun accountPreferences(): AccountPreferencesResponse

    /**
     * Only the keys being changed. The server merges the body into the stored blob and echoes the
     * result, so sending a full snapshot would let this phone's stale copy of a setting overwrite
     * one changed elsewhere.
     */
    @PATCH("api/me/preferences")
    suspend fun updateAccountPreferences(
        @Body changes: Map<String, @JvmSuppressWildcards Any?>,
    ): AccountPreferencesResponse

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
