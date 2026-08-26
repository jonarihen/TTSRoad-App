package dk.perspektiva.ttsroad.data

import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
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
     * Content hashes for one fiction's converted chapters — what a download index needs and nothing
     * else (#109).
     *
     * Deliberately not the chapter list. That carries titles, statuses, per-user playback and
     * read-along flags, and on a several-hundred-chapter serial it is a lot of bytes to re-fetch in
     * order to be told nothing changed. This is the same question asked directly.
     *
     * Gated on the `audio_content_hash` capability: an older server has no route here at all.
     */
    @GET("api/mobile/fictions/{fiction_id}/audio-hashes")
    suspend fun audioHashes(@Path("fiction_id") fictionId: Int): AudioHashesResponse

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

    /**
     * Correct scraped metadata by hand. Admin-only, and answers the fiction as it now stands.
     *
     * The reply is the only way to tell an edit that was applied from one a server too old to know
     * the field quietly dropped, so callers read the echoed fiction rather than the status.
     */
    @PATCH("api/mobile/fictions/{fiction_id}")
    suspend fun updateFiction(
        @Path("fiction_id") fictionId: Int,
        @Body request: FictionUpdateRequest,
    ): FictionWriteResponse

    /**
     * Replace the cover with an image from the device. Admin-only.
     *
     * An upload rather than a URL field on the PATCH above, because a pasted link renders in a
     * browser and then fails to embed in any MP3: the ID3 writer only fetches art from hosts a
     * source adapter allows. Uploading puts the bytes on the server, where every consumer of the
     * cover can reach them.
     *
     * The part must be named `file`, and 404 here means the server predates the route — not that
     * the fiction is missing.
     */
    @Multipart
    @POST("api/mobile/fictions/{fiction_id}/cover")
    suspend fun uploadFictionCover(
        @Path("fiction_id") fictionId: Int,
        @Part file: MultipartBody.Part,
    ): FictionWriteResponse

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

    // Account security (#118). The app already handles the 2FA *login* challenge and could not be
    // the thing that turns the factor on — and an admin who set the server up from a phone is
    // soft-gated toward an account page that only exists in a browser.

    /**
     * Change the password, and receive a replacement token.
     *
     * The old token is dead the moment this returns 200 — a credential minted under the old
     * password does not outlive it — so the answer must be stored, not merely inspected.
     */
    @POST("api/mobile/account/password")
    suspend fun changePassword(@Body request: PasswordChangeRequest): PasswordChangeResponse

    @GET("api/mobile/account/2fa")
    suspend fun twoFactorStatus(): TwoFactorStatus

    @POST("api/mobile/account/2fa/setup")
    suspend fun startTwoFactorSetup(): TwoFactorSetup

    @POST("api/mobile/account/2fa/enable")
    suspend fun enableTwoFactor(@Body request: TwoFactorEnableRequest): TwoFactorCodes

    @POST("api/mobile/account/2fa/recovery-codes")
    suspend fun reissueRecoveryCodes(): TwoFactorCodes

    /**
     * Turn the factor off. Requires the current password, not just the bearer token.
     *
     * A stolen token must not be enough to strip the factor that would have stopped it being
     * useful, which is the same reason the web route asks.
     */
    @POST("api/mobile/account/2fa/disable")
    suspend fun disableTwoFactor(@Body request: TwoFactorDisableRequest): TwoFactorCodes

    /**
     * This account's listening totals, streaks, activity grid and badges (#117).
     *
     * Returns the raw [Response] rather than the body for the same reason [readAlong] does: the
     * route carries an `ETag` and honours `If-None-Match`, and this is per-user aggregation over
     * every playback row the account owns. A Stats screen that gets opened often should be sending
     * the conditional request.
     *
     * [weeks] sizes the activity grid and nothing else — every other figure is lifetime. It is
     * validated server-side to 1..53 and answers a `422` outside that, so the caller clamps rather
     * than passing a user-supplied number straight through.
     */
    @GET("api/mobile/stats")
    suspend fun listeningStats(
        @Query("weeks") weeks: Int,
        @Header("If-None-Match") ifNoneMatch: String? = null,
    ): Response<ListeningStatsResponse>

    /**
     * Every podcast URL this account can hand to a podcast app (#115).
     *
     * Scoped to the caller's shelf by default — the URLs they would actually share — the same
     * scoping `/library` uses.
     */
    @GET("api/mobile/feeds")
    suspend fun feeds(@Query("scope") scope: String = LibraryScopeFollowed): FeedsResponse

    /**
     * Invalidate this account's combined feed and OPML URLs.
     *
     * Self-service, not admin-gated: it revokes only the caller's own credential, and if a private
     * token leaks the device in your hand is the natural place to respond from.
     */
    @POST("api/mobile/feeds/rotate")
    suspend fun rotateLibraryFeed(): LibraryFeedRotateResponse

    /**
     * Invalidate one fiction's feed URL. Admin only, because that token is *shared* — every
     * account subscribed to the fiction has to re-subscribe afterwards.
     */
    @POST("api/mobile/fictions/{fiction_id}/feed-token/rotate")
    suspend fun rotateFictionFeedToken(
        @Path("fiction_id") fictionId: Int,
    ): MaintenanceResponse

    /** Every position and chosen mark on this account, as a portable document (#116). */
    @GET("api/mobile/listening-state")
    suspend fun exportListeningState(): ListeningStateExport

    /**
     * Merge a previously exported document back in.
     *
     * The server takes it bare or wrapped in `{"document": …}` — this sends it wrapped, which is
     * exactly the shape the export handed over, so nothing has to unwrap and re-wrap a payload it
     * does not otherwise read.
     */
    @POST("api/mobile/listening-state")
    suspend fun importListeningState(
        @Body document: Map<String, @JvmSuppressWildcards Any?>,
    ): ListeningStateImportResponse

    // Maintenance (#107, #112). Nine routes, one response shape, two capability flags: `retry`,
    // `exclude` and `delete` for a chapter, and poll / retry-failed / retry-all / reconvert-all /
    // retag / apply-filter for a fiction. Which of them an *account* may use is `me.is_admin` —
    // the flags only say the server has them. Retry and poll are the two the server deliberately
    // leaves open to any account.

    @POST("api/mobile/chapters/{chapter_id}/retry")
    suspend fun retryChapter(@Path("chapter_id") chapterId: Int): MaintenanceResponse

    @POST("api/mobile/chapters/{chapter_id}/exclude")
    suspend fun setChapterExcluded(
        @Path("chapter_id") chapterId: Int,
        @Body request: ChapterExcludeRequest,
    ): MaintenanceResponse

    @DELETE("api/mobile/chapters/{chapter_id}")
    suspend fun deleteChapter(@Path("chapter_id") chapterId: Int): MaintenanceResponse

    /**
     * Check the source for new chapters now, rather than waiting for the scheduler.
     *
     * The answer to "the author posted an hour ago, where is it". [full] re-ingests the whole
     * chapter list instead of the recent tail, which is the expensive branch and is why it is a
     * separate action in the UI rather than a default.
     */
    @POST("api/mobile/fictions/{fiction_id}/poll")
    suspend fun pollFiction(
        @Path("fiction_id") fictionId: Int,
        @Query("full") full: Boolean = false,
    ): MaintenanceResponse

    @POST("api/mobile/fictions/{fiction_id}/retry-failed")
    suspend fun retryFailedChapters(@Path("fiction_id") fictionId: Int): MaintenanceResponse

    @POST("api/mobile/retry-all-failed")
    suspend fun retryAllFailed(): MaintenanceResponse

    @POST("api/mobile/fictions/{fiction_id}/reconvert-all")
    suspend fun reconvertAllChapters(@Path("fiction_id") fictionId: Int): MaintenanceResponse

    /**
     * Rewrite the ID3 tags on existing MP3s. No TTS is re-run.
     *
     * The counterpart to the metadata editing that shipped in 0.11.0: a title or cover changed from
     * a phone is only half applied while the files carrying the old one cannot be rewritten from
     * the same place.
     */
    @POST("api/mobile/fictions/{fiction_id}/retag")
    suspend fun retagFiction(@Path("fiction_id") fictionId: Int): MaintenanceResponse

    @POST("api/mobile/fictions/{fiction_id}/apply-chapter-filter")
    suspend fun applyChapterFilter(@Path("fiction_id") fictionId: Int): MaintenanceResponse

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
