package dk.perspektiva.ttsroad.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * How long a discovered capability set is trusted before it is re-asked. The server advertises new
 * features when it is upgraded under a long-lived app process, so this is short enough to notice an
 * upgrade the same day without making discovery a per-screen cost.
 */
private val CapabilityTtlMillis = TimeUnit.HOURS.toMillis(6)

/**
 * Batch size to use when the server advertises `batch_progress` but names no limit.
 *
 * Deliberately well under the 500 the backend actually enforces: guessing high costs a whole flush
 * to a 400, while guessing low costs one extra round trip in a case that is already rare.
 */
const val DefaultPlaybackSyncBatchLimit: Int = 100

/**
 * Outcome of tracking a new fiction.
 *
 * A sealed result rather than an exception because every failure here is one the user can act on by
 * editing what they pasted, and a thrown [HttpException] would lose the server's explanation of
 * *which* sites it accepts — which is the only thing that makes the error useful.
 */
sealed interface FictionAddResult {
    data class Added(val fiction: FictionSummary?) : FictionAddResult
    /** The server said no, with its own words: an unsupported host, or a fiction already tracked. */
    data class Refused(val message: String) : FictionAddResult
    /** This server has no fiction management. The control should not have been shown. */
    data object Unsupported : FictionAddResult
}

/**
 * Outcome of editing a fiction's metadata, or of replacing its cover.
 *
 * Shaped like [FictionAddResult] and for the same reason: the user is standing in front of a form
 * waiting to hear whether it took, and the server's own words — "title must not be empty", "that
 * file is not an image" — are more use than any message this client could invent.
 *
 * [Saved] carries the fiction *as the server now holds it*, which is the only trustworthy account of
 * what an edit did. It is also how a server too old to know a field is detected: it echoes a fiction
 * with no `metadata_overrides` rather than failing.
 */
sealed interface FictionEditResult {
    data class Saved(val fiction: FictionSummary?) : FictionEditResult
    data class Refused(val message: String) : FictionEditResult
    /** This server cannot do this at all — no fiction management, or no cover route. */
    data object Unsupported : FictionEditResult
}

/** Outcome of a mobile login attempt. */
sealed interface LoginResult {
    data object Success : LoginResult
    /** Password was accepted but a valid 2FA code is required; resubmit with [totpCode]. */
    data object TotpRequired : LoginResult
    data class Failure(val message: String) : LoginResult
}

class TtsRoadRepository(
    private val tokenStore: SessionStore,
    /** Injectable so the capability cache's expiry can be tested without waiting on wall time. */
    private val clock: () -> Long = System::currentTimeMillis,
    /** Where read-along documents survive a restart. Defaults to not persisting at all. */
    private val readAlongStore: ReadAlongStore = ReadAlongStore.None,
) {
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
            val builder = request.newBuilder()
            if (request.header(NoAuthHeader) != null) {
                builder.removeHeader(NoAuthHeader)
            } else {
                authHeader?.let { builder.header("Authorization", it) }
            }
            chain.proceed(builder.build())
        }
        .build()

    private val apiCache = HashMap<String, TtsRoadApi>()

    /**
     * Discovered capabilities per normalized base URL. Kept in memory rather than on disk: it is one
     * cheap unauthenticated call per launch, and a stale flag surviving a reinstall would be worse
     * than refetching.
     */
    private val capabilityCache = HashMap<String, CachedCapabilities>()

    private data class CachedCapabilities(
        val value: ServerCapabilities,
        val fetchedAtMillis: Long,
    )

    /**
     * Parsed read-along documents by chapter id, with the ETag they were served under.
     *
     * The parsed document is held, not the payload, because a chapter runs to tens of thousands of
     * cues: re-deriving spans and sentences on every 304 would put a visible pause on the way back
     * into a chapter the user just left.
     */
    private val readAlongCache = HashMap<Int, CachedReadAlongDocument>()

    private data class CachedReadAlongDocument(
        val etag: String?,
        val document: ReadAlongDocument,
    )

    private val _currentCapabilities = MutableStateFlow(ServerCapabilities.Baseline)

    /**
     * What the signed-in server supports. Optional UI observes this and stays hidden at the
     * baseline, which is also what an older or unreachable server resolves to.
     */
    val currentCapabilities: StateFlow<ServerCapabilities> = _currentCapabilities.asStateFlow()

    private val _sessionEnd = MutableStateFlow<SessionEnd?>(null)

    /**
     * Why the stored token was dropped, or null while the session is still usable.
     *
     * Set once an authenticated call — or an audio request, via [endSession] — came back 401, so the
     * login screen can say why it is being shown rather than appearing out of nowhere. Cleared by a
     * successful [login].
     */
    val sessionEnd: StateFlow<SessionEnd?> = _sessionEnd.asStateFlow()

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
            _sessionEnd.value = null
            LoginResult.Success
        } catch (e: HttpException) {
            val body = e.response()?.errorBody()?.string()
            if (e.code() == 401 && body?.contains("totp_required") == true) {
                LoginResult.TotpRequired
            } else {
                LoginResult.Failure(detailMessage(body) ?: "Invalid username or password")
            }
        } catch (e: Exception) {
            LoginResult.Failure(e.message ?: "Login failed")
        }
    }

    /**
     * Drop the session because the server refused the credential.
     *
     * Public because the audio path reaches the same conclusion from somewhere this class cannot
     * see: a 401 on a `/audio/...` request means exactly what a 401 on an API call means, and both
     * should land on the same login screen with the same explanation. See
     * [dk.perspektiva.ttsroad.media.TtsRoadMediaService].
     */
    suspend fun endSession(end: SessionEnd) = withContext(Dispatchers.IO) {
        authHeader = null
        tokenStore.clearToken()
        _sessionEnd.value = end
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
        // Discovery is per server, and the next sign-in may be a different one. Leaving the old
        // flags in place would show read-along or device management on a server without them.
        forgetCapabilities(session.serverUrl)
        _currentCapabilities.value = ServerCapabilities.Baseline
        // Chapter text is account-visible content, and chapter ids are only unique per server, so a
        // cached read-along must never outlive the session that fetched it.
        synchronized(readAlongCache) { readAlongCache.clear() }
        readAlongStore.clear()
    }

    /**
     * Ask [baseUrl] which optional features it supports.
     *
     * Never throws. Discovery is a convenience, not a gate on signing in: a server that 404s (too
     * old to have the endpoint) and a server that cannot be reached at all both resolve to
     * [ServerCapabilities.Baseline] so the baseline login, library and playback flow is untouched.
     *
     * A definitive 404 is cached — that server will not grow the endpoint under us — but a transient
     * failure is not, and leaves any previously discovered capabilities in place. Downgrading a
     * working server to baseline over one dropped request would make features flicker.
     */
    suspend fun capabilities(
        baseUrl: String,
        forceRefresh: Boolean = false,
    ): ServerCapabilities = withContext(Dispatchers.IO) {
        val normalized = runCatching { normalizeBaseUrl(baseUrl) }.getOrNull()
            ?: return@withContext ServerCapabilities.Baseline
        val cached = synchronized(capabilityCache) { capabilityCache[normalized] }
        if (!forceRefresh && cached != null && clock() - cached.fetchedAtMillis < CapabilityTtlMillis) {
            return@withContext cached.value
        }
        try {
            val discovered = ServerCapabilities.from(api(normalized).capabilities())
            synchronized(capabilityCache) {
                capabilityCache[normalized] = CachedCapabilities(discovered, clock())
            }
            discovered
        } catch (e: HttpException) {
            if (e.code() == 404) {
                synchronized(capabilityCache) {
                    capabilityCache[normalized] =
                        CachedCapabilities(ServerCapabilities.Baseline, clock())
                }
                ServerCapabilities.Baseline
            } else {
                cached?.value ?: ServerCapabilities.Baseline
            }
        } catch (_: Exception) {
            cached?.value ?: ServerCapabilities.Baseline
        }
    }

    /**
     * Re-ask the signed-in server what it supports and publish the answer to [currentCapabilities].
     *
     * Called after login before the library loads, so optional UI is gated by the time there is
     * anything to gate. Signed out, it resets to the baseline rather than leaving the previous
     * account's flags standing.
     */
    suspend fun refreshCurrentCapabilities(forceRefresh: Boolean = false): ServerCapabilities {
        val session = tokenStore.current()
        if (!session.isLoggedIn) {
            _currentCapabilities.value = ServerCapabilities.Baseline
            return ServerCapabilities.Baseline
        }
        return capabilities(session.serverUrl, forceRefresh).also { _currentCapabilities.value = it }
    }

    /** Drop discovered capabilities for [baseUrl], so the next call re-asks. Used on sign-out. */
    fun forgetCapabilities(baseUrl: String) {
        val normalized = runCatching { normalizeBaseUrl(baseUrl) }.getOrNull() ?: return
        synchronized(capabilityCache) { capabilityCache.remove(normalized) }
    }

    /**
     * The caller's shelf, or the whole catalogue with [scope] = [LibraryScopeAll].
     *
     * The parameter is always sent. A server without per-user libraries ignores it and answers the
     * shared list either way, and the response says which scope it actually applied.
     */
    suspend fun library(scope: String = LibraryScopeFollowed): LibraryResponse =
        withAuthorizedApi { it.library(scope) }

    /**
     * Follow or unfollow a fiction. Answers the resulting state, or null when the server has no
     * per-user libraries and the control should not have been offered.
     *
     * A 404 means the fiction is gone from the server — not a failure to report as one, but it
     * cannot be followed either, so the caller is told what is true: it is not followed.
     */
    suspend fun setFollowing(fictionId: Int, following: Boolean): Boolean? {
        if (!_currentCapabilities.value.follows) return null
        return try {
            withAuthorizedApi {
                if (following) it.followFiction(fictionId) else it.unfollowFiction(fictionId)
            }.following
        } catch (e: HttpException) {
            if (e.code() == 404) false else throw e
        }
    }

    /**
     * Track a new fiction, or report why the server would not.
     *
     * Unlike most of this class, a failure here is *not* swallowed: the user typed something and is
     * waiting to hear whether it worked, so the server's own explanation — which URLs it accepts, or
     * that this fiction is already tracked — is the whole value of the response. [FictionAddResult]
     * carries that message rather than throwing, because none of these are exceptional conditions.
     *
     * Adding is admin-only server-side. The UI hides the control for a non-admin account, so a
     * [FictionAddResult.Refused] here means the session lost admin since it signed in.
     */
    suspend fun addFiction(url: String): FictionAddResult {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return FictionAddResult.Refused("Paste a fiction URL first.")
        if (!_currentCapabilities.value.fictionManagement) {
            return FictionAddResult.Unsupported
        }
        return try {
            val added = withAuthorizedApi { it.addFiction(AddFictionRequest(fictionUrl = trimmed)) }
            FictionAddResult.Added(added.fiction)
        } catch (e: HttpException) {
            if (e.code() == 401) throw e
            FictionAddResult.Refused(
                detailMessage(e.response()?.errorBody()?.string())
                    ?: "The server would not add that fiction.",
            )
        }
    }

    /**
     * Correct a fiction's metadata by hand, or hand a field back to the source.
     *
     * [changes] must carry only what actually changed — `fictionMetadataPatch` is where that is
     * decided — because the server marks every field a PATCH sets as hand-edited and stops
     * refreshing it. [FictionUpdateRequest.clearOverrides] is the reverse, and is deliberately a
     * field on the same request rather than a route of its own: it is the same edit, from the other
     * direction.
     *
     * Admin-only server-side. The editor is hidden for a non-admin account, so a [Refused] here
     * means the session lost admin since it signed in.
     */
    suspend fun updateFiction(fictionId: Int, changes: FictionUpdateRequest): FictionEditResult {
        if (!_currentCapabilities.value.fictionManagement) return FictionEditResult.Unsupported
        return try {
            FictionEditResult.Saved(
                withAuthorizedApi { it.updateFiction(fictionId, changes) }.fiction,
            )
        } catch (e: HttpException) {
            if (e.code() == 401) throw e
            FictionEditResult.Refused(
                detailMessage(e.response()?.errorBody()?.string())
                    ?: "The server would not save that change.",
            )
        }
    }

    /**
     * Replace the cover with an image from the device.
     *
     * The size and type are checked here as well as on the screen, because this is the last place
     * before the bytes leave the phone: the ceiling matches the server's, and a mobile connection is
     * the wrong place to discover a 413.
     *
     * [FictionEditResult.Unsupported] covers a 404, which on this route almost always means the
     * server predates cover uploads — the fiction was loaded a moment ago, and the PATCH route
     * shares its id space. The caller says so rather than reporting a failure the user can act on.
     */
    suspend fun uploadFictionCover(
        fictionId: Int,
        bytes: ByteArray,
        mimeType: String,
        filename: String = coverFilename(mimeType),
    ): FictionEditResult {
        if (!_currentCapabilities.value.fictionManagement) return FictionEditResult.Unsupported
        coverRejectionReason(mimeType, bytes.size.toLong())
            ?.let { return FictionEditResult.Refused(it) }
        val part = MultipartBody.Part.createFormData(
            // The server looks for a part with exactly this name; anything else is a 422.
            "file",
            filename,
            bytes.toRequestBody(mimeType.toMediaTypeOrNull()),
        )
        return try {
            FictionEditResult.Saved(
                withAuthorizedApi { it.uploadFictionCover(fictionId, part) }.fiction,
            )
        } catch (e: HttpException) {
            when (e.code()) {
                401 -> throw e
                404 -> FictionEditResult.Unsupported
                else -> FictionEditResult.Refused(
                    detailMessage(e.response()?.errorBody()?.string())
                        ?: "The server would not accept that image.",
                )
            }
        }
    }

    /**
     * Delete a fiction, its chapters and its audio. True when it is gone.
     *
     * A 404 counts as gone: deleting something already deleted — from the browser, or a second tap
     * on a stale list — is the outcome the caller wanted. Null means this server has no fiction
     * management at all, which is a different answer from "the delete failed".
     */
    suspend fun deleteFiction(fictionId: Int): Boolean? {
        if (!_currentCapabilities.value.fictionManagement) return null
        return try {
            withAuthorizedApi { it.deleteFiction(fictionId) }.deleted
        } catch (e: HttpException) {
            if (e.code() == 404) true else throw e
        }
    }

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

    /**
     * What each converted chapter of [fictionId] currently hashes to, or null when this server
     * cannot answer.
     *
     * Null rather than an exception for both refusals — an older server without the capability, and
     * a request that failed — because the caller is a background freshness check. Every answer it
     * cannot get means "carry on with what is on disk", which is also the answer when nothing has
     * changed, so a failure here must not surface as an error on a screen the user opened to read.
     */
    suspend fun audioHashes(fictionId: Int): AudioHashesResponse? {
        if (!_currentCapabilities.value.audioContentHash) return null
        return runCatching { withAuthorizedApi { it.audioHashes(fictionId) } }.getOrNull()
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

    /**
     * The read-along document for [chapterId], or null when this chapter simply does not have one.
     *
     * A `404` is an ordinary answer, not a failure: plenty of chapters were converted before timing
     * existed, and the reader's job there is to say "no read-along", quietly. Only a genuine
     * failure with nothing cached to fall back on throws.
     *
     * The three caches stack. In memory, a repeat open costs nothing at all. On disk, a chapter
     * opened once reads with the phone offline. On the wire, the stored `ETag` turns every reopen
     * into a `304` — chapter text never changes after conversion, so that is the normal case, and
     * it is what keeps re-entering a chapter from re-downloading a megabyte of cues.
     */
    suspend fun readAlong(chapterId: Int): ReadAlongDocument? = withContext(Dispatchers.IO) {
        val cached = cachedReadAlong(chapterId)
        try {
            authorized { api ->
                // Only send If-None-Match when there is something to revalidate, so a 304 can never
                // arrive without a document to answer it with.
                val response = api.readAlong(chapterId, cached?.etag)
                when {
                    response.code() == 304 -> cached?.document
                    response.code() == 404 -> null
                    response.isSuccessful -> response.body()?.let { body ->
                        val document = ReadAlongDocument.from(body)
                        val etag = response.headers()["ETag"]
                        synchronized(readAlongCache) {
                            readAlongCache[chapterId] = CachedReadAlongDocument(etag, document)
                        }
                        readAlongStore.write(chapterId, CachedReadAlong(etag, body))
                        document
                    }

                    // Rethrown so `authorized` can see a 401 and expire the session; every other
                    // status falls through to the cached copy below.
                    else -> throw HttpException(response)
                }
            }
        } catch (e: HttpException) {
            if (e.code() == 401) throw e
            cached?.document ?: throw e
        } catch (e: Exception) {
            // Offline, or the server is down: the text the user already read is a far better answer
            // than an error screen.
            cached?.document ?: throw e
        }
    }

    /**
     * Hold [chapterId]'s read-along document for as long as its audio is downloaded.
     *
     * Downloads used to fetch the MP3 and nothing else, so a chapter taken on a flight played
     * offline and could not be *read* offline unless the reader had happened to be opened on it
     * beforehand (#123). The backend's own `download-plan` endpoint treats the audio and its
     * companion documents as one unit for exactly this reason.
     *
     * Answers whether a document is now held. False is an ordinary outcome: plenty of chapters were
     * converted before timings existed, and there is nothing to pin for those.
     *
     * Best-effort by design: no failure here is rethrown, because this runs in the background behind
     * a download the user asked for and a prefetch that did not land must not fail that download.
     *
     * A 401 is the exception worth being precise about. [authorized] expires the session *before*
     * rethrowing, so a revoked token still signs the user out from here — the swallow only stops
     * the exception propagating, not the expiry. That is deliberate: the token really is invalid,
     * every other call is about to fail too, and hiding it in this one path would leave the app
     * quietly half-broken rather than asking for a sign-in.
     */
    suspend fun pinReadAlong(chapterId: Int): Boolean = withContext(Dispatchers.IO) {
        if (readAlongStore.isPinned(chapterId)) return@withContext true
        // Already on disk from having been read: promote it rather than spending a request.
        readAlongStore.read(chapterId)?.let { cached ->
            readAlongStore.pin(chapterId, cached)
            return@withContext true
        }
        runCatching {
            authorized { api ->
                val response = api.readAlong(chapterId, null)
                when {
                    response.code() == 404 -> false
                    response.isSuccessful -> response.body()?.let { body ->
                        readAlongStore.pin(
                            chapterId,
                            CachedReadAlong(response.headers()["ETag"], body),
                        )
                        true
                    } ?: false

                    else -> throw HttpException(response)
                }
            }
        }.getOrDefault(false)
    }

    /** Release a pinned document, when its chapter's audio is deleted. */
    fun unpinReadAlong(chapterId: Int) {
        readAlongStore.unpin(chapterId)
    }

    /** Whatever copy of [chapterId] we already hold, promoting the on-disk one into memory. */
    private fun cachedReadAlong(chapterId: Int): CachedReadAlongDocument? {
        synchronized(readAlongCache) { readAlongCache[chapterId] }?.let { return it }
        val stored = readAlongStore.read(chapterId) ?: return null
        val restored = CachedReadAlongDocument(stored.etag, ReadAlongDocument.from(stored.response))
        synchronized(readAlongCache) { readAlongCache[chapterId] = restored }
        return restored
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

    /**
     * The shared Up Next queue, or null on a server without one.
     *
     * Null is not an empty queue: it means this server has no cross-library queue at all, so the
     * browse node and the queue controls should not be offered.
     */
    suspend fun queue(): QueueResponse? {
        if (!_currentCapabilities.value.queue) return null
        return withAuthorizedApi { it.queue() }
    }

    /** Append chapters to the queue, or slot them in right after what is playing. */
    suspend fun addToQueue(chapterIds: List<Int>, playNext: Boolean = false): QueueAdvanceResponse? {
        if (chapterIds.isEmpty()) return null
        if (!_currentCapabilities.value.queue) return null
        return withAuthorizedApi {
            it.updateQueue(
                QueueRequest(
                    action = QueueActionAdd,
                    chapterIds = chapterIds,
                    mode = if (playNext) QueueModeNext else QueueModeEnd,
                ),
            )
        }
    }

    /** Remove by *queue row* id — the item's `id`, not its chapter id. */
    suspend fun removeFromQueue(itemIds: List<Int>): QueueAdvanceResponse? {
        if (itemIds.isEmpty()) return null
        if (!_currentCapabilities.value.queue) return null
        return withAuthorizedApi {
            it.updateQueue(QueueRequest(action = QueueActionRemove, itemIds = itemIds))
        }
    }

    suspend fun clearQueue(): QueueAdvanceResponse? {
        if (!_currentCapabilities.value.queue) return null
        return withAuthorizedApi { it.updateQueue(QueueRequest(action = QueueActionClear)) }
    }

    /**
     * Take the head of the queue, or — when it is empty and the account says `continue` — the
     * oldest unplayed chapter in the library.
     *
     * The decision is the server's on purpose. `queue_when_empty` is an account preference, and
     * `advance` reads it, so calling this rather than deciding locally is what makes the phone and
     * the browser agree about what comes after the last chapter of a book.
     */
    suspend fun advanceQueue(): QueueAdvanceResponse? {
        if (!_currentCapabilities.value.queue) return null
        return withAuthorizedApi { it.updateQueue(QueueRequest(action = QueueActionAdvance)) }
    }

    /**
     * Server-side search, or null when this server cannot do it.
     *
     * Null is not "no results" — the caller falls back to the local filter, which is still the
     * instant path and the only one that works offline.
     */
    suspend fun search(query: String, fictionId: Int? = null): SearchResponse? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return null
        if (!_currentCapabilities.value.search) return null
        return withAuthorizedApi { it.search(query = trimmed, fictionId = fictionId) }
    }

    /**
     * The account's stored preference blob, or null when this server cannot hold one.
     *
     * Gated on the discovered `player_preferences` capability rather than on a 404: an older server
     * answers `/api/me/preferences` perfectly well and simply drops every key it does not know, so
     * probing the endpoint would report success while the settings quietly went nowhere.
     */
    suspend fun accountPreferences(): Map<String, Any?>? {
        if (!_currentCapabilities.value.playerPreferences) return null
        return ifPreferencesSupported { it.accountPreferences() }?.preferences
    }

    /**
     * PATCHes [changes] and answers the echoed blob, or null when the server cannot hold it.
     *
     * [changes] must carry only the keys being changed — see `chapterFilterPatch` and friends.
     */
    suspend fun updateAccountPreferences(changes: Map<String, Any?>): Map<String, Any?>? {
        if (changes.isEmpty()) return null
        if (!_currentCapabilities.value.playerPreferences) return null
        return ifPreferencesSupported { it.updateAccountPreferences(changes) }?.preferences
    }

    /**
     * Preference calls must never take a screen down with them.
     *
     * Every caller has a working local value already — the account copy is an improvement on it,
     * not a prerequisite — so a server that has the capability but fails the call anyway (offline,
     * mid-restart, a 404 from a version skew the flag did not predict) answers null and the phone
     * keeps what it has. A 401 still propagates through [authorized] and signs the session out.
     */
    private suspend fun <T> ifPreferencesSupported(block: suspend (TtsRoadApi) -> T): T? = try {
        withAuthorizedApi(block)
    } catch (e: HttpException) {
        if (e.code() == 401) throw e else null
    } catch (e: IOException) {
        null
    }

    /**
     * The account's bookmarks, or null on a server without the `bookmarks` capability.
     *
     * Null and empty mean different things and the caller must keep them apart: null is "this
     * server cannot do bookmarks", which hides the UI; empty is "you have not made any yet".
     */
    suspend fun bookmarks(fictionId: Int? = null, chapterId: Int? = null): List<Bookmark>? {
        if (!_currentCapabilities.value.bookmarks) return null
        return withAuthorizedApi {
            it.bookmarks(fictionId = fictionId, chapterId = chapterId)
        }.bookmarks
    }

    /**
     * The account's jump-back breadcrumbs, or null on a server without the `bookmarks` capability.
     *
     * Separate from [bookmarks] rather than a parameter on it so the two can never be confused at a
     * call site: this list is machine-written and can run to hundreds of rows, and rendering it
     * where the user's own marks belong is the bug this filter exists to prevent.
     */
    suspend fun breadcrumbs(): List<Bookmark>? {
        if (!_currentCapabilities.value.bookmarks) return null
        return withAuthorizedApi { it.bookmarks(kind = BookmarkKindAuto) }.bookmarks
    }

    /** The created bookmark, or null when the server cannot hold one. Throws on a real failure. */
    suspend fun createBookmark(
        chapterId: Int,
        positionSeconds: Double,
        label: String? = null,
        note: String? = null,
        kind: String = BookmarkKindManual,
    ): Bookmark? {
        if (!_currentCapabilities.value.bookmarks) return null
        return withAuthorizedApi {
            it.createBookmark(
                CreateBookmarkRequest(
                    chapterId = chapterId,
                    positionSeconds = positionSeconds.coerceAtLeast(0.0),
                    label = label?.trim()?.takeIf { text -> text.isNotEmpty() },
                    note = note?.trim()?.takeIf { text -> text.isNotEmpty() },
                    kind = kind,
                ),
            )
        }.bookmark
    }

    suspend fun updateBookmark(bookmarkId: Int, label: String?, note: String?): Bookmark? {
        if (!_currentCapabilities.value.bookmarks) return null
        return withAuthorizedApi {
            it.updateBookmark(bookmarkId, UpdateBookmarkRequest(label = label, note = note))
        }.bookmark
    }

    /** True when the bookmark is gone. A 404 counts: it is already not there. */
    suspend fun deleteBookmark(bookmarkId: Int): Boolean {
        if (!_currentCapabilities.value.bookmarks) return false
        return try {
            withAuthorizedApi { it.deleteBookmark(bookmarkId) }
            true
        } catch (e: HttpException) {
            // Deleting something already deleted — from the browser, or a double tap — is the
            // outcome the caller wanted, not an error to put on screen.
            if (e.code() == 404) true else throw e
        }
    }

    /**
     * Batched, timestamped progress. Answers null when the server has no `batch_progress` support,
     * which is the caller's signal to fall back to the single-item endpoint.
     */
    suspend fun syncProgress(items: List<PlaybackSyncItem>): PlaybackSyncResponse? {
        if (items.isEmpty()) return null
        if (!_currentCapabilities.value.batchProgress) return null
        return withAuthorizedApi { it.syncProgress(PlaybackSyncRequest(items = items)) }
    }

    /** How many items one `/playback/sync` call may carry against the current server. */
    fun playbackSyncBatchLimit(): Int =
        _currentCapabilities.value.maxPlaybackSyncItems?.takeIf { it > 0 }
            ?: DefaultPlaybackSyncBatchLimit

    /** Every mobile session on this account, or null on a server that has no devices endpoint. */
    suspend fun devices(): List<DeviceSession>? = ifDevicesSupported { it.devices() }?.devices

    /** Revokes one session. False means the server has no devices endpoint, not that it refused. */
    suspend fun revokeDevice(tokenId: Int): Boolean =
        ifDevicesSupported { it.revokeDevice(tokenId) } != null

    /**
     * Revokes every *other* mobile session.
     *
     * One server-side call rather than a loop of deletes, precisely so the client cannot get the
     * "which one am I" question wrong: the token making the request is the one kept.
     */
    suspend fun revokeOtherDevices(): Boolean =
        ifDevicesSupported { it.revokeOtherDevices() } != null

    /**
     * Run a devices call, answering null when the server has never heard of the endpoint.
     *
     * The devices API is additive and `api_version` did not change with it, so there is no version
     * to test against — a 404 is the only signal that the backend predates it. Treating that as
     * "not supported" lets the screen say so instead of showing an HTTP error the user cannot act
     * on. Anything else, including a 401, keeps its normal meaning.
     */
    private suspend fun <T> ifDevicesSupported(block: suspend (TtsRoadApi) -> T): T? = try {
        withAuthorizedApi(block)
    } catch (e: HttpException) {
        if (e.code() == 404) null else throw e
    }

    private suspend fun <T> withAuthorizedApi(block: suspend (TtsRoadApi) -> T): T =
        withContext(Dispatchers.IO) { authorized(block) }

    /**
     * Runs an authenticated call, turning a server-side 401 into a forced sign-out.
     *
     * A 401 on an authenticated endpoint means the stored token is no longer valid — expired after
     * 90 days unused, revoked from another device, or the server database was reset — so retrying
     * can never succeed. Dropping the token lets the session observer in `TtsRoadApp` fall back to
     * the login screen (which also stops playback) instead of every screen showing "HTTP 401
     * Unauthorized" until the user finds Settings > Sign out.
     *
     * The server distinguishes those cases in the 401 body, so the reason is carried through to
     * the login screen rather than reduced to a generic "signed out".
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
                _sessionEnd.value = parseSessionEnd(e.response()?.errorBody()?.string())
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
