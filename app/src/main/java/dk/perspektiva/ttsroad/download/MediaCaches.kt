package dk.perspektiva.ttsroad.download

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheSpan
import dk.perspektiva.ttsroad.data.StreamingCacheUnlimited
import java.util.TreeSet

/**
 * Two caches, and the rule that keeps them apart.
 *
 * Until 0.12.0 there was one, holding both kinds of audio at once: chapters the user downloaded by
 * name, and whatever playback happened to stream through it. That is why the evictor was
 * [androidx.media3.datasource.cache.NoOpCacheEvictor] and why there could be no size cap — Media3's
 * LRU evictor cannot tell a downloaded span from a streamed one, because they are the same keys in
 * the same store, so capping the shared cache would silently delete chapters someone downloaded for
 * a flight. A chapter that was offline yesterday and is not offline in a tunnel today is the worst
 * failure this feature has, and no amount of free disk is worth it.
 *
 * Splitting them is what makes a cap possible:
 *
 * - the **download cache** keeps [androidx.media3.datasource.cache.NoOpCacheEvictor] and is written
 *   only by `DownloadManager`. Nothing in it is ever removed automatically.
 * - the **streaming cache** is written only by playback reading through it, and is capped by
 *   [ResizableLruCacheEvictor]. Everything in it is by definition re-fetchable, so evicting costs a
 *   re-buffer and nothing else.
 */

/**
 * Read through the download cache first, then the streaming cache, then [upstream].
 *
 * The order is the whole design. The download cache is outer and **read-only** — its bytes are put
 * there by `DownloadManager` and must not be duplicated into the evictable cache just because
 * something played them. The streaming cache sits underneath it and is the only one a read miss
 * writes to, so playing a chapter that is already downloaded costs nothing and does not push
 * anything else out of the capped store.
 *
 * `CacheDataSource` chains, so this is a factory composition rather than new logic anywhere near the
 * player: the service still hands in its own auth-injecting source and still knows nothing about
 * either cache.
 */
@OptIn(UnstableApi::class)
internal fun readThroughFactory(
    downloadCache: Cache,
    streamingCache: Cache,
    upstream: DataSource.Factory,
    cacheKeyFactory: CacheKeyFactory,
): DataSource.Factory {
    val throughStreamingCache = CacheDataSource.Factory()
        .setCache(streamingCache)
        .setUpstreamDataSourceFactory(upstream)
        .setCacheKeyFactory(cacheKeyFactory)
        // A cache that cannot be written (full disk, revoked permission) must degrade to plain
        // streaming rather than stopping playback.
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    return CacheDataSource.Factory()
        .setCache(downloadCache)
        .setUpstreamDataSourceFactory(throughStreamingCache)
        .setCacheKeyFactory(cacheKeyFactory)
        // Null sink means read-only. Without it every downloaded chapter would be written into the
        // streaming cache as well the first time it played — paying for the same bytes twice and
        // evicting something else to do it.
        .setCacheWriteDataSinkFactory(null)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
}

/**
 * How many bytes have to be evicted for [requiredSpace] more to fit under [maxBytes].
 *
 * Zero when it already fits, which is the common answer and the one worth being sure about: a
 * playing chapter asks this question for every span it writes.
 *
 * Written as a subtraction rather than as the `currentSize + requiredSpace > maxBytes` comparison
 * Media3's own evictor uses, because [StreamingCacheUnlimited] is `Long.MAX_VALUE` and that sum
 * overflows into a negative number — which reads as "plenty of room" by luck rather than by design.
 */
internal fun overflowBytes(currentSize: Long, requiredSpace: Long, maxBytes: Long): Long {
    if (maxBytes == StreamingCacheUnlimited) return 0L
    val used = currentSize.coerceAtLeast(0L)
    val wanted = requiredSpace.coerceAtLeast(0L)
    return (used - maxBytes + wanted).coerceAtLeast(0L)
}

/**
 * Least-recently-used eviction with a ceiling that can be changed while the cache is open.
 *
 * Media3's [androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor] is `final` and takes its
 * maximum in the constructor, and [androidx.media3.datasource.cache.SimpleCache] refuses to open a
 * directory twice in one process — so with that evictor, changing the cap in Settings could not take
 * effect until the app was next launched. Someone lowering a cap is usually trying to free space
 * *now*, and telling them to restart the app for it is not an answer.
 *
 * Otherwise this is deliberately the same policy: order spans by [CacheSpan.lastTouchTimestamp] and
 * drop the oldest until the write fits. `SimpleCache` calls every method here while holding its own
 * lock, so the accounting is single-threaded; [setMaxBytes] comes from elsewhere and takes the same
 * monitor.
 */
@OptIn(UnstableApi::class)
class ResizableLruCacheEvictor(maxBytes: Long) : CacheEvictor {

    /**
     * Oldest touch first. Ties are broken by the span's own ordering (key, then position) rather
     * than treated as equal — a `TreeSet` discards a duplicate, and two spans written in the same
     * millisecond are not the same span.
     */
    private val leastRecentlyUsed = TreeSet<CacheSpan> { a, b ->
        val byAge = a.lastTouchTimestamp.compareTo(b.lastTouchTimestamp)
        if (byAge != 0) byAge else a.compareTo(b)
    }

    private var currentSize: Long = 0L
    private var maxBytes: Long = maxBytes

    /** Total bytes this evictor is accounting for. Test seam, and the readout in Settings. */
    val cachedBytes: Long
        @Synchronized get() = currentSize

    /**
     * Raise or lower the ceiling, applying it immediately.
     *
     * [cache] is passed rather than held because the evictor is handed to `SimpleCache`'s
     * constructor, so it cannot be given the cache it belongs to until after that returns — and a
     * reference stored back afterwards is one more thing to get wrong at shutdown.
     */
    @Synchronized
    fun setMaxBytes(cache: Cache, bytes: Long) {
        maxBytes = bytes.coerceAtLeast(0L)
        evict(cache, requiredSpace = 0L)
    }

    /** Needed: the eviction order is the touch order, so touches have to be reported. */
    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() = Unit

    @Synchronized
    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        // An unknown length cannot be budgeted for. Media3's evictor skips these too; the span is
        // accounted for by onSpanAdded once it is actually on disk, and the next write evicts.
        if (length != C.LENGTH_UNSET.toLong()) evict(cache, requiredSpace = length)
    }

    @Synchronized
    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        leastRecentlyUsed.add(span)
        currentSize += span.length
        evict(cache, requiredSpace = 0L)
    }

    @Synchronized
    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        leastRecentlyUsed.remove(span)
        currentSize -= span.length
    }

    @Synchronized
    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    /**
     * Drop the oldest spans until [requiredSpace] more bytes fit.
     *
     * `removeSpan` calls back into [onSpanRemoved], which is what keeps [currentSize] and the set in
     * step — so the loop re-reads the shortfall each time rather than computing a count up front.
     * The set emptying is the other exit: a cap smaller than a single span cannot be honoured, and
     * looping forever trying is worse than being briefly over it.
     */
    private fun evict(cache: Cache, requiredSpace: Long) {
        while (leastRecentlyUsed.isNotEmpty() &&
            overflowBytes(currentSize, requiredSpace, maxBytes) > 0L
        ) {
            cache.removeSpan(leastRecentlyUsed.first())
        }
    }
}
