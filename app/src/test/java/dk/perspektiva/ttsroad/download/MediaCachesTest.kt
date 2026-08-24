package dk.perspektiva.ttsroad.download

import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import android.net.Uri
import dk.perspektiva.ttsroad.data.StreamingCacheUnlimited
import java.io.File
import java.io.IOException
import kotlin.math.min
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The two-cache split, exercised against real [SimpleCache] instances.
 *
 * This is the one part of the download stack where reasoning about the code is not enough. The
 * promise the split exists to keep — *a chapter you downloaded still plays when the server is
 * unreachable, however much you have streamed since* — is a property of how three `CacheDataSource`s
 * are chained, and the way to get it wrong is subtle: write through the outer cache and downloads
 * are duplicated into the capped store; put the caches the other way round and an evictor gets to
 * delete a download. Neither shows up as a compile error, and both are silent on a phone until
 * someone is in a tunnel.
 *
 * So the upstream here can be switched off, which is what "airplane mode" means to a data source.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaCachesTest {

    private lateinit var downloadCache: Cache
    private lateinit var streamingCache: Cache
    private lateinit var streamingEvictor: ResizableLruCacheEvictor

    /** Switched off to mean airplane mode: the only way to prove a read came off the disk. */
    private var serverReachable = true
    private var upstreamReads = 0

    private val body = ByteArray(4096) { (it % 251).toByte() }

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val provider = StandaloneDatabaseProvider(context)
        downloadCache = SimpleCache(
            File(context.filesDir, "test_downloads_${System.nanoTime()}"),
            NoOpCacheEvictor(),
            provider,
        )
        streamingEvictor = ResizableLruCacheEvictor(StreamingCacheUnlimited)
        streamingCache = SimpleCache(
            File(context.filesDir, "test_stream_${System.nanoTime()}"),
            streamingEvictor,
            provider,
        )
    }

    @After
    fun tearDown() {
        downloadCache.release()
        streamingCache.release()
    }

    // --- the chain -------------------------------------------------------------------------

    @Test
    fun `a chapter in the download cache plays with the server unreachable`() {
        writeInto(downloadCache)
        serverReachable = false

        assertArrayEquals(body, readThroughChain())
    }

    @Test
    fun `a chapter only streamed plays from the streaming cache without the server`() {
        // Read it once with the server up, which is what puts it in the streaming cache.
        assertArrayEquals(body, readThroughChain())
        serverReachable = false

        assertArrayEquals(body, readThroughChain())
    }

    @Test
    fun `a chapter in neither cache needs the server, and is refused without it`() {
        serverReachable = false

        val failed = runCatching { readThroughChain() }.isFailure
        assertTrue("an uncached chapter must fail rather than serve nothing", failed)
    }

    @Test
    fun `streaming writes to the streaming cache only`() {
        readThroughChain()

        assertTrue("streamed audio belongs in the capped store", streamingCache.cacheSpace > 0)
        assertEquals(
            "nothing but DownloadManager may write to the download cache",
            0L,
            downloadCache.cacheSpace,
        )
    }

    @Test
    fun `replaying a downloaded chapter does not spend streaming cache on it`() {
        writeInto(downloadCache)

        readThroughChain()
        readThroughChain()

        // The point of making the download cache read-only. Without it every download would be
        // copied into the capped store the first time it played, paying twice and evicting
        // something else to do it.
        assertEquals(
            "a downloaded chapter must not be duplicated into the streaming cache",
            0L,
            streamingCache.cacheSpace,
        )
    }

    @Test
    fun `a downloaded chapter is served without touching the network at all`() {
        writeInto(downloadCache)
        upstreamReads = 0

        readThroughChain()

        assertEquals("the download cache is checked first", 0, upstreamReads)
    }

    @Test
    fun `clearing the streaming cache leaves the downloads playable offline`() {
        writeInto(downloadCache)
        writeInto(streamingCache, key = "/audio/other.mp3")

        streamingCache.keys.toList().forEach(streamingCache::removeResource)
        serverReachable = false

        assertEquals(0L, streamingCache.cacheSpace)
        assertArrayEquals(body, readThroughChain())
    }

    // --- eviction --------------------------------------------------------------------------

    @Test
    fun `the cap evicts the least recently used chapter and keeps the newest`() {
        streamingEvictor.setMaxBytes(streamingCache, body.size.toLong() * 2)

        readThroughChain(url = "https://ttsroad.example/audio/one.mp3")
        readThroughChain(url = "https://ttsroad.example/audio/two.mp3")
        readThroughChain(url = "https://ttsroad.example/audio/three.mp3")

        val keys = streamingCache.keys
        assertFalse("the oldest span should have gone", keys.contains("/audio/one.mp3"))
        assertTrue(keys.contains("/audio/three.mp3"))
        assertTrue(
            "the cache must stay within its cap",
            streamingCache.cacheSpace <= body.size.toLong() * 2,
        )
    }

    @Test
    fun `a download is never evicted, however much is streamed past the cap`() {
        writeInto(downloadCache)
        streamingEvictor.setMaxBytes(streamingCache, body.size.toLong())

        repeat(6) { readThroughChain(url = "https://ttsroad.example/audio/filler$it.mp3") }
        serverReachable = false

        // The whole reason the caches are separate, stated as a test.
        assertArrayEquals(body, readThroughChain())
    }

    @Test
    fun `lowering the cap frees space immediately rather than at the next launch`() {
        readThroughChain(url = "https://ttsroad.example/audio/one.mp3")
        readThroughChain(url = "https://ttsroad.example/audio/two.mp3")
        assertTrue(streamingCache.cacheSpace > body.size)

        streamingEvictor.setMaxBytes(streamingCache, body.size.toLong())

        assertTrue(
            "a lowered cap should apply to what is already on disk",
            streamingCache.cacheSpace <= body.size.toLong(),
        )
    }

    @Test
    fun `no limit keeps everything`() {
        streamingEvictor.setMaxBytes(streamingCache, StreamingCacheUnlimited)

        repeat(5) { readThroughChain(url = "https://ttsroad.example/audio/keep$it.mp3") }

        assertEquals(5, streamingCache.keys.size)
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun readThroughChain(url: String = Url): ByteArray {
        val source = readThroughFactory(
            downloadCache = downloadCache,
            streamingCache = streamingCache,
            upstream = FakeUpstreamFactory(),
            cacheKeyFactory = KeyFactory,
        ).createDataSource()

        val spec = DataSpec.Builder().setUri(Uri.parse(url)).build()
        return try {
            source.open(spec)
            val out = ByteArray(body.size)
            var filled = 0
            while (filled < out.size) {
                val read = source.read(out, filled, out.size - filled)
                if (read == C.RESULT_END_OF_INPUT) break
                filled += read
            }
            out.copyOf(filled)
        } finally {
            source.close()
        }
    }

    /** Put [body] into [cache] the way a download does: written directly, not through the chain. */
    private fun writeInto(cache: Cache, key: String = DefaultKey) {
        val hole = cache.startReadWrite(key, 0, body.size.toLong())
        val file = cache.startFile(key, 0, body.size.toLong())
        file.writeBytes(body)
        cache.commitFile(file, body.size.toLong())
        cache.releaseHoleSpan(hole)
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals("byte count", expected.size, actual.size)
        assertTrue("contents", expected.contentEquals(actual))
    }

    private inner class FakeUpstreamFactory : DataSource.Factory {
        override fun createDataSource(): DataSource = FakeUpstream()
    }

    /**
     * "The server", with a switch. Serves [body] for any URL when [serverReachable], and throws the
     * way a real HTTP source does when it is not.
     */
    private inner class FakeUpstream : DataSource {
        private var uri: Uri? = null
        private var position = 0
        private var remaining = 0

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            if (!serverReachable) throw IOException("unreachable")
            uri = dataSpec.uri
            position = dataSpec.position.toInt()
            remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                body.size - position
            } else {
                dataSpec.length.toInt()
            }
            return remaining.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining == 0) return C.RESULT_END_OF_INPUT
            val count = min(length, remaining)
            body.copyInto(buffer, offset, position, position + count)
            position += count
            remaining -= count
            upstreamReads++
            return count
        }

        override fun getUri(): Uri? = uri

        override fun close() = Unit
    }

    private companion object {
        const val Url = "https://ttsroad.example/audio/chapter.mp3"
        const val DefaultKey = "/audio/chapter.mp3"

        /**
         * The same rule [OfflineDownloads] uses: the server-relative path, so a download survives
         * the phone signing in on a different address.
         */
        val KeyFactory = CacheKeyFactory { spec ->
            spec.key ?: DownloadCacheKeys.forUrl(spec.uri.toString(), null)
        }
    }
}
