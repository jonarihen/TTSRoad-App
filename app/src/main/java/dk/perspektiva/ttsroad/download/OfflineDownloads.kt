package dk.perspektiva.ttsroad.download

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadIndex
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import dk.perspektiva.ttsroad.core.ServerUrls
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.DefaultStreamingCacheBytes
import dk.perspektiva.ttsroad.data.DownloadPrefs
import dk.perspektiva.ttsroad.data.ServerCapabilities
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.TokenStore
import dk.perspektiva.ttsroad.media.TtsRoadMediaIds
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the on-disk media cache and the download queue.
 *
 * One instance per process, held by `ServiceLocator`, because [SimpleCache] refuses to open a
 * directory a second time — the playback service and the download service must share it or neither
 * works.
 *
 * There are **two** caches, and which one a byte lands in is the whole storage policy — see
 * [readThroughFactory] and the file comment on `MediaCaches.kt`:
 * - [downloadCache] holds chapters asked for by name, is written only by [DownloadManager], and
 *   keeps [NoOpCacheEvictor]. Nothing in it is ever removed automatically, which is what lets the
 *   app promise that a chapter downloaded for a flight is still there on the plane.
 * - [streamingCache] holds whatever playback read through it, and is capped by
 *   [ResizableLruCacheEvictor] from `DownloadPrefs.streamingCacheBytes`. Everything in it is
 *   re-fetchable by definition, so evicting costs a re-buffer.
 *
 * Before 0.13.0 there was one cache holding both, which is exactly why there could be no cap: an
 * LRU evictor over the shared store cannot tell the two apart and would have deleted real downloads.
 *
 * Opted in once for the class: the cache, download and datasource APIs are all still marked
 * unstable in media3 1.10.0.
 */
@OptIn(UnstableApi::class)
class OfflineDownloads(
    private val context: Context,
    tokenStore: TokenStore,
    capabilities: Flow<ServerCapabilities> = flowOf(ServerCapabilities.Baseline),
    downloadPrefs: Flow<DownloadPrefs> = flowOf(DownloadPrefs()),
    /**
     * Fetch and hold a chapter's read-along document, so a downloaded chapter can be read offline
     * as well as heard. Answers whether one is now held.
     *
     * Injected rather than reached through the service locator so the download logic stays testable
     * without a repository, and defaulted to a no-op so a caller that does not care need not say so.
     */
    private val pinReadAlong: suspend (Int) -> Boolean = { false },
    private val unpinReadAlong: (Int) -> Unit = {},
    /**
     * Forget which audio a chapter's download held (#109).
     *
     * Here rather than at the UI call sites because a download can go from a chapter row, from the
     * keep-ahead window sliding past it, or from "delete all downloads" — and a hash left behind
     * describes bytes this device no longer has. The next download of that chapter would then be
     * compared against the previous copy's hash and reported stale or fresh on no evidence.
     */
    private val forgetAudioHash: (scope: String?, chapterId: Int) -> Unit = { _, _ -> },
    private val forgetAllAudioHashes: () -> Unit = {},
    private val initializeManager: Boolean = true,
    private val replacementHash: (Int) -> String? = { null },
    private val replacementCompleted: (scope: String?, chapterId: Int, hash: String) -> Unit = { _, _, _ -> },
    private val sendRemoveAllDownloads: () -> Unit = {
        DownloadService.sendRemoveAllDownloads(context, TtsRoadDownloadService::class.java, false)
    },
    session: Flow<SessionState> = tokenStore.session,
    private val migrationIndex: DownloadIndex? = null,
    private val migrationDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Told the scope the signed-in server's audio hashes live under, whenever it changes (#316):
     * hashes are per server for the same reason request ids are.
     */
    private val audioHashScope: (scope: String?, legacyOwned: Set<Int>) -> Unit = { _, _ -> },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val readerPinLock = Any()
    private val readerPins = mutableMapOf<Int, Job>()

    /**
     * Chapters whose read-along was asked for since the server last became able to serve one (#447).
     * Not cleared by [remove], so a row still listed while its delete is in flight is not re-pinned.
     */
    private val readerPinsRequested = mutableSetOf<Int>()
    private val readAlongReady = MutableStateFlow(false)

    // Same contract as the player's resolver: read the latest header per request, so signing out
    // and back in does not require rebuilding the cache, the manager or the player.
    @Volatile
    private var audioAuth = dk.perspektiva.ttsroad.core.AudioAuthSnapshot()

    private val serverUrl: String get() = audioAuth.serverUrl

    private val identityPreferences = context.getSharedPreferences("download_server_identities", Context.MODE_PRIVATE)
    private val cacheIdentities = SessionCacheIdentity(
        knownIdentities = identityPreferences.all.mapNotNull { (address, identity) ->
            (identity as? String)?.let { address to it }
        }.toMap(),
        remember = { address, identity -> identityPreferences.edit().putString(address, identity).apply() },
    )

    @Volatile
    private var activeCapabilities = ServerCapabilities.Baseline

    private val identityMigration = DownloadIdentityMigration(
        RetainedRekeyFile(File(context.filesDir, RetainedRekeysFileName)),
    )
    private val identityMigrationLock = Any()

    @Volatile
    private var pendingMigration: IdentityMigrationAttempt? = null

    private data class IdentityMigrationAttempt(
        val identity: String,
        val session: dk.perspektiva.ttsroad.core.AudioAuthSnapshot,
        val capabilities: ServerCapabilities,
        /** Only dispatch rekeys retained for [identity]; no index read, cleanup or adoption. */
        val retainedOnly: Boolean = false,
    )

    /**
     * The identity the signed-in address was verified under, whose retained rekeys a deletion or a
     * fresh download here may touch. Never the address fallback: rekeys are only ever retained under
     * an identity a server advertised for itself.
     */
    private fun retainedScope(): String? = cacheIdentities.rememberedForServer(audioAuth.serverUrl)

    /**
     * Whether this server can serve read-along documents at all.
     *
     * False until capabilities come back, and on a server without them. Prefetching against one
     * that cannot answer would spend a request per download to be told 404 every time.
     */
    @Volatile
    private var readAlongSupported: Boolean = false

    private val _downloads = MutableStateFlow<Map<String, ChapterDownload>>(emptyMap())
    private val unfinishedReplacementDownloads = mutableSetOf<String>()

    /**
     * One download index row, whichever server it belongs to (#316).
     *
     * The index is shared by every server this phone has signed in to, and chapter ids are only
     * unique per server. [owner] is the identity the row's cache key is filed under — null for a
     * 0.8.0 key, whose owner is worked out from its URL — and is what [downloads] filters on.
     */
    private data class IndexedDownload(
        val download: ChapterDownload,
        val chapterId: Int?,
        val owner: String?,
        val uri: String,
    )

    /** Every row in the index, keyed by request id. Touched on the main thread only. */
    private var rows: Map<String, IndexedDownload> = emptyMap()

    /** Whether [rows] holds the whole persisted index, not only what the manager reported so far. */
    private var indexLoaded = false

    /** The signed-in server's rows: chapter id -> the request id it is downloaded under. */
    @Volatile
    private var visibleRequestIds: Map<Int, String> = emptyMap()

    /**
     * Keep-ahead's off-switch release could not reach the download service, most likely because the
     * process was in the background. Retried from [resumeUnfinished], which runs in the foreground.
     */
    @Volatile
    private var keepAheadReleasePending = false
    private val keepAheadRetry = MutableStateFlow(0)
    private val keepAheadSetting = MutableStateFlow(Int.MAX_VALUE)
    private val keepAheadReleaseRequested: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Bumped each time the keep-ahead setting changes. The media service keys its plan on this, so
     * switching the feature off and on again — even back to the same size — re-plans the window.
     */
    val keepAheadGeneration = MutableStateFlow(0L)

    /**
     * The signed-in server's downloads, keyed by the chapter's media id. Empty until the index has
     * loaded. Another server's row for the same chapter number is not here (#316): it would show as
     * downloaded, keep-ahead would count it as handled, and a remove would delete it.
     */
    val downloads: StateFlow<Map<String, ChapterDownload>> = _downloads.asStateFlow()

    private val databaseProvider: DatabaseProvider by lazy { StandaloneDatabaseProvider(context) }

    /**
     * Chapters the user asked for by name. Never evicted.
     *
     * Deliberately under `filesDir`, not `cacheDir`: the OS empties `cacheDir` under storage
     * pressure, and silently deleting the chapters someone downloaded for a flight is the exact
     * failure this feature exists to prevent.
     */
    private val downloadCache: Cache by lazy {
        SimpleCache(File(context.filesDir, CacheDirName), NoOpCacheEvictor(), databaseProvider)
    }

    /** The ceiling on [streamingCache], held here so Settings can move it without a restart. */
    private val streamingEvictor = ResizableLruCacheEvictor(DefaultStreamingCacheBytes)

    /**
     * Whatever playback streamed through. Capped, and safe to lose.
     *
     * Also under `filesDir` rather than `cacheDir`, despite being the disposable one. The OS empties
     * `cacheDir` at moments it chooses, and this cache existing is what makes rewinding an hour of
     * overnight playback free — having it vanish under storage pressure would turn that back into an
     * hour of re-fetching, silently. This app decides what to drop, and says so in Settings.
     */
    private val streamingCache: Cache by lazy {
        SimpleCache(File(context.filesDir, StreamCacheDirName), streamingEvictor, databaseProvider)
    }

    /**
     * Cache identity for a request. Falls back to the URL's path rather than the whole URL, so a
     * download survives the user signing in against a different address for the same server — see
     * [DownloadCacheKeys].
     *
     * The fallback matters more than the explicit key: controllers hand media items back across the
     * binder stripped of their local configuration, so a played item often arrives with no key at
     * all and the URL is the only thing left to derive one from.
     */
    private val scopedCacheKeyFactory = CacheKeyFactory { dataSpec: DataSpec ->
        dataSpec.key?.takeIf(DownloadCacheKeys::isScoped) ?: run {
            val address = serverUrl
            val url = dataSpec.uri.toString()
            val key = DownloadCacheKeys.forUrl(url, cacheIdentities.forServer(address, activeCapabilities))
            val fallback = DownloadCacheKeys.forUrl(url, cacheIdentities.fallbackForServer(address))
            fun cached(candidate: String) = downloadCache.isCached(candidate, dataSpec.position, 1) ||
                streamingCache.isCached(candidate, dataSpec.position, 1)
            if (key != fallback && !cached(key) && cached(fallback)) fallback else key
        }
    }

    private val cacheKeyFactory = CacheKeyFactory { dataSpec: DataSpec ->
        val key = scopedCacheKeyFactory.buildCacheKey(dataSpec)
        if (downloadCache.isCached(key, dataSpec.position, 1) || streamingCache.isCached(key, dataSpec.position, 1)) {
            key
        } else {
            legacyDownloadKey(dataSpec.uri.toString(), serverUrl, dataSpec.position) ?: key
        }
    }

    private fun legacyDownloadKey(url: String, address: String, position: Long?): String? {
        if (cacheIdentities.fallbackForServer(address) == null) return null
        val key = DownloadCacheKeys.forUrl(url)
        if (position != null && !downloadCache.isCached(key, position, 1)) return null
        return runCatching {
            DefaultDownloadIndex(databaseProvider).getDownloads().use { cursor ->
                val owners = buildList {
                    while (cursor.moveToNext()) {
                        val request = cursor.download.request
                        val requestKey = request.customCacheKey ?: request.uri.toString()
                        if (requestKey == key || decodeDownloadIds(request.data)?.invalidationKeys?.contains(key) == true) {
                            add(request.uri.toString())
                        }
                    }
                }
                key.takeIf {
                    owners.isNotEmpty() && owners.all { owner ->
                        cacheIdentities.ownsLegacyUrl(address, owner, activeCapabilities)
                    }
                }
            }
        }.getOrNull()
    }

    /** Auth-injecting HTTP source used to fetch bytes the cache does not have. */
    private val upstreamFactory: DataSource.Factory = ResolvingDataSource.Factory(
        DefaultHttpDataSource.Factory(),
        ResolvingDataSource.Resolver { dataSpec ->
            val snapshot = audioAuth
            snapshot.resolve(dataSpec)
        },
    )

    val downloadManager: DownloadManager by lazy {
        DownloadManager(
            context,
            DefaultDownloadIndex(databaseProvider),
            staleAudioDownloaderFactory(
                downloadCache,
                streamingCache,
                upstreamFactory,
                Executors.newFixedThreadPool(MaxParallelDownloads),
            ),
        ).apply {
            maxParallelDownloads = MaxParallelDownloads
            addListener(
                object : DownloadManager.Listener {
                    override fun onInitialized(downloadManager: DownloadManager) {
                        // Fired once the persisted index has been read — this is what makes a
                        // download that was in flight when the app died reappear in the UI.
                        publish(downloadManager.currentDownloads)
                        loadIndex()
                    }

                    override fun onDownloadChanged(
                        downloadManager: DownloadManager,
                        download: Download,
                        finalException: Exception?,
                    ) {
                        put(download)
                    }

                    override fun onDownloadRemoved(
                        downloadManager: DownloadManager,
                        download: Download,
                    ) {
                        val ids = decodeDownloadIds(download.request.data)
                        val unfinishedReplacement = unfinishedReplacementDownloads.remove(download.request.id)
                        if (ids?.replacementHash == null || !unfinishedReplacement) {
                            (ids?.chapterId ?: chapterIdOfDownload(download.request.id))
                                ?.let { forgetAudioHash(hashScopeFor(download.request), it) }
                        }
                        rows = rows - download.request.id
                        republish()
                    }
                },
            )
        }
    }

    // Declared before [init]: its collectors call [refreshCacheBytes] on Dispatchers.IO, which can
    // run before property initialisers further down the class have.
    private val _downloadCacheBytes = MutableStateFlow(0L)
    private val _streamingCacheBytes = MutableStateFlow(0L)

    init {
        scope.launch {
            combine(session, capabilities) { current, discovered -> current to discovered }
                .collectLatest { (current, discovered) ->
                    val snapshot = dk.perspektiva.ttsroad.core.AudioAuthSnapshot(current.serverUrl, current.authorizationHeader)
                    if (snapshot != audioAuth) pendingMigration = null
                    activeCapabilities = discovered
                    cacheIdentities.forServer(current.serverUrl, discovered)
                    audioAuth = snapshot
                    readAlongSupported = discovered.discoveryBaseUrl == current.serverUrl && discovered.readAlong
                    readAlongReady.value = current.isLoggedIn && readAlongSupported
                    republish()
                    val identity = cacheIdentities.advertisedForServer(current.serverUrl, discovered)
                    if (current.isLoggedIn) adoptServerIdentity(identity) else pendingMigration = null
                }
        }
        // Sign-out and a 401 delete every pinned document but keep the audio, and a download queued
        // before discovery resolved was never pinned at all (#447): pin whatever is on the list once
        // the server can answer. The repository's pin is a no-op for a document already held.
        scope.launch {
            combine(readAlongReady, _downloads) { ready, downloads -> ready to downloads }
                .collect { (ready, downloads) ->
                    synchronized(readerPinLock) {
                        if (!ready) {
                            readerPinsRequested.clear()
                            return@collect
                        }
                        val present = downloads.filterValues {
                            it.state != ChapterDownloadState.Failed && it.state != ChapterDownloadState.Removing
                        }.keys.mapNotNullTo(mutableSetOf()) { TtsRoadMediaIds.chapterId(it) }
                        readerPinsRequested.retainAll(present)
                        (present - readerPinsRequested).forEach(::pinReadAlongFor)
                    }
                }
        }
        // Requirements are enforced by the manager itself, so a queued chapter waits for Wi-Fi
        // rather than failing — and flipping the switch back on releases whatever was waiting,
        // with no need to queue it again.
        scope.launch {
            downloadPrefs
                .map { it.wifiOnly }
                .distinctUntilChanged()
                .collect { wifiOnly ->
                    if (initializeManager) {
                        withContext(Dispatchers.IO) {
                            downloadManager.requirements = downloadRequirements(wifiOnly)
                        }
                    }
                }
        }
        scope.launch {
            downloadPrefs
                .map { it.keepAheadChapters }
                .distinctUntilChanged()
                .collect { keepAhead ->
                    keepAheadSetting.value = keepAhead
                    keepAheadGeneration.value++
                }
        }
        scope.launch {
            combine(keepAheadSetting, _downloads, keepAheadRetry) { keepAhead, downloads, _ ->
                keepAheadReleaseWhenOff(keepAhead, downloads).filterNot { it in keepAheadReleaseRequested }
            }
                .collect { release ->
                    val present = _downloads.value.keys.mapNotNullTo(mutableSetOf()) { TtsRoadMediaIds.chapterId(it) }
                    keepAheadReleaseRequested.retainAll(present)
                    val sent = sentReleases(release, ::remove)
                    keepAheadReleaseRequested += sent
                    keepAheadReleasePending = sent.size < release.size
                }
        }
        // The cap is applied to the live evictor rather than only at construction, so lowering it
        // frees space now — someone who has just chosen a smaller number is usually trying to get
        // disk back, and "restart the app" is not an answer to that.
        scope.launch {
            downloadPrefs
                .map { it.streamingCacheBytes }
                .distinctUntilChanged()
                .collect { bytes ->
                    withContext(Dispatchers.IO) {
                        runCatching { streamingEvictor.setMaxBytes(streamingCache, bytes) }
                        refreshCacheBytes()
                    }
                }
        }
        // Touching the manager is what makes it read the persisted index, which is what makes
        // yesterday's downloads show up in the chapter rows again. Done off the main thread because
        // opening the cache scans its directory, and it is not worth janking the first frame.
        //
        // The split's one-off sweep goes here, after it, rather than in the cache's own lazy
        // initialiser: the sweep needs the download index, reaching the index opens the manager, and
        // opening the manager needs the cache. Hanging that off the cache's initialiser would have
        // it re-enter the very lazy that is still running.
        //
        // The false path is test-only dependency control for settings-layout tests, which need the
        // cache-size flows but never exercise Media3. Production callers keep the default true.
        if (initializeManager) {
            scope.launch(Dispatchers.IO) {
                downloadManager
                dropStrandedStreamSpans(downloadCache)
            }
        }
    }

    /** Cancel the collectors of a test instance that was explicitly forbidden from opening Media3. */
    fun closeWithoutManagerForTest() {
        check(!initializeManager) { "This test instance owns a real DownloadManager" }
        scope.cancel()
    }

    internal fun releaseForTest() {
        check(!initializeManager)
        scope.cancel()
        downloadManager.release()
        downloadCache.release()
        streamingCache.release()
    }

    /**
     * Wrap [upstream] so the player reads through both caches before the network.
     *
     * The caller passes its own auth-injecting source rather than this class's, so the service keeps
     * one resolver for playback and there is no second place that knows how the header is built.
     * A read miss is written to [streamingCache] only, which is what makes a chapter that was merely
     * streamed replay without touching the server — and what keeps replaying a *downloaded* chapter
     * from spending capped space on bytes that are already on disk.
     */
    fun readThroughFactory(upstream: DataSource.Factory): DataSource.Factory =
        readThroughFactory(
            downloadCache = downloadCache,
            streamingCache = streamingCache,
            upstream = upstream,
            cacheKeyFactory = cacheKeyFactory,
            streamingCacheKeyFactory = scopedCacheKeyFactory,
        )

    /** Queue [chapter] for download. A chapter with no audio yet is silently ignored. */
    fun download(
        chapter: ChapterSummary,
        serverUrl: String?,
        origin: DownloadOrigin = DownloadOrigin.Manual,
        replaceExisting: Boolean = false,
    ) {
        val identity = cacheIdentities.forServer(serverUrl.orEmpty(), activeCapabilities)
        val spec = chapterDownloadSpec(chapter, serverUrl, identity, origin) ?: return
        val hash = if (replaceExisting) replacementHash(spec.chapterId) ?: return else null
        val invalidationKeys = if (replaceExisting) {
            buildSet {
                add(spec.cacheKey)
                add(DownloadCacheKeys.forUrl(spec.url, cacheIdentities.fallbackForServer(serverUrl.orEmpty())))
                legacyDownloadKey(spec.url, serverUrl.orEmpty(), null)?.let(::add)
            }
        } else emptySet()
        val request = spec.copy(id = requestIdFor(spec.chapterId, identity)).toDownloadRequest(hash, invalidationKeys)
        // A replacement re-pins rather than unpinning: the chapter stays downloaded throughout, so
        // its read-along document must stay held while the new audio bytes arrive.
        synchronized(readerPinLock) {
            // Asked for again: an earlier deletion must not keep the migration from moving it, and a
            // rekey retained for it must not later overwrite it. Dropped only once the add went out.
            identityMigration.restore(request.id, retainedScope()) { send(request) }
            chapter.resolvedChapterId.takeIf { it > 0 }?.let(::pinReadAlongFor)
        }
    }

    private fun pinReadAlongFor(chapterId: Int) {
        if (!readAlongSupported) return
        readerPinsRequested += chapterId
        readerPins.remove(chapterId)?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) { runCatching { pinReadAlong(chapterId) } }
        readerPins[chapterId] = job
        job.start()
    }

    /** Queue several chapters in one go — the fiction header's "download next N". */
    fun download(
        chapters: List<ChapterSummary>,
        serverUrl: String?,
        origin: DownloadOrigin = DownloadOrigin.Manual,
        replaceExisting: Boolean = false,
    ) {
        chapters.forEach { download(it, serverUrl, origin, replaceExisting) }
    }

    /**
     * Move the keep-ahead window to [currentChapterId] within [chapters].
     *
     * Called from the media service when the chapter changes. Everything it decides lives in
     * [autoDownloadPlan]; this only supplies the state that plan needs and posts the result to the
     * download service.
     *
     * A chapter already downloaded by hand inside the window is left exactly as it is — it is
     * counted as handled, so it is not re-queued, and its manual origin is not overwritten. That
     * matters on the way out: the window moving past it must not delete it.
     *
     * Answers whether the plan is settled. It is not while the feature has been switched off since
     * the caller read it, while a service start was refused, or while an earlier release is still
     * removing rows the plan would otherwise count as handled — the caller re-plans next time.
     */
    fun applyKeepAhead(
        chapters: List<ChapterSummary>,
        currentChapterId: Int?,
        keepAhead: Int,
        fictionId: Int,
        serverUrl: String?,
    ): Boolean {
        if (keepAheadSetting.value <= 0 || keepAheadSetting.value != keepAhead) return false
        val known = _downloads.value
        val settled = known.none { (mediaId, download) ->
            download.origin == DownloadOrigin.Auto && (
                download.state == ChapterDownloadState.Removing ||
                    TtsRoadMediaIds.chapterId(mediaId) in keepAheadReleaseRequested
                )
        }
        // A failed download is not "handled" — leaving it out is what lets the window retry it.
        // A failed *manual* one is left alone even so: re-queuing it here would rewrite its record
        // as an automatic download, and the window would then be entitled to delete something the
        // user asked for by name. Retrying it stays the chapter row's job.
        val handled = known
            .filterValues { it.state != ChapterDownloadState.Failed || it.origin == DownloadOrigin.Manual }
            .keys
            .mapNotNullTo(mutableSetOf()) { TtsRoadMediaIds.chapterId(it) }
        val autoDownloaded = known
            .filterValues { it.origin == DownloadOrigin.Auto && it.fictionId == fictionId }
            .keys
            .mapNotNullTo(mutableSetOf()) { TtsRoadMediaIds.chapterId(it) }

        val plan = autoDownloadPlan(
            chapters = chapters,
            currentChapterId = currentChapterId,
            keepAhead = keepAhead,
            handled = handled,
            autoDownloaded = autoDownloaded,
        )
        if (plan.isEmpty) return settled

        // Wrapped for the same reason resumeUnfinished is: this runs from the media service, which
        // is often in the background, and starting the download service from there is not always
        // allowed. A refused start leaves the honest state — not downloaded — rather than killing
        // playback, which is the thing the user actually asked for.
        return runCatching {
            download(plan.download, serverUrl, DownloadOrigin.Auto)
            // A false remove sent nothing: the window is not settled, and is planned again.
            plan.release.forEach { check(remove(it)) { "chapter $it was not released" } }
        }.isSuccess && settled
    }

    /**
     * Delete a chapter's audio and its read-along document, or cancel it if it is still downloading.
     *
     * Answers false, having changed nothing, only when a migration rekey retained for the chapter
     * could not be durably dropped — deleting anyway would let a restart queue it straight back.
     */
    fun remove(chapterId: Int): Boolean {
        synchronized(readerPinLock) {
            // The audio hash is forgotten when the download service reports the removal (#288), not
            // here: an unfinished replacement must keep the hash it was verifying against.
            // Committed together with the remove: a rekey retained from a failed migration dispatch
            // would otherwise queue this chapter again on the next retry.
            // This server's row only: another server's chapter with the same number is not ours to
            // delete (#316).
            val requestId = visibleRequestIds[chapterId]
                ?: requestIdFor(chapterId, cacheIdentities.forServer(serverUrl, activeCapabilities))
            val removed = identityMigration.forget(requestId, retainedScope()) {
                DownloadService.sendRemoveDownload(
                    context,
                    TtsRoadDownloadService::class.java,
                    requestId,
                    /* foreground= */ false,
                )
            }
            if (!removed) return false
            readerPins.remove(chapterId)?.cancel()
            unpinReadAlong(chapterId)
        }
        return true
    }

    /**
     * Delete every download *and* everything the read-through cache picked up while streaming.
     *
     * Still one button, because "free the space" is one intent. The two stores are separated for
     * eviction policy, not to make emptying them a two-step chore, and [clearStreamingCache] is
     * there for anyone who only wants the disposable half back.
     */
    fun removeAll(): Boolean {
        synchronized(readerPinLock) {
            val chapterIds = readerPins.keys + rows.values.mapNotNull { it.chapterId }
            // Global, like the remove-all command and the cache sweep below: every server's retained
            // rekeys go too, or one stranded on another server would re-download what was deleted.
            if (!identityMigration.forgetAll { sendRemoveAllDownloads() }) return false
            readerPins.values.forEach { it.cancel() }
            readerPins.clear()
            chapterIds.forEach(unpinReadAlong)
            forgetAllAudioHashes()
        }
        scope.launch(Dispatchers.IO) {
            // removeAllDownloads only clears what the index knows about; a download cache upgraded
            // from before the split can still hold spans no record claims.
            runCatching { downloadCache.keys.toList().forEach(downloadCache::removeResource) }
            runCatching { streamingCache.keys.toList().forEach(streamingCache::removeResource) }
            refreshCacheBytes()
        }
        return true
    }

    /**
     * Drop everything that was merely streamed, keeping every download.
     *
     * The action the split makes possible: before it, the only way to reclaim streamed audio was to
     * delete the chapters someone had downloaded for a flight along with it.
     */
    fun clearStreamingCache() {
        scope.launch(Dispatchers.IO) {
            runCatching { streamingCache.keys.toList().forEach(streamingCache::removeResource) }
            refreshCacheBytes()
        }
    }

    /**
     * Retry a keep-ahead release the download service refused, now that the app is in the foreground.
     * Called on every return to the foreground, not only at launch, because a stopped activity that
     * comes back is not created again.
     */
    fun retryPendingKeepAheadRelease() {
        if (keepAheadReleasePending) keepAheadRetry.value++
    }

    fun retryPendingIdentityMigration() {
        if (pendingMigration == null) armRetainedRekeys()
        val attempt = pendingMigration ?: return
        if (attempt.session != audioAuth) {
            pendingMigration = null
            return
        }
        if (initializeManager || migrationIndex != null) {
            scope.launch(migrationDispatcher) { performIdentityMigration(attempt) }
        }
    }

    /**
     * Restart whatever was still in flight when the app was last killed.
     *
     * Called from the activity, so the process is in the foreground and starting the service is
     * allowed. Downloads are not resumed from the background on purpose: an audiobook is not worth
     * a background service start, and the user will open the app before the next drive anyway.
     */
    fun resumeUnfinished() {
        retryPendingKeepAheadRelease()
        retryPendingIdentityMigration()
        runCatching {
            DownloadService.sendResumeDownloads(
                context,
                TtsRoadDownloadService::class.java,
                /* foreground= */ false,
            )
        }
    }

    /** Bytes held by chapters downloaded on purpose. Nothing reclaims these but the user. */
    val downloadCacheBytes: StateFlow<Long> = _downloadCacheBytes.asStateFlow()

    /** Bytes held by audio that was streamed through. Capped, and evicted oldest-first. */
    val streamingCacheBytes: StateFlow<Long> = _streamingCacheBytes.asStateFlow()

    /**
     * Both together, which is what the phone's storage settings would show.
     *
     * Kept alongside the split figures rather than replaced by them: "how much is this app using"
     * is still a question with one answer, and it is the one worth leading with.
     */
    val cacheBytes: StateFlow<Long> = combine(
        _downloadCacheBytes,
        _streamingCacheBytes,
        Long::plus,
    ).stateIn(scope, SharingStarted.Eagerly, 0L)

    /** Re-read both cache sizes. Touches the disk, so it is kept off the main thread. */
    fun refreshCacheBytes() {
        scope.launch(Dispatchers.IO) {
            _downloadCacheBytes.value = runCatching { downloadCache.cacheSpace }.getOrDefault(0L)
            _streamingCacheBytes.value = runCatching { streamingCache.cacheSpace }.getOrDefault(0L)
        }
    }

    /**
     * Everything the download cache holds that no download record claims.
     *
     * Runs once, when the download cache is first opened. An install upgraded from before the split
     * carries a store with both kinds of audio in it and no way to tell them apart except this: the
     * index names every chapter someone asked for by name, and the rest is what playback left behind
     * on its way past. Those bytes are now unreachable — read-through looks in the streaming cache —
     * so they are freed rather than left to sit there forever, which is the failure the cap exists to
     * end.
     *
     * A failed index read aborts the whole sweep. An unopenable database answers "nothing is
     * downloaded", and acting on that would delete every download on the phone.
     */
    private fun dropStrandedStreamSpans(cache: Cache) {
        val indexed = runCatching {
            downloadManager.downloadIndex.getDownloads().use { cursor ->
                buildSet<String?> {
                    while (cursor.moveToNext()) add(cursor.download.request.customCacheKey)
                }
            }
        }.getOrNull() ?: return

        runCatching {
            strandedStreamKeys(cache.keys.toList(), indexed).forEach(cache::removeResource)
        }
        refreshCacheBytes()
    }

    private fun send(request: DownloadRequest) {
        DownloadService.sendAddDownload(
            context,
            TtsRoadDownloadService::class.java,
            request,
            /* foreground= */ false,
        )
    }

    /**
     * Adopt [identity] and move anything already on disk into its keyspace.
     *
     * 0.8.0 keyed the cache on the bare path, so every download made with it is filed under a key
     * this build no longer computes. Left alone those entries would be invisible to playback while
     * still counting as downloaded in the UI, and their bytes would be unreclaimable short of
     * "delete all downloads" — so each one is removed and queued again under the new key, which
     * costs a re-download but keeps the rows honest.
     *
     * Only ever migrates *towards* an identity. A capabilities refresh that comes back without one
     * (an unreachable server answering as [ServerCapabilities.Baseline]) must not undo the move and
     * start the whole library downloading a second time.
     */
    private fun adoptServerIdentity(identity: String?) {
        if (!shouldAdoptIdentity(current = identityMigration.identity, incoming = identity)) {
            pendingMigration = null
            armRetainedRekeys()
            if (pendingMigration != null && (initializeManager || migrationIndex != null)) retryPendingIdentityMigration()
            return
        }
        val attempt = IdentityMigrationAttempt(identity ?: return, audioAuth, activeCapabilities)
        pendingMigration = attempt
        if (initializeManager || migrationIndex != null) retryPendingIdentityMigration()
    }

    /**
     * Arm a dispatch of the rekeys retained for the signed-in address's verified identity.
     *
     * Retained rekeys survive a restart on disk, but a full migration only starts when discovery
     * advertises an identity — and a first discovery that transiently answers as Baseline would
     * leave them waiting for a whole process. The identity remembered for this address is enough to
     * finish what was already verified and started; only that identity's entries are dispatched,
     * never another server's.
     */
    private fun armRetainedRekeys() {
        val session = audioAuth
        if (session.authorizationHeader == null) return
        val identity = cacheIdentities.rememberedForServer(session.serverUrl) ?: return
        if (!identityMigration.hasRetained(identity)) return
        pendingMigration = IdentityMigrationAttempt(identity, session, activeCapabilities, retainedOnly = true)
    }

    private fun performIdentityMigration(attempt: IdentityMigrationAttempt): Boolean =
        synchronized(identityMigrationLock) {
            if (pendingMigration !== attempt || audioAuth != attempt.session) return@synchronized false
            val isCurrent = { pendingMigration === attempt && audioAuth == attempt.session }
            val rekey: (DownloadRequest) -> Unit = { request -> rekey(attempt, request) }
            val migrated = if (attempt.retainedOnly) {
                identityMigration.dispatchRetained(
                    attempt.identity,
                    migrationIndex ?: downloadManager.downloadIndex,
                    isCurrent,
                    rekey,
                )
            } else {
                identityMigration.migrate(
                    incoming = attempt.identity,
                    index = migrationIndex ?: downloadManager.downloadIndex,
                    cache = downloadCache,
                    isCurrent = isCurrent,
                    ownsLegacyRequest = { request ->
                        cacheIdentities.ownsLegacyUrl(attempt.session.serverUrl, request.uri.toString(), attempt.capabilities)
                    },
                    rekey = rekey,
                )
            }
            if (migrated && pendingMigration === attempt) pendingMigration = null
            if (migrated) refreshCacheBytes()
            migrated
        }

    private fun rekey(attempt: IdentityMigrationAttempt, request: DownloadRequest) {
        check(audioAuth == attempt.session && pendingMigration === attempt)
        DownloadService.sendRemoveDownload(
            context,
            TtsRoadDownloadService::class.java,
            request.id,
            false,
        )
        send(
            DownloadRequest.Builder(
                request.id,
                ServerUrls.rewriteAudioUrlOrNull(request.uri.toString(), attempt.session.serverUrl)?.toUri() ?: request.uri,
            )
                .setCustomCacheKey(
                    DownloadCacheKeys.forUrl(request.uri.toString(), attempt.identity),
                )
                .setData(request.data)
                .build(),
        )
    }

    /** Read every persisted download, including the completed ones the manager does not hold. */
    private fun loadIndex() {
        scope.launch(Dispatchers.IO) {
            val loaded = runCatching {
                downloadManager.downloadIndex.getDownloads().use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(cursor.download)
                    }
                }
            }.getOrNull()
            withContext(Dispatchers.Main.immediate) {
                publish(loaded.orEmpty().filter { it.request.id !in rows })
                // A failed read leaves [requestIdFor] asking the index itself.
                if (loaded != null) indexLoaded = true
            }
            refreshCacheBytes()
        }
    }

    private fun publish(loaded: List<Download>) {
        if (loaded.isEmpty()) return
        loaded.forEach(::recordReplacement)
        rows = rows + loaded.associate { it.request.id to it.toIndexedDownload() }
        republish()
    }

    private fun put(download: Download) {
        recordReplacement(download)
        rows = rows + (download.request.id to download.toIndexedDownload())
        republish()
        refreshCacheBytes()
    }

    /** Recompute [downloads] from every row: the signed-in server's only, under bare media ids. */
    private fun republish() {
        val visible = mutableMapOf<String, ChapterDownload>()
        val ids = mutableMapOf<Int, String>()
        for ((requestId, row) in rows) {
            val chapterId = row.chapterId ?: continue
            if (!ownedByActiveServer(row.owner, row.uri)) continue
            // One server should never hold two rows for a chapter; if it does, show the bare one,
            // which is also the one [requestIdFor] answers with.
            if (chapterId in ids && isScopedDownloadId(requestId)) continue
            ids[chapterId] = requestId
            visible[TtsRoadMediaIds.chapter(chapterId)] = row.download
        }
        visibleRequestIds = ids
        _downloads.value = visible
        // Before scoping every download had a bare id, so a legacy hash is the bare row owner's.
        audioHashScope(activeHashScope(), ids.filterValues { !isScopedDownloadId(it) }.keys)
    }

    /**
     * Whether a row filed under [owner] belongs to the server signed in now: under the identity that
     * server advertised, under its address fallback, or — for a 0.8.0 key that names no server —
     * when its URL is one of this server's.
     */
    private fun ownedByActiveServer(owner: String?, uri: String): Boolean {
        val address = audioAuth.serverUrl
        if (owner == null) return cacheIdentities.ownsLegacyUrl(address, uri, activeCapabilities)
        return owner == cacheIdentities.rememberedForServer(address) || owner == cacheIdentities.fallbackForServer(address)
    }

    private fun ownedByActiveServer(request: DownloadRequest): Boolean =
        ownedByActiveServer(request.ownerIdentity(), request.uri.toString())

    /**
     * The request id [chapterId] is downloaded under on the server filed under [identity] (#316).
     *
     * Media3 keys the whole index on the request id, so two servers' chapter 42 cannot share
     * `chapter:42`: the second download would merge over the first server's row, and a remove would
     * delete it. The bare id stays with whoever holds it — nothing already on disk is re-keyed or
     * downloaded again — and a server that finds it held by another server's row, or owed to another
     * server's retained rekey, uses [scopedDownloadId].
     */
    private fun requestIdFor(chapterId: Int, identity: String?): String {
        visibleRequestIds[chapterId]?.let { return it }
        val bare = TtsRoadMediaIds.chapter(chapterId)
        if (identity == null) return bare
        val scoped = scopedDownloadId(chapterId, identity)
        val bareOwned: Boolean?
        val scopedHeld: Boolean
        if (indexLoaded) {
            // Every index row is in memory once the index has loaded, so a tap or a keep-ahead pass
            // on the main thread does not have to query SQLite to tell whose the bare id is.
            bareOwned = rows[bare]?.let { ownedByActiveServer(it.owner, it.uri) }
            scopedHeld = scoped in rows
        } else {
            val index = DefaultDownloadIndex(databaseProvider)
            // An unreadable index is the one case that cannot tell whose the bare row is, and only
            // the scoped id is certain not to land on another server's.
            val (bareRow, scopedRow) = runCatching { index.getDownload(bare) to index.getDownload(scoped) }
                .getOrElse { return scoped }
            bareOwned = bareRow?.let { ownedByActiveServer(it.request) }
            scopedHeld = scopedRow != null
        }
        return when {
            scopedHeld -> scoped
            bareOwned == null -> if (identityMigration.isRetainedElsewhere(bare, retainedScope())) scoped else bare
            bareOwned -> bare
            else -> scoped
        }
    }

    /**
     * Which server's audio hashes a row's events belong to: the signed-in server's scope for its own
     * rows, whichever of its identities a row is filed under, and otherwise the identity the row
     * names. A 0.8.0 row of another server names none and lands in the unscoped legacy record.
     */
    private fun hashScopeFor(request: DownloadRequest): String? =
        if (ownedByActiveServer(request)) activeHashScope() else request.ownerIdentity()

    /** The scope the signed-in server's audio hashes are kept under (#316). */
    private fun activeHashScope(): String? {
        val address = audioAuth.serverUrl
        return cacheIdentities.rememberedForServer(address) ?: cacheIdentities.fallbackForServer(address)
    }

    private fun recordReplacement(download: Download) {
        val ids = decodeDownloadIds(download.request.data)
        val hash = ids?.replacementHash
        when {
            hash == null -> unfinishedReplacementDownloads.remove(download.request.id)
            download.state == Download.STATE_COMPLETED -> {
                unfinishedReplacementDownloads.remove(download.request.id)
                replacementCompleted(hashScopeFor(download.request), ids.chapterId, hash)
            }
            download.state != Download.STATE_REMOVING -> unfinishedReplacementDownloads.add(download.request.id)
        }
    }

    private fun Download.toIndexedDownload() = IndexedDownload(
        download = toChapterDownload(),
        chapterId = decodeDownloadIds(request.data)?.chapterId ?: chapterIdOfDownload(request.id),
        owner = request.ownerIdentity(),
        uri = request.uri.toString(),
    )

    private fun DownloadRequest.ownerIdentity(): String? = customCacheKey?.let(DownloadCacheKeys::identityOf)

    private fun Download.toChapterDownload(): ChapterDownload {
        val ids = decodeDownloadIds(request.data)
        return ChapterDownload(
            state = chapterDownloadState(state),
            percent = downloadPercent(percentDownloaded),
            bytesDownloaded = bytesDownloaded,
            origin = ids?.origin ?: DownloadOrigin.Manual,
            fictionId = ids?.fictionId ?: 0,
            isReplacement = ids?.replacementHash != null,
        )
    }

    private companion object {
        const val CacheDirName = "media_downloads"

        /**
         * A new directory, so the split needs no data migration: what is already on disk stays the
         * download cache and the streaming one starts empty. The cost is one re-buffer of whatever
         * had been streamed, and the alternative — sorting a mixed store into two — cannot be done,
         * because nothing on disk records which kind a span was.
         */
        const val StreamCacheDirName = "media_stream_cache"
        const val MaxParallelDownloads = 2

        /** Rekeys the identity migration has yet to dispatch; see [RetainedRekeyStore]. */
        const val RetainedRekeysFileName = "download_migration_rekeys.json"
    }
}

/**
 * The Media3 request for this spec.
 *
 * Lives here rather than on [ChapterDownloadSpec] because parsing a `Uri` is the one part of
 * building a request that cannot run in a JVM unit test — keeping it out of the spec is what leaves
 * the id, host and cache-key decisions testable.
 */
@OptIn(UnstableApi::class)
private fun ChapterDownloadSpec.toDownloadRequest(
    replacementHash: String? = null,
    invalidationKeys: Set<String> = emptySet(),
): DownloadRequest =
    DownloadRequest.Builder(id, url.toUri())
        .setCustomCacheKey(cacheKey)
        .setData(encodedIds(replacementHash, invalidationKeys))
        .build()
