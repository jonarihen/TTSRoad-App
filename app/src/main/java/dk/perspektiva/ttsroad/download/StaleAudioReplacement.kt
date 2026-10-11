package dk.perspektiva.ttsroad.download

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executor

@OptIn(UnstableApi::class)
internal fun staleAudioDownloaderFactory(
    downloadCache: Cache,
    streamingCache: Cache,
    upstream: DataSource.Factory,
    executor: Executor,
): DownloaderFactory {
    val cacheFactory = CacheDataSource.Factory()
        .setCache(downloadCache)
        .setUpstreamDataSourceFactory(upstream)
    val delegateFactory = DefaultDownloaderFactory(cacheFactory, executor)
    return DownloaderFactory { request ->
        val ids = decodeDownloadIds(request.data)
        val hash = ids?.replacementHash
        if (hash == null) {
            delegateFactory.createDownloader(request)
        } else {
            StaleAudioDownloader(request, hash, ids.invalidationKeys, downloadCache, streamingCache, delegateFactory)
        }
    }
}

@OptIn(UnstableApi::class)
private class StaleAudioDownloader(
    private val request: DownloadRequest,
    private val hash: String,
    invalidationKeys: Set<String>,
    private val downloadCache: Cache,
    private val streamingCache: Cache,
    private val factory: DownloaderFactory,
) : Downloader {
    @Volatile private var delegate: Downloader? = null
    @Volatile private var cancelled = false
    private val invalidationKeys = invalidationKeys + (request.customCacheKey ?: request.uri.toString())

    override fun download(progressListener: Downloader.ProgressListener?) {
        if (cancelled) throw InterruptedException()
        if (delegate == null) {
            invalidationKeys.forEach { key ->
                while (true) {
                    if (cancelled) throw InterruptedException()
                    streamingCache.removeResource(key)
                    val span = streamingCache.startReadWrite(key, 0, C.LENGTH_UNSET.toLong())
                    if (span.isCached) continue
                    try {
                        resetAudioResource(streamingCache, key)
                        resetAudioResource(downloadCache, key)
                    } finally {
                        streamingCache.releaseHoleSpan(span)
                    }
                    break
                }
            }
            delegate = factory.createDownloader(request)
        }
        if (cancelled) throw InterruptedException()
        checkNotNull(delegate).download(progressListener)
        if (cancelled) throw InterruptedException()
        val source = CacheDataSource.Factory().setCache(downloadCache).createDataSource()
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            source.open(DataSpec.Builder().setUri(request.uri).setKey(request.customCacheKey).build())
            val buffer = ByteArray(8192)
            while (true) {
                if (cancelled) throw InterruptedException()
                val count = source.read(buffer, 0, buffer.size)
                if (count == C.RESULT_END_OF_INPUT) break
                digest.update(buffer, 0, count)
            }
        } finally {
            source.close()
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != hash) {
            invalidationKeys.forEach { key ->
                resetAudioResource(downloadCache, key)
                resetAudioResource(streamingCache, key)
            }
            delegate = null
            throw IOException("Replacement audio does not match its content hash")
        }
    }

    override fun cancel() {
        cancelled = true
        delegate?.cancel()
    }

    override fun remove() = factory.createDownloader(request).remove()
}

@OptIn(UnstableApi::class)
private fun resetAudioResource(cache: Cache, key: String) {
    cache.removeResource(key)
    cache.applyContentMetadataMutations(
        key,
        ContentMetadataMutations()
            .remove(ContentMetadata.KEY_CONTENT_LENGTH)
            .remove(ContentMetadata.KEY_REDIRECTED_URI),
    )
}
