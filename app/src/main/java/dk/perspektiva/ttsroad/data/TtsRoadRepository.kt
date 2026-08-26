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
 * How long a request marked [SlowUploadHeader] is given to write its body and hear an answer.
 *
 * Sized for the worst realistic case rather than the common one: a hundred-megabyte illustrated
 * book pushed up a mobile connection, which the server then parses and splits into chapters before
 * it replies. Five minutes is generous for that and still short enough that a genuinely dead
 * connection is reported rather than left spinning.
 */
private const val SlowUploadTimeoutMinutes = 5

/**
 * Batch size to use when the server advertises `batch_progress` but names no limit.
 *
 * Deliberately well under the 500 the backend actually enforces: guessing high costs a whole flush
 * to a 400, while guessing low costs one extra round trip in a case that is already rare.
 */
const val DefaultPlaybackSyncBatchLimit: Int = 100

/**
 * Weeks of activity grid to ask for, and the range the server accepts.
 *
 * Twelve is the server's own default and what the web page shows. The bounds are the server's too:
 * anything outside them is a `422` rather than a clamp, so the client clamps before asking.
 */
const val DefaultActivityWeeks: Int = 12
const val MinActivityWeeks: Int = 1
const val MaxActivityWeeks: Int = 53

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
            // A file upload borrows a longer clock from the same client rather than getting a
            // client of its own: sharing the connection pool is the whole reason there is one
            // client, and the timeouts are the only thing an EPUB actually needs changed.
            val slow = request.header(SlowUploadHeader) != null
            if (slow) builder.removeHeader(SlowUploadHeader)
            val proceed = if (slow) {
                chain
                    .withWriteTimeout(SlowUploadTimeoutMinutes, TimeUnit.MINUTES)
                    .withReadTimeout(SlowUploadTimeoutMinutes, TimeUnit.MINUTES)
            } else {
                chain
            }
            proceed.proceed(builder.build())
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

    /**
     * The last stats payload seen for each `weeks` value, with the `ETag` that answered it.
     *
     * Keyed by `weeks` because a different grid size will not answer the previous `ETag` — the
     * server folds it into the revision. In memory only: these are lifetime figures that move every
     * time anything is played, so a copy surviving a process restart would be stale far more often
     * than it would be useful, and re-asking costs one conditional request.
     */
    private val listeningStatsCache = HashMap<Int, CachedListeningStats>()

    private data class CachedListeningStats(
        val etag: String?,
        val response: ListeningStatsResponse,
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
    suspend fun library(
        scope: String = LibraryScopeFollowed,
        updatedSince: String? = null,
    ): LibraryResponse = withAuthorizedApi { it.library(scope, updatedSince) }

    /** Ask whether anything moved before spending requests on sparse payloads. */
    suspend fun deltaSync(updatedSince: String): DeltaSyncResponse? {
        if (!_currentCapabilities.value.deltaSync) return null
        return withAuthorizedApi { it.deltaSync(updatedSince) }
    }

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
     * Import a book that is already on the phone.
     *
     * The interesting half is what happens *before* the request. `/api/mobile/capabilities`
     * publishes `max_epub_bytes` so that a client can refuse an oversized file itself, and this is
     * where that promise is kept: a book past the ceiling never reaches the wire, because the
     * alternative is spending a hundred megabytes of someone's data allowance to be told 413.
     * [epubRejectionReason] applies the server's own two rules — the name has to end in `.epub`,
     * the file has to fit — against the limit this server actually advertised.
     *
     * The bytes are streamed off the content provider rather than read into memory first; see
     * [PickedEpub.Ready]. Nothing here holds the book.
     *
     * Gated on `epub_upload`, not on `fiction_management`: the server treats "accepts files" as a
     * separate thing to advertise, and a 404 here means this server is one that does not.
     */
    suspend fun uploadEpub(book: PickedEpub.Ready): FictionAddResult {
        val capabilities = _currentCapabilities.value
        if (!capabilities.epubUpload) return FictionAddResult.Unsupported
        epubRejectionReason(book.filename, book.sizeBytes, capabilities.effectiveMaxEpubBytes)
            ?.let { return FictionAddResult.Refused(it) }
        val part = MultipartBody.Part.createFormData(
            // The server looks for a part with exactly this name; anything else is a 422.
            "file",
            book.filename,
            book.requestBody(),
        )
        return try {
            FictionAddResult.Added(withAuthorizedApi { it.uploadEpub(part) }.fiction)
        } catch (e: HttpException) {
            when (e.code()) {
                401 -> throw e
                404 -> FictionAddResult.Unsupported
                else -> FictionAddResult.Refused(
                    // 409 is the one worth reading: "This EPUB has already been uploaded" is the
                    // server recognising the file by content hash, which is a useful thing to be
                    // told and not a failure to retry.
                    detailMessage(e.response()?.errorBody()?.string())
                        ?: "The server would not accept that book.",
                )
            }
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
        updatedSince: String? = null,
    ): ChaptersResponse = withAuthorizedApi {
        it.chapters(
            fictionId = fictionId,
            playableOnly = playableOnly,
            includeExcluded = includeExcluded,
            updatedSince = updatedSince,
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

    /**
     * The read-along document for [chapterId] **only if it is already in memory**, else null.
     *
     * For the one caller that must not wait: the media-session capture of a mispronunciation, which
     * runs on the main thread at the instant of a press, often with the phone locked (#125). The
     * word under the playhead is a bonus on that capture and never a precondition — the contract
     * says a report without one still points a human at ten seconds to listen to — so this is
     * deliberately the cheapest possible lookup and nothing else.
     *
     * Note what it does *not* do. It never fetches, and unlike [readAlong] it never falls back to
     * the on-disk store: parsing a chapter's worth of cues off the filesystem is tens of
     * milliseconds of a locked phone's main thread spent on an optional field. In-memory means the
     * reader has this chapter open, or had it open this session — which is exactly the case the
     * issue describes as "a read-along document happens to be loaded".
     */
    fun loadedReadAlong(chapterId: Int): ReadAlongDocument? =
        synchronized(readAlongCache) { readAlongCache[chapterId] }?.document

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

    /**
     * Rewrite the queue's order from the complete list of *row* ids, head first.
     *
     * The whole order rather than "move item 4 up one" because that is what the server takes, and
     * because it is the only shape that survives two clients touching the same queue: a move
     * instruction applied to an order that has since changed lands somewhere nobody asked for.
     */
    suspend fun reorderQueue(itemIds: List<Int>): QueueAdvanceResponse? {
        if (itemIds.isEmpty()) return null
        if (!_currentCapabilities.value.queue) return null
        return withAuthorizedApi {
            it.updateQueue(QueueRequest(action = QueueActionReorder, itemIds = itemIds))
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
     * Set what playback does when the queue runs dry: [QueueWhenEmptyStop] or
     * [QueueWhenEmptyContinue].
     *
     * An account preference rather than a device one, and deliberately so — `advance` reads it
     * server-side, which is what makes the phone and the browser agree about what comes after the
     * last chapter of a book. There is no local copy to keep in step, so this is a plain PATCH
     * rather than anything in [AccountPreferenceSync]; the queue payload is where the current value
     * is read back from.
     *
     * Gated on `queue` rather than on `player_preferences`: a server with a queue is a server whose
     * `advance` reads this key, and the app has no use for the setting on one without.
     */
    suspend fun setQueueWhenEmpty(value: String): Boolean {
        if (!_currentCapabilities.value.queue) return false
        val patch = queueWhenEmptyPatch(value)
        return runCatching { withAuthorizedApi { it.updateAccountPreferences(patch) } }.isSuccess
    }

    // Account security (#118).

    /**
     * Change the password, and adopt the replacement credential the server hands back.
     *
     * **This is the whole reason the method exists rather than the call site doing it.** A password
     * change revokes every mobile token, including the one making the request — a credential minted
     * under the old password must not outlive it. The server therefore answers with a fresh token,
     * and a client that merely inspects the body has signed itself out and will find out on its
     * next request. Saving it here means no call site can forget.
     *
     * Saved through the same [SessionStore.saveLogin] a sign-in uses, because the payload is
     * deliberately [LoginResponse]'s shape; a second way to persist a session is a second way to
     * get it subtly wrong.
     *
     * [deviceName] is left null on purpose when the caller has nothing better to say: the server
     * then reuses the name the old token already had, so the device list does not gain a nameless
     * entry as a side effect of a password change.
     */
    suspend fun changePassword(
        currentPassword: String,
        newPassword: String,
        deviceName: String? = null,
    ): AccountActionResult<Unit> {
        if (!_currentCapabilities.value.accountSecurity) return AccountActionResult.Unsupported
        val currentSession = tokenStore.current()
        if (!currentSession.isLoggedIn) {
            return AccountActionResult.Refused("You are not signed in.")
        }
        return try {
            val response = withAuthorizedApi {
                it.changePassword(
                    PasswordChangeRequest(
                        currentPassword = currentPassword,
                        newPassword = newPassword,
                        deviceName = deviceName?.trim()?.takeIf { name -> name.isNotEmpty() },
                    ),
                )
            }
            tokenStore.saveLogin(
                currentSession.serverUrl,
                LoginResponse(
                    token = response.token,
                    tokenType = response.tokenType,
                    deviceId = response.deviceId,
                    expiresAt = response.expiresAt,
                    user = response.user,
                    // The password response intentionally carries credentials, not server
                    // discovery metadata. Preserve the name already shown in Settings instead of
                    // letting saveLogin replace a branded server name with its default.
                    server = ServerInfo(
                        name = currentSession.serverName,
                        baseUrl = currentSession.serverUrl,
                    ),
                ),
            )
            authHeader = "${response.tokenType.replaceFirstChar { it.uppercase() }} ${response.token}"
            AccountActionResult.Done(Unit)
        } catch (e: HttpException) {
            // A 400 is the server telling the user something: a wrong current password, or a new
            // one the strength policy refuses. Both are meant to be read.
            if (e.code() != 400) throw e
            AccountActionResult.Refused(
                detailMessage(e.response()?.errorBody()?.string())
                    ?: "The server would not change your password.",
            )
        }
    }

    suspend fun twoFactorStatus(): TwoFactorStatus? {
        if (!_currentCapabilities.value.accountSecurity) return null
        return withAuthorizedApi { it.twoFactorStatus() }
    }

    /** A provisional secret. The factor is not active until [enableTwoFactor] confirms a code. */
    suspend fun startTwoFactorSetup(): AccountActionResult<TwoFactorSetup> =
        accountAction("The server would not start two-factor setup.") { it.startTwoFactorSetup() }

    /**
     * Confirm setup with a code from the authenticator, and receive the recovery codes.
     *
     * The codes come back exactly once — they are hashed before storage — so the caller must put
     * them in front of the user rather than storing them for later.
     */
    suspend fun enableTwoFactor(code: String): AccountActionResult<TwoFactorCodes> =
        accountAction("That code didn't match.") {
            it.enableTwoFactor(TwoFactorEnableRequest(code = code.trim()))
        }

    /** A fresh set of one-time codes. The previous set stops working immediately. */
    suspend fun reissueRecoveryCodes(): AccountActionResult<TwoFactorCodes> =
        accountAction("The server would not issue new recovery codes.") { it.reissueRecoveryCodes() }

    /**
     * Turn the factor off. Requires the password, not just the session.
     *
     * A stolen token must not be enough to strip the factor that would have stopped it being
     * useful, which is why the server asks and why this takes a password at all.
     */
    suspend fun disableTwoFactor(password: String): AccountActionResult<TwoFactorCodes> =
        accountAction("Password is incorrect.") {
            it.disableTwoFactor(TwoFactorDisableRequest(password = password))
        }

    /**
     * The shared shape of the four 2FA calls: capability gate, then a 400 read as an answer.
     *
     * [fallback] is only used when the server refused without a `detail`, which it does not do for
     * any of these routes — but a client that showed nothing in that case would be worse than one
     * that showed a guess.
     */
    private suspend fun <T> accountAction(
        fallback: String,
        call: suspend (TtsRoadApi) -> T,
    ): AccountActionResult<T> {
        if (!_currentCapabilities.value.accountSecurity) return AccountActionResult.Unsupported
        return try {
            AccountActionResult.Done(withAuthorizedApi { call(it) })
        } catch (e: HttpException) {
            if (e.code() != 400) throw e
            AccountActionResult.Refused(
                detailMessage(e.response()?.errorBody()?.string()) ?: fallback,
            )
        }
    }

    /**
     * Every podcast URL this account can hand to a podcast app, or null on a server without them.
     *
     * Null is not "no feeds" — it means this server has no way to *tell* the app what the URLs are,
     * so nothing should be drawn rather than a share button that shares nothing.
     */
    /**
     * This account's listening totals, or null when the server has no such endpoint.
     *
     * Null and a thrown failure mean different things to the screen and must stay distinguishable:
     * null is "this server cannot answer that", a permanent state the UI explains once, while a
     * throw is "it could not answer *now*", which is worth a retry button.
     *
     * The `ETag` is what makes reopening the screen cheap. This is aggregation over every playback
     * row the account owns, and the server offers a conditional request precisely because a Stats
     * screen is the kind of thing people bounce in and out of. A `304` carries no body, which is
     * why the cached payload rather than the empty response is what answers one.
     */
    suspend fun listeningStats(weeks: Int = DefaultActivityWeeks): ListeningStatsResponse? =
        withContext(Dispatchers.IO) {
            if (!_currentCapabilities.value.listeningStats) return@withContext null
            // Clamped rather than passed through: the server answers 422 outside 1..53, and a
            // screen choosing its own grid size should never be able to produce one.
            val requested = weeks.coerceIn(MinActivityWeeks, MaxActivityWeeks)
            val cached = synchronized(listeningStatsCache) { listeningStatsCache[requested] }
            authorized { api ->
                // Only conditional when there is something to revalidate, so a 304 can never
                // arrive without a payload to answer it with.
                val response = api.listeningStats(requested, cached?.etag)
                when {
                    response.code() == 304 -> cached?.response
                    response.isSuccessful -> response.body()?.also { body ->
                        val etag = response.headers()["ETag"]
                        synchronized(listeningStatsCache) {
                            listeningStatsCache[requested] = CachedListeningStats(etag, body)
                        }
                    }

                    // Rethrown so `authorized` can see a 401 and expire the session.
                    else -> throw HttpException(response)
                }
            }
        }

    suspend fun feeds(scope: String = LibraryScopeFollowed): FeedsResponse? {
        if (!_currentCapabilities.value.feedUrls) return null
        return withAuthorizedApi { it.feeds(scope) }
    }

    /** Revoke and reissue this account's combined feed and OPML links. Self-service. */
    suspend fun rotateLibraryFeed(): LibraryFeedRotateResponse? {
        if (!_currentCapabilities.value.feedUrls) return null
        return withAuthorizedApi { it.rotateLibraryFeed() }
    }

    /**
     * Revoke and reissue one fiction's feed link. Admin only, server-side.
     *
     * Gated on `fiction_maintenance` rather than `feed_urls`: the route lives with the other
     * whole-fiction admin actions, and a server could advertise the read-only feed list without it.
     */
    suspend fun rotateFictionFeedToken(fictionId: Int): MaintenanceResponse? {
        if (!_currentCapabilities.value.fictionMaintenance) return null
        return withAuthorizedApi { it.rotateFictionFeedToken(fictionId) }
    }

    /**
     * The finished M4B audiobooks on the server, or null on a server without the route (#113).
     *
     * Gated on the capability here and on `is_admin` at the call site, the same two-part gate the
     * other admin surfaces use: the flag says the server has the route, the session says whether
     * this account may reach it. A non-admin asking gets a 403, which is a thrown exception dressed
     * up as a feature that does not exist — so the caller does not ask.
     *
     * The whole response is returned rather than just the list, because `ffmpeg_available` is the
     * difference between "nothing has been exported" and "this server cannot export anything".
     */
    suspend fun audiobookExports(): AudiobookExportsResponse? {
        if (!_currentCapabilities.value.audiobookExport) return null
        return withAuthorizedApi { it.audiobookExports() }
    }

    /**
     * This account's positions and marks, as the server's own document.
     *
     * Returned as the opaque map the server sent. Nothing here reads or rewrites it: a document
     * from a newer server has to survive a round trip through an older app, and the only way to
     * guarantee that is never to parse it.
     */
    suspend fun exportListeningState(): Map<String, Any?>? {
        if (!_currentCapabilities.value.listeningStateBackup) return null
        return withAuthorizedApi { it.exportListeningState() }.document
    }

    /**
     * Merge a previously exported document back into this account.
     *
     * Never destructive server-side — a position only moves forward and bookmarks are added rather
     * than reconciled — so restoring a six-month-old backup over a live account cannot undo six
     * months of listening. The report is what says whether it did anything.
     */
    suspend fun importListeningState(document: Map<String, Any?>): Map<String, Any?>? {
        if (!_currentCapabilities.value.listeningStateBackup) return null
        return withAuthorizedApi { it.importListeningState(mapOf("document" to document)) }.report
    }

    // Maintenance (#107, #112). Every call answers a [MaintenanceResponse] or null, and null means
    // exactly one thing: this server does not have the route. A *failure* throws, because unlike the
    // background freshness check these are actions the user pressed a button for and expects to be
    // told about — "Poll" quietly doing nothing is worse than "Poll" saying it could not.

    /**
     * Queue one chapter for conversion again.
     *
     * Gated on the capability only, not on admin: the server leaves this route open to any account
     * on purpose — it repairs one chapter, harms nobody, and the account watching a failed row is
     * usually the one that wants it fixed.
     */
    suspend fun retryChapter(chapterId: Int): MaintenanceResponse? {
        if (!_currentCapabilities.value.chapterMaintenance) return null
        return withAuthorizedApi { it.retryChapter(chapterId) }
    }

    /** Take one chapter off every feed and player, or put it back. Admin only, server-side. */
    suspend fun setChapterExcluded(chapterId: Int, excluded: Boolean): MaintenanceResponse? {
        if (!_currentCapabilities.value.chapterMaintenance) return null
        return withAuthorizedApi {
            it.setChapterExcluded(chapterId, ChapterExcludeRequest(excluded = excluded))
        }
    }

    /** Delete one chapter and its audio. Admin only, server-side. */
    suspend fun deleteChapter(chapterId: Int): MaintenanceResponse? {
        if (!_currentCapabilities.value.chapterMaintenance) return null
        return withAuthorizedApi { it.deleteChapter(chapterId) }
    }

    /**
     * Check the source for new chapters now.
     *
     * Open to any account, like [retryChapter] — the server rate-limits it and a fresh chapter
     * benefits every reader. [full] re-ingests the whole chapter list rather than the recent tail.
     */
    suspend fun pollFiction(fictionId: Int, full: Boolean = false): MaintenanceResponse? {
        if (!_currentCapabilities.value.fictionMaintenance) return null
        return withAuthorizedApi { it.pollFiction(fictionId, full = full) }
    }

    suspend fun retryFailedChapters(fictionId: Int): MaintenanceResponse? {
        if (!_currentCapabilities.value.fictionMaintenance) return null
        return withAuthorizedApi { it.retryFailedChapters(fictionId) }
    }

    suspend fun retryAllFailed(): MaintenanceResponse? {
        if (!_currentCapabilities.value.fictionMaintenance) return null
        return withAuthorizedApi { it.retryAllFailed() }
    }

    /** Re-narrate every chapter. Expensive — a 400-chapter serial is 400 conversions. */
    suspend fun reconvertAllChapters(fictionId: Int): MaintenanceResponse? {
        if (!_currentCapabilities.value.fictionMaintenance) return null
        return withAuthorizedApi { it.reconvertAllChapters(fictionId) }
    }

    /** Rewrite the ID3 tags on existing MP3s. No TTS is re-run. */
    suspend fun retagFiction(fictionId: Int): MaintenanceResponse? {
        if (!_currentCapabilities.value.fictionMaintenance) return null
        return withAuthorizedApi { it.retagFiction(fictionId) }
    }

    /** Re-run the fiction's title filter over chapters that already exist. Excludes only. */
    suspend fun applyChapterFilter(fictionId: Int): MaintenanceResponse? {
        if (!_currentCapabilities.value.fictionMaintenance) return null
        return withAuthorizedApi { it.applyChapterFilter(fictionId) }
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
     * This account's captured pronunciation problems, or null when the server has no capture store.
     *
     * Null and empty are deliberately different: null hides the feature, while an empty list is a
     * supported server on which the listener has not filed anything matching the filters.
     */
    suspend fun pronunciationReports(
        fictionId: Int? = null,
        includeResolved: Boolean = false,
    ): List<PronunciationReport>? {
        if (!_currentCapabilities.value.pronunciationReports) return null
        return withAuthorizedApi {
            it.pronunciationReports(
                fictionId = fictionId,
                includeResolved = includeResolved,
            )
        }.reports
    }

    /**
     * Capture where a pronunciation problem was heard, or null when the server cannot store one.
     *
     * [word] is optional by contract and must stay that way: a media-session command normally has
     * the chapter and position but no timed read-along document. Non-finite and negative positions
     * are flattened before Moshi sees them; NaN and infinity are not valid JSON, and losing the
     * entire locked-phone capture over a bad clock value would lose the only useful information.
     *
     * A real server refusal, including the open-report ceiling's 409, propagates as an
     * [HttpException] so the UI can show the backend's specific `detail`.
     */
    suspend fun createPronunciationReport(
        chapterId: Int,
        positionSeconds: Double = 0.0,
        fictionId: Int? = null,
        word: String? = null,
        note: String? = null,
    ): PronunciationReport? {
        if (!_currentCapabilities.value.pronunciationReports) return null
        val safePosition = positionSeconds.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        return withAuthorizedApi {
            it.createPronunciationReport(
                CreatePronunciationReportRequest(
                    chapterId = chapterId,
                    fictionId = fictionId,
                    positionSeconds = safePosition,
                    word = word?.trim()?.takeIf { text -> text.isNotEmpty() },
                    note = note?.trim()?.takeIf { text -> text.isNotEmpty() },
                ),
            )
        }.report
    }

    /** True when the report is gone. A 404 counts because it is already not there. */
    suspend fun deletePronunciationReport(reportId: Int): Boolean {
        if (!_currentCapabilities.value.pronunciationReports) return false
        return try {
            withAuthorizedApi { it.deletePronunciationReport(reportId) }
            true
        } catch (e: HttpException) {
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
                // A password change deliberately rotates this credential. A request that began
                // just before the rotation can come back 401 just after the fresh token was saved;
                // it must not erase that newer session. Only the token that actually failed is
                // allowed to end the session.
                if (tokenStore.current().token == session.token) {
                    authHeader = null
                    tokenStore.clearToken()
                    _sessionEnd.value = parseSessionEnd(e.response()?.errorBody()?.string())
                }
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
