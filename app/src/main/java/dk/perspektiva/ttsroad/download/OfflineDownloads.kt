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
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import dk.perspektiva.ttsroad.core.ServerUrls
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.DownloadPrefs
import dk.perspektiva.ttsroad.data.ServerCapabilities
import dk.perspektiva.ttsroad.data.TokenStore
import dk.perspektiva.ttsroad.media.TtsRoadMediaIds
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the on-disk media cache and the download queue.
 *
 * One instance per process, held by `ServiceLocator`, because [SimpleCache] refuses to open a
 * directory a second time — the playback service and the download service must share it or neither
 * works.
 *
 * Two things it deliberately does *not* do, because the policy has not been decided:
 * - evict anything automatically. The evictor is [NoOpCacheEvictor] and there is no size cap, so
 *   nothing the user listened to or downloaded disappears behind their back. The cost is that the
 *   read-through cache grows until "delete all downloads" is used.
 * - distinguish a read-through cache entry from an explicit download in terms of storage budget.
 *   Both live in one cache; only the download index knows which chapters were asked for by name.
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Same contract as the player's resolver: read the latest header per request, so signing out
    // and back in does not require rebuilding the cache, the manager or the player.
    @Volatile
    private var authHeader: String? = null

    /** Address the phone signed in on — used to point a re-keyed download at a reachable host. */
    @Volatile
    private var serverUrl: String = ""

    /**
     * Which server the cache entries belong to, once it has said so. Null until capabilities come
     * back, and on a server too old to report a `base_url` at all — see [DownloadCacheKeys].
     */
    @Volatile
    private var serverIdentity: String? = null

    private val _downloads = MutableStateFlow<Map<String, ChapterDownload>>(emptyMap())

    /** Every known download, keyed by the chapter's media id. Empty until the index has loaded. */
    val downloads: StateFlow<Map<String, ChapterDownload>> = _downloads.asStateFlow()

    private val databaseProvider: DatabaseProvider by lazy { StandaloneDatabaseProvider(context) }

    /**
     * Deliberately under `filesDir`, not `cacheDir`: the OS empties `cacheDir` under storage
     * pressure, and silently deleting the chapters someone downloaded for a flight is the exact
     * failure this feature exists to prevent.
     */
    private val cache: Cache by lazy {
        SimpleCache(File(context.filesDir, CacheDirName), NoOpCacheEvictor(), databaseProvider)
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
    private val cacheKeyFactory = CacheKeyFactory { dataSpec: DataSpec ->
        dataSpec.key ?: DownloadCacheKeys.forUrl(dataSpec.uri.toString(), serverIdentity)
    }

    /** Auth-injecting HTTP source used to fetch bytes the cache does not have. */
    private val upstreamFactory: DataSource.Factory = ResolvingDataSource.Factory(
        DefaultHttpDataSource.Factory(),
        ResolvingDataSource.Resolver { dataSpec ->
            val header = authHeader ?: return@Resolver dataSpec
            dataSpec.withAdditionalHeaders(mapOf("Authorization" to header))
        },
    )

    val downloadManager: DownloadManager by lazy {
        DownloadManager(
            context,
            databaseProvider,
            cache,
            upstreamFactory,
            // Two at a time: enough to keep a phone's link busy without starving playback of the
            // chapter the user is actually listening to.
            Executors.newFixedThreadPool(MaxParallelDownloads),
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
                        _downloads.value = _downloads.value - download.request.id
                    }
                },
            )
        }
    }

    init {
        scope.launch {
            tokenStore.session.collectLatest {
                authHeader = it.authorizationHeader
                serverUrl = it.serverUrl
            }
        }
        scope.launch {
            capabilities.collectLatest { adoptServerIdentity(DownloadCacheKeys.serverIdentity(it.serverBaseUrl)) }
        }
        // Requirements are enforced by the manager itself, so a queued chapter waits for Wi-Fi
        // rather than failing — and flipping the switch back on releases whatever was waiting,
        // with no need to queue it again.
        scope.launch {
            downloadPrefs
                .map { it.wifiOnly }
                .distinctUntilChanged()
                .collect { wifiOnly ->
                    withContext(Dispatchers.IO) {
                        downloadManager.requirements = downloadRequirements(wifiOnly)
                    }
                }
        }
        // Touching the manager is what makes it read the persisted index, which is what makes
        // yesterday's downloads show up in the chapter rows again. Done off the main thread because
        // opening the cache scans its directory, and it is not worth janking the first frame.
        scope.launch(Dispatchers.IO) { downloadManager }
    }

    /**
     * Wrap [upstream] so the player reads through the cache first.
     *
     * The caller passes its own auth-injecting source rather than this class's, so the service keeps
     * one resolver for playback and there is no second place that knows how the header is built.
     * Writes go through the same cache the downloads use, which is what makes a chapter that was
     * merely streamed replay without touching the server.
     */
    fun readThroughFactory(upstream: DataSource.Factory): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheKeyFactory(cacheKeyFactory)
            // A cache that cannot be written (full disk, revoked permission) must degrade to plain
            // streaming rather than stopping playback.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** Queue [chapter] for download. A chapter with no audio yet is silently ignored. */
    fun download(
        chapter: ChapterSummary,
        serverUrl: String?,
        origin: DownloadOrigin = DownloadOrigin.Manual,
    ) {
        val spec = chapterDownloadSpec(chapter, serverUrl, serverIdentity, origin) ?: return
        send(spec.toDownloadRequest())
    }

    /** Queue several chapters in one go — the fiction header's "download next N". */
    fun download(
        chapters: List<ChapterSummary>,
        serverUrl: String?,
        origin: DownloadOrigin = DownloadOrigin.Manual,
    ) {
        chapters.forEach { download(it, serverUrl, origin) }
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
     */
    fun applyKeepAhead(
        chapters: List<ChapterSummary>,
        currentChapterId: Int?,
        keepAhead: Int,
        fictionId: Int,
        serverUrl: String?,
    ) {
        val known = _downloads.value
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
        if (plan.isEmpty) return

        // Wrapped for the same reason resumeUnfinished is: this runs from the media service, which
        // is often in the background, and starting the download service from there is not always
        // allowed. A refused start leaves the honest state — not downloaded — rather than killing
        // playback, which is the thing the user actually asked for.
        runCatching {
            download(plan.download, serverUrl, DownloadOrigin.Auto)
            plan.release.forEach(::remove)
        }
    }

    /** Delete a chapter's audio, or cancel it if it is still downloading. */
    fun remove(chapterId: Int) {
        DownloadService.sendRemoveDownload(
            context,
            TtsRoadDownloadService::class.java,
            TtsRoadMediaIds.chapter(chapterId),
            /* foreground= */ false,
        )
    }

    /**
     * Delete every download *and* everything the read-through cache picked up while streaming.
     *
     * Both live in one cache, so this is the only honest "free the space" action available until the
     * budget question is decided.
     */
    fun removeAll() {
        DownloadService.sendRemoveAllDownloads(
            context,
            TtsRoadDownloadService::class.java,
            /* foreground= */ false,
        )
        scope.launch(Dispatchers.IO) {
            // removeAllDownloads only clears what the index knows about; chapters that were merely
            // streamed have cache spans but no download record.
            runCatching { cache.keys.toList().forEach(cache::removeResource) }
            refreshCacheBytes()
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
        runCatching {
            DownloadService.sendResumeDownloads(
                context,
                TtsRoadDownloadService::class.java,
                /* foreground= */ false,
            )
        }
    }

    private val _cacheBytes = MutableStateFlow(0L)

    /** Total bytes the media cache occupies — downloads and streamed-through audio together. */
    val cacheBytes: StateFlow<Long> = _cacheBytes.asStateFlow()

    /** Re-read the cache size. Touches the disk, so it is kept off the main thread. */
    fun refreshCacheBytes() {
        scope.launch(Dispatchers.IO) {
            _cacheBytes.value = runCatching { cache.cacheSpace }.getOrDefault(0L)
        }
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
        if (!shouldAdoptIdentity(current = serverIdentity, incoming = identity)) return
        serverIdentity = identity
        scope.launch(Dispatchers.IO) {
            // Only unscoped entries move. One already carrying a different identity belongs to
            // another server the user also downloaded from, and re-keying it here would hand its
            // audio to this one — the very collision the identity exists to prevent.
            val stale = runCatching {
                downloadManager.downloadIndex.getDownloads().use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            val request = cursor.download.request
                            val key = request.customCacheKey ?: continue
                            if (!DownloadCacheKeys.isScoped(key)) add(request)
                        }
                    }
                }
            }.getOrDefault(emptyList())

            // Unscoped spans belonging to no download record are what streaming left behind.
            // Nothing will ever read them again, so they are dropped rather than re-fetched — the
            // chapter simply streams once more if it is played.
            val indexed = stale.mapTo(mutableSetOf()) { it.customCacheKey }
            runCatching {
                orphanedCacheKeys(cache.keys, indexed).forEach(cache::removeResource)
            }

            // Wrapped like resumeUnfinished: this can run while the process is in the background
            // (the media service builds this class too) and starting the download service from
            // there is not always allowed. A row that does not make it keeps the honest state —
            // not downloaded — rather than taking the process down.
            runCatching {
                stale.forEach { request ->
                    // Removed before it is re-added, so the manager has nothing to merge the new
                    // key into; both go through the one service queue, so they stay in order.
                    DownloadService.sendRemoveDownload(
                        context,
                        TtsRoadDownloadService::class.java,
                        request.id,
                        /* foreground= */ false,
                    )
                    // The recorded URL may name an address this phone can no longer reach, so it is
                    // put back on the one signed in — the rewrite a fresh download would do.
                    send(
                        DownloadRequest.Builder(
                            request.id,
                            ServerUrls.rewriteHost(request.uri.toString(), serverUrl).toUri(),
                        )
                            .setCustomCacheKey(
                                DownloadCacheKeys.forUrl(request.uri.toString(), identity),
                            )
                            .setData(request.data)
                            .build(),
                    )
                }
            }
            refreshCacheBytes()
        }
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
            }.getOrDefault(emptyList())
            publish(loaded)
            refreshCacheBytes()
        }
    }

    private fun publish(loaded: List<Download>) {
        if (loaded.isEmpty()) return
        _downloads.value = _downloads.value + loaded.associate { it.request.id to it.toChapterDownload() }
    }

    private fun put(download: Download) {
        _downloads.value = _downloads.value + (download.request.id to download.toChapterDownload())
        refreshCacheBytes()
    }

    private fun Download.toChapterDownload(): ChapterDownload {
        val ids = decodeDownloadIds(request.data)
        return ChapterDownload(
            state = chapterDownloadState(state),
            percent = downloadPercent(percentDownloaded),
            bytesDownloaded = bytesDownloaded,
            origin = ids?.origin ?: DownloadOrigin.Manual,
            fictionId = ids?.fictionId ?: 0,
        )
    }

    private companion object {
        const val CacheDirName = "media_downloads"
        const val MaxParallelDownloads = 2
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
private fun ChapterDownloadSpec.toDownloadRequest(): DownloadRequest =
    DownloadRequest.Builder(id, url.toUri())
        .setCustomCacheKey(cacheKey)
        .setData(encodedIds())
        .build()
