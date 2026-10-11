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
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadCursor
import androidx.media3.exoplayer.offline.DownloadIndex
import androidx.media3.exoplayer.offline.DownloadRequest
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
    private lateinit var downloadIndex: DefaultDownloadIndex

    /** Switched off to mean airplane mode: the only way to prove a read came off the disk. */
    private var serverReachable = true
    private var upstreamReads = 0

    private val body = ByteArray(4096) { (it % 251).toByte() }

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val provider = StandaloneDatabaseProvider(context)
        downloadIndex = DefaultDownloadIndex(provider)
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

    @Test
    fun `an index open failure retains every cached span and download record`() {
        assertFailedMigrationRetainsLibrary(object : DownloadIndex by downloadIndex {
            override fun getDownloads(vararg states: Int): DownloadCursor {
                throw IOException("index unavailable")
            }
        })
    }

    @Test
    fun `an index cursor failure after one record retains the entire library`() {
        assertFailedMigrationRetainsLibrary(object : DownloadIndex by downloadIndex {
            override fun getDownloads(vararg states: Int): DownloadCursor {
                val cursor = downloadIndex.getDownloads(*states)
                return object : DownloadCursor by cursor {
                    override fun moveToNext(): Boolean {
                        if (cursor.position >= 0) throw IOException("cursor read failed")
                        return cursor.moveToNext()
                    }
                }
            }
        })
    }

    @Test
    fun `an index cursor close failure retains every cached span and record`() {
        assertFailedMigrationRetainsLibrary(object : DownloadIndex by downloadIndex {
            override fun getDownloads(vararg states: Int): DownloadCursor {
                val cursor = downloadIndex.getDownloads(*states)
                return object : DownloadCursor by cursor {
                    override fun close() {
                        cursor.close()
                        throw IOException("cursor close failed")
                    }
                }
            }
        })
    }

    @Test
    fun `a failed identity migration retries the same identity when the index recovers`() {
        seedMigrationLibrary()
        val migration = DownloadIdentityMigration()
        val incoming = DownloadCacheKeys.serverIdentity("https://ttsroad.example")!!
        var unavailable = true
        val index = object : DownloadIndex by downloadIndex {
            override fun getDownloads(vararg states: Int): DownloadCursor {
                if (unavailable) throw IOException("index unavailable")
                return downloadIndex.getDownloads(*states)
            }
        }
        val rekeyed = mutableListOf<String>()
        val rekey: (DownloadRequest) -> Unit = { request ->
            rekeyed += request.id
            downloadCache.removeResource(request.customCacheKey!!)
            val replacement = DownloadRequest.Builder(request.id, request.uri)
                .setCustomCacheKey(DownloadCacheKeys.forUrl(request.uri.toString(), incoming))
                .setData(request.data)
                .build()
            downloadIndex.putDownload(
                Download(replacement, Download.STATE_QUEUED, 0, 0, -1, 0, 0),
            )
        }

        assertFalse(migration.migrate(incoming, index, downloadCache, rekey = rekey))
        unavailable = false
        assertTrue(migration.migrate(incoming, index, downloadCache, rekey = rekey))

        assertEquals(incoming, migration.identity)
        assertEquals(setOf("chapter:1", "chapter:2"), rekeyed.toSet())
        assertEquals(setOf("other.example /audio/chapter.mp3", "other.example /audio/orphan.mp3"), downloadCache.keys)
        for (id in rekeyed) {
            val download = downloadIndex.getDownload(id)!!
            assertEquals(Download.STATE_QUEUED, download.state)
            assertTrue(download.request.customCacheKey!!.startsWith("$incoming "))
        }
        assertEquals(Download.STATE_COMPLETED, downloadIndex.getDownload("chapter:3")!!.state)
        assertTrue(migration.migrate(incoming, index, downloadCache, rekey = rekey))
        assertEquals(2, rekeyed.size)
    }

    @Test
    fun `a successful identity index read removes only genuine unscoped orphans`() {
        seedMigrationLibrary()
        val records = indexedRecords()
        val migration = DownloadIdentityMigration()
        val rekeyed = mutableListOf<DownloadRequest>()

        assertTrue(
            migration.migrate("ttsroad.example", downloadIndex, downloadCache, rekey = rekeyed::add),
        )

        assertEquals(
            setOf(DefaultKey, "/audio/second.mp3", "other.example /audio/chapter.mp3", "other.example /audio/orphan.mp3"),
            downloadCache.keys,
        )
        assertTrue(downloadCache.isCached(DefaultKey, 0, body.size.toLong() * 2))
        assertEquals(setOf("chapter:1", "chapter:2"), rekeyed.map { it.id }.toSet())
        assertEquals(records, indexedRecords())
    }

    @Test
    fun `a successfully empty identity index removes unscoped orphans but keeps scoped spans`() {
        writeInto(downloadCache)
        writeInto(downloadCache, "other.example /audio/chapter.mp3")
        val rekeyed = mutableListOf<DownloadRequest>()

        assertTrue(
            DownloadIdentityMigration().migrate("ttsroad.example", downloadIndex, downloadCache, rekey = rekeyed::add),
        )

        assertEquals(setOf("other.example /audio/chapter.mp3"), downloadCache.keys)
        assertTrue(rekeyed.isEmpty())
        assertTrue(indexedRecords().isEmpty())
    }

    @Test
    fun `a session reset during the index read aborts cleanup and rekeying`() {
        seedMigrationLibrary()
        val records = indexedRecords()
        val spans = cachedSpans()
        val migration = DownloadIdentityMigration()
        var current = true
        val index = object : DownloadIndex by downloadIndex {
            override fun getDownloads(vararg states: Int): DownloadCursor {
                val cursor = downloadIndex.getDownloads(*states)
                return object : DownloadCursor by cursor {
                    override fun close() {
                        cursor.close()
                        current = false
                    }
                }
            }
        }

        assertFalse(
            migration.migrate("ttsroad.example", index, downloadCache, isCurrent = { current }) {
                error("must not rekey a previous session")
            },
        )

        assertEquals(null, migration.identity)
        assertEquals(records, indexedRecords())
        assertEquals(spans, cachedSpans())
    }

    @Test
    fun `a download queued during the snapshot uses the selected identity and survives migration`() {
        seedMigrationLibrary()
        val migration = DownloadIdentityMigration()
        val incoming = "ttsroad.example"
        val index = object : DownloadIndex by downloadIndex {
            override fun getDownloads(vararg states: Int): DownloadCursor {
                val cursor = downloadIndex.getDownloads(*states)
                return object : DownloadCursor by cursor {
                    override fun close() {
                        cursor.close()
                        val key = DownloadCacheKeys.forUrl(Url, incoming)
                        writeInto(downloadCache, key)
                        val request = DownloadRequest.Builder("chapter:4", Uri.parse(Url)).setCustomCacheKey(key).build()
                        downloadIndex.putDownload(Download(request, Download.STATE_COMPLETED, 0, 0, body.size.toLong(), 0, 0))
                    }
                }
            }
        }
        val rekeyed = mutableListOf<DownloadRequest>()

        assertTrue(migration.migrate("ttsroad.example", index, downloadCache, rekey = rekeyed::add))

        assertEquals("ttsroad.example $DefaultKey", downloadIndex.getDownload("chapter:4")!!.request.customCacheKey)
        assertFalse(rekeyed.any { it.id == "chapter:4" })
        serverReachable = false
        assertArrayEquals(body, readThroughChain(cacheKeyFactory = CacheKeyFactory { DownloadCacheKeys.forUrl(it.uri.toString(), incoming) }))
    }

    @Test
    fun `a refused rekey keeps migration incomplete and retries after foreground recovery`() {
        seedMigrationLibrary()
        val migration = DownloadIdentityMigration()
        val before = cachedSpans()
        val records = indexedRecords()
        var refused = true
        val dispatched = mutableListOf<String>()
        val rekey: (DownloadRequest) -> Unit = { request ->
            if (refused) throw IllegalStateException("background service start refused")
            dispatched += request.id
        }

        assertFalse(migration.migrate("ttsroad.example", downloadIndex, downloadCache, rekey = rekey))
        assertEquals(null, migration.identity)
        assertEquals(before, cachedSpans())
        assertEquals(records, indexedRecords())
        refused = false
        assertTrue(migration.migrate("ttsroad.example", downloadIndex, downloadCache, rekey = rekey))
        assertEquals("ttsroad.example", migration.identity)
        assertEquals(setOf("chapter:1", "chapter:2"), dispatched.toSet())
        assertFalse(downloadCache.keys.contains("/audio/orphan.mp3"))
    }

    @Test
    fun `a rekey whose remove succeeded but add failed retains its request even without an index row`() {
        seedMigrationLibrary()
        val migration = DownloadIdentityMigration()
        var refuseAdd = true
        val replacementIds = mutableListOf<String>()
        val rekey: (DownloadRequest) -> Unit = { request ->
            downloadIndex.removeDownload(request.id)
            downloadCache.removeResource(request.customCacheKey!!)
            if (refuseAdd) throw IllegalStateException("add refused")
            val replacement = DownloadRequest.Builder(request.id, request.uri)
                .setCustomCacheKey(DownloadCacheKeys.forUrl(request.uri.toString(), "ttsroad.example"))
                .setData(request.data)
                .build()
            downloadIndex.putDownload(Download(replacement, Download.STATE_QUEUED, 0, 0, -1, 0, 0))
            replacementIds += request.id
        }

        assertFalse(migration.migrate("ttsroad.example", downloadIndex, downloadCache, rekey = rekey))
        assertEquals(null, migration.identity)
        assertEquals(null, downloadIndex.getDownload("chapter:1"))
        assertTrue(downloadCache.keys.contains("/audio/orphan.mp3"))
        refuseAdd = false
        assertTrue(migration.migrate("ttsroad.example", downloadIndex, downloadCache, rekey = rekey))
        assertEquals(setOf("chapter:1", "chapter:2"), replacementIds.toSet())
        for (id in replacementIds) assertEquals(Download.STATE_QUEUED, downloadIndex.getDownload(id)!!.state)
        assertFalse(downloadCache.keys.contains("/audio/orphan.mp3"))
    }

    @Test
    fun `a user removal drops a retained failed rekey so a retry never re-queues it`() {
        seedMigrationLibrary()
        val migration = DownloadIdentityMigration()
        var refuseAdd = true
        val attempted = mutableListOf<String>()
        val replacementIds = mutableListOf<String>()
        val rekey = rekeyRemovingFirst(attempted, replacementIds) { refuseAdd }

        assertFalse(migration.migrate("ttsroad.example", downloadIndex, downloadCache, rekey = rekey))
        // The migration's own remove went through; only the replacement add was refused.
        val deleted = attempted.single()
        assertEquals(null, downloadIndex.getDownload(deleted))
        // The user deletes that chapter before the foreground retry: OfflineDownloads.remove().
        migration.forget(deleted, "ttsroad.example")
        downloadIndex.removeDownload(deleted)
        refuseAdd = false
        attempted.clear()

        assertTrue(migration.migrate("ttsroad.example", downloadIndex, downloadCache, rekey = rekey))

        assertEquals("ttsroad.example", migration.identity)
        assertFalse("$deleted was resurrected", deleted in attempted)
        assertEquals(setOf("chapter:1", "chapter:2") - deleted, replacementIds.toSet())
        assertEquals(null, downloadIndex.getDownload(deleted))
    }

    @Test
    fun `a user removal of a different chapter keeps the migration's own retained rekey`() {
        seedMigrationLibrary()
        val migration = DownloadIdentityMigration()
        var refuseAdd = true
        val attempted = mutableListOf<String>()
        val replacementIds = mutableListOf<String>()
        val rekey = rekeyRemovingFirst(attempted, replacementIds) { refuseAdd }

        assertFalse(migration.migrate("ttsroad.example", downloadIndex, downloadCache, rekey = rekey))
        val retained = attempted.single()
        val other = (setOf("chapter:1", "chapter:2") - retained).single()
        migration.forget(other, "ttsroad.example")
        downloadIndex.removeDownload(other)
        refuseAdd = false

        assertTrue(migration.migrate("ttsroad.example", downloadIndex, downloadCache, rekey = rekey))

        // Removed by the migration itself, not by the user: still owed its replacement.
        assertEquals(listOf(retained), replacementIds)
        assertEquals(Download.STATE_QUEUED, downloadIndex.getDownload(retained)!!.state)
        assertEquals(null, downloadIndex.getDownload(other))
    }

    @Test
    fun `removing every download drops all retained failed rekeys`() {
        seedMigrationLibrary()
        val migration = DownloadIdentityMigration()
        var refuseAdd = true
        val attempted = mutableListOf<String>()
        val replacementIds = mutableListOf<String>()
        val rekey = rekeyRemovingFirst(attempted, replacementIds) { refuseAdd }

        assertFalse(migration.migrate("ttsroad.example", downloadIndex, downloadCache, rekey = rekey))
        assertEquals(1, attempted.size)
        // OfflineDownloads.removeAll(): every record goes, including the ones never attempted.
        migration.forgetAll("ttsroad.example")
        indexedRecords().forEach { (request, _) -> downloadIndex.removeDownload(request.id) }
        refuseAdd = false
        attempted.clear()

        assertTrue(migration.migrate("ttsroad.example", downloadIndex, downloadCache, rekey = rekey))

        assertEquals("ttsroad.example", migration.identity)
        assertTrue("resurrected $attempted", attempted.isEmpty())
        assertTrue(replacementIds.isEmpty())
        assertTrue(indexedRecords().isEmpty())
    }

    @Test
    fun `a removal while the index snapshot is open is not rekeyed from that snapshot`() {
        seedMigrationLibrary()
        val migration = DownloadIdentityMigration()
        val index = object : DownloadIndex by downloadIndex {
            override fun getDownloads(vararg states: Int): DownloadCursor {
                val cursor = downloadIndex.getDownloads(*states)
                return object : DownloadCursor by cursor {
                    override fun close() {
                        cursor.close()
                        migration.forget("chapter:1", "ttsroad.example")
                        downloadIndex.removeDownload("chapter:1")
                    }
                }
            }
        }
        val rekeyed = mutableListOf<String>()

        assertTrue(migration.migrate("ttsroad.example", index, downloadCache) { rekeyed += it.id })

        assertEquals(listOf("chapter:2"), rekeyed)
    }

    @Test
    fun `removing every download while the index snapshot is open rekeys nothing from it`() {
        seedMigrationLibrary()
        val migration = DownloadIdentityMigration()
        val index = object : DownloadIndex by downloadIndex {
            override fun getDownloads(vararg states: Int): DownloadCursor {
                val cursor = downloadIndex.getDownloads(*states)
                return object : DownloadCursor by cursor {
                    override fun close() {
                        cursor.close()
                        migration.forgetAll("ttsroad.example")
                    }
                }
            }
        }
        val rekeyed = mutableListOf<String>()

        assertTrue(migration.migrate("ttsroad.example", index, downloadCache) { rekeyed += it.id })

        assertTrue("rekeyed $rekeyed", rekeyed.isEmpty())
    }

    @Test
    fun `a deletion before any migration starts keeps its still-standing row from being rekeyed`() {
        seedMigrationLibrary()
        val migration = DownloadIdentityMigration()
        // OfflineDownloads.remove() with the migration only scheduled: the service has not yet
        // processed the remove, so the index row is still there when the migration reads it.
        migration.forget("chapter:1", "ttsroad.example")
        val rekeyed = mutableListOf<String>()

        assertTrue(migration.migrate("ttsroad.example", downloadIndex, downloadCache) { rekeyed += it.id })

        assertEquals(listOf("chapter:2"), rekeyed)
    }

    @Test
    fun `removing everything before any migration starts rekeys none of the still-standing rows`() {
        seedMigrationLibrary()
        val migration = DownloadIdentityMigration()
        migration.forgetAll("ttsroad.example")
        val rekeyed = mutableListOf<String>()

        assertTrue(migration.migrate("ttsroad.example", downloadIndex, downloadCache) { rekeyed += it.id })

        assertTrue("rekeyed $rekeyed", rekeyed.isEmpty())
    }

    @Test
    fun `a chapter downloaded again after its deletion is migrated like any other`() {
        seedMigrationLibrary()
        val migration = DownloadIdentityMigration()
        migration.forget("chapter:1", "ttsroad.example")
        migration.forgetAll("ttsroad.example")
        migration.restore("chapter:1", "ttsroad.example")
        migration.restore("chapter:2", "ttsroad.example")
        val rekeyed = mutableListOf<String>()

        assertTrue(migration.migrate("ttsroad.example", downloadIndex, downloadCache) { rekeyed += it.id })

        assertEquals(setOf("chapter:1", "chapter:2"), rekeyed.toSet())
    }

    @Test
    fun `a deletion mark lapses once an index read shows the remove has landed`() {
        seedMigrationLibrary()
        val migration = DownloadIdentityMigration()
        migration.forget("chapter:1", "ttsroad.example")
        downloadIndex.removeDownload("chapter:1")
        var refuse = true
        val rekeyed = mutableListOf<String>()
        val rekey: (DownloadRequest) -> Unit = { request ->
            if (refuse) throw IllegalStateException("service start refused")
            rekeyed += request.id
        }
        assertFalse(migration.migrate("ttsroad.example", downloadIndex, downloadCache, rekey = rekey))
        // A legacy row for the same id appears afterwards; the old deletion says nothing about it.
        val request = DownloadRequest.Builder("chapter:1", Uri.parse("https://ttsroad.example$DefaultKey"))
            .setCustomCacheKey(DefaultKey).build()
        downloadIndex.putDownload(Download(request, Download.STATE_COMPLETED, 0, 0, body.size.toLong(), 0, 0))
        refuse = false

        assertTrue(migration.migrate("ttsroad.example", downloadIndex, downloadCache, rekey = rekey))

        assertEquals(setOf("chapter:1", "chapter:2"), rekeyed.toSet())
    }

    @Test
    fun `a removed record whose add was refused survives the process being reaped`() {
        seedMigrationLibrary()
        val file = File(RuntimeEnvironment.getApplication().filesDir, "rekeys_${System.nanoTime()}.json")
        var refuseAdd = true
        val attempted = mutableListOf<String>()
        val replacementIds = mutableListOf<String>()
        val removeFirst = rekeyRemovingFirst(attempted, replacementIds) { refuseAdd }
        val rekey: (DownloadRequest) -> Unit = { request ->
            // Durable before the dispatch that can strand it, not merely after the run.
            assertTrue(RetainedRekeyFile(file).load()["ttsroad.example"]!!.containsKey(request.id))
            removeFirst(request)
        }

        assertFalse(DownloadIdentityMigration(RetainedRekeyFile(file)).migrate("ttsroad.example", downloadIndex, downloadCache, rekey = rekey))
        val stranded = attempted.single()
        assertEquals(null, downloadIndex.getDownload(stranded))
        refuseAdd = false

        // A new process: nothing in memory, and the index no longer names the chapter.
        val restarted = DownloadIdentityMigration(RetainedRekeyFile(file))
        assertTrue(restarted.migrate("ttsroad.example", downloadIndex, downloadCache, rekey = rekey))

        assertEquals(setOf("chapter:1", "chapter:2"), replacementIds.toSet())
        assertEquals(Download.STATE_QUEUED, downloadIndex.getDownload(stranded)!!.state)
        assertFalse("nothing is left retained", file.exists())
    }

    @Test
    fun `a deletion after the process was reaped drops the persisted rekey too`() {
        seedMigrationLibrary()
        val file = File(RuntimeEnvironment.getApplication().filesDir, "rekeys_${System.nanoTime()}.json")
        var refuseAdd = true
        val attempted = mutableListOf<String>()
        val replacementIds = mutableListOf<String>()
        val rekey = rekeyRemovingFirst(attempted, replacementIds) { refuseAdd }

        assertFalse(DownloadIdentityMigration(RetainedRekeyFile(file)).migrate("ttsroad.example", downloadIndex, downloadCache, rekey = rekey))
        val stranded = attempted.single()
        DownloadIdentityMigration(RetainedRekeyFile(file)).forget(stranded, "ttsroad.example")
        refuseAdd = false
        attempted.clear()

        assertTrue(DownloadIdentityMigration(RetainedRekeyFile(file)).migrate("ttsroad.example", downloadIndex, downloadCache, rekey = rekey))

        assertFalse("$stranded was resurrected", stranded in attempted)
        assertEquals(null, downloadIndex.getDownload(stranded))
    }

    @Test
    fun `removing everything after the process was reaped clears the persisted rekeys`() {
        seedMigrationLibrary()
        val file = File(RuntimeEnvironment.getApplication().filesDir, "rekeys_${System.nanoTime()}.json")
        val attempted = mutableListOf<String>()
        val rekey = rekeyRemovingFirst(attempted, mutableListOf()) { true }

        assertFalse(DownloadIdentityMigration(RetainedRekeyFile(file)).migrate("ttsroad.example", downloadIndex, downloadCache, rekey = rekey))
        assertTrue(file.exists())
        DownloadIdentityMigration(RetainedRekeyFile(file)).forgetAll("ttsroad.example")

        assertFalse(file.exists())
        assertTrue(RetainedRekeyFile(file).load().isEmpty())
    }

    @Test
    fun `downloading a chapter again drops its retained rekey so the retry cannot overwrite it`() {
        seedMigrationLibrary()
        val file = File(RuntimeEnvironment.getApplication().filesDir, "rekeys_${System.nanoTime()}.json")
        val migration = DownloadIdentityMigration(RetainedRekeyFile(file))
        var refuseAdd = true
        val attempted = mutableListOf<String>()
        val replacementIds = mutableListOf<String>()
        val rekey = rekeyRemovingFirst(attempted, replacementIds) { refuseAdd }
        assertFalse(migration.migrate("ttsroad.example", downloadIndex, downloadCache, rekey = rekey))
        val stranded = attempted.single()
        // OfflineDownloads.download(): restore first, then the fresh request lands in the index.
        migration.restore(stranded, "ttsroad.example")
        val fresh = DownloadRequest.Builder(stranded, Uri.parse("https://ttsroad.example/audio/fresh.mp3"))
            .setCustomCacheKey("ttsroad.example /audio/fresh.mp3")
            .setData(byteArrayOf(42))
            .build()
        downloadIndex.putDownload(Download(fresh, Download.STATE_QUEUED, 0, 0, -1, 0, 0))
        assertFalse(
            "the persisted copy must go too",
            RetainedRekeyFile(file).load()["ttsroad.example"].orEmpty().containsKey(stranded),
        )
        refuseAdd = false
        attempted.clear()

        assertTrue(migration.migrate("ttsroad.example", downloadIndex, downloadCache, rekey = rekey))

        assertFalse("$stranded was overwritten by its retained rekey", stranded in attempted)
        assertEquals(fresh, downloadIndex.getDownload(stranded)!!.request)
    }

    @Test
    fun `a deletion on another server never erases this server's retained rekey`() {
        seedMigrationLibrary()
        val file = File(RuntimeEnvironment.getApplication().filesDir, "rekeys_${System.nanoTime()}.json")
        var refuseAdd = true
        val attempted = mutableListOf<String>()
        val replacementIds = mutableListOf<String>()
        val rekey = rekeyRemovingFirst(attempted, replacementIds) { refuseAdd }
        assertFalse(DownloadIdentityMigration(RetainedRekeyFile(file)).migrate("ttsroad.example", downloadIndex, downloadCache, rekey = rekey))
        val stranded = attempted.single()

        // Signed in to server B, whose chapter shares the id; then B's "delete all" and re-download.
        val onB = DownloadIdentityMigration(RetainedRekeyFile(file))
        onB.forget(stranded, "b.example")
        onB.forgetAll("b.example")
        onB.restore(stranded, "b.example")
        onB.forget(stranded, null)
        assertTrue(RetainedRekeyFile(file).load()["ttsroad.example"]!!.containsKey(stranded))
        refuseAdd = false

        assertTrue(DownloadIdentityMigration(RetainedRekeyFile(file)).migrate("ttsroad.example", downloadIndex, downloadCache, rekey = rekey))

        assertTrue("$stranded was lost", stranded in replacementIds)
        assertEquals(Download.STATE_QUEUED, downloadIndex.getDownload(stranded)!!.state)
    }

    @Test
    fun `dispatching retained rekeys touches only that identity and adopts nothing`() {
        seedMigrationLibrary()
        val records = indexedRecords()
        val file = File(RuntimeEnvironment.getApplication().filesDir, "rekeys_${System.nanoTime()}.json")
        val mine = DownloadRequest.Builder("chapter:5", Uri.parse("https://ttsroad.example/audio/5.mp3"))
            .setCustomCacheKey("/audio/5.mp3").build()
        val theirs = DownloadRequest.Builder("chapter:5", Uri.parse("https://other.example/audio/5.mp3"))
            .setCustomCacheKey("/audio/5.mp3").build()
        RetainedRekeyFile(file).save(
            mapOf("ttsroad.example" to mapOf(mine.id to mine), "other.example" to mapOf(theirs.id to theirs)),
        )
        val migration = DownloadIdentityMigration(RetainedRekeyFile(file))
        val dispatched = mutableListOf<DownloadRequest>()

        assertTrue(migration.dispatchRetained("ttsroad.example") { dispatched += it })

        assertEquals(listOf(mine), dispatched)
        assertEquals(null, migration.identity)
        assertEquals(records, indexedRecords())
        assertTrue("no cleanup", downloadCache.keys.contains("/audio/orphan.mp3"))
        assertEquals(mapOf("other.example" to mapOf(theirs.id to theirs)), RetainedRekeyFile(file).load())
        assertFalse(migration.hasRetained("ttsroad.example"))
        assertTrue(migration.hasRetained("other.example"))
    }

    // --- retained-rekey faults: a failed write, a refused service start ----------------------

    /** The retained-rekey file, with a switch that makes every write fail as a full disk would. */
    private class FlakyStore(val file: File) : RetainedRekeyStore {
        private val delegate = RetainedRekeyFile(file)
        var failing = false

        override fun load() = delegate.load()

        override fun save(retained: Map<String, Map<String, DownloadRequest>>): Boolean =
            !failing && delegate.save(retained)

        fun onDisk(): Set<String> = delegate.load()["ttsroad.example"].orEmpty().keys
    }

    private fun flakyStore() =
        FlakyStore(File(RuntimeEnvironment.getApplication().filesDir, "rekeys_${System.nanoTime()}.json"))

    /** Leave one chapter stranded: its old row removed by the migration, its add refused. */
    private fun strand(migration: DownloadIdentityMigration): String {
        val attempted = mutableListOf<String>()
        assertFalse(
            migration.migrate(
                "ttsroad.example",
                downloadIndex,
                downloadCache,
                rekey = rekeyRemovingFirst(attempted, mutableListOf()) { true },
            ),
        )
        return attempted.single()
    }

    @Test
    fun `a re-download whose retained copy cannot be dropped durably queues nothing`() {
        seedMigrationLibrary()
        val store = flakyStore()
        val migration = DownloadIdentityMigration(store)
        val stranded = strand(migration)
        store.failing = true
        var added = 0

        assertFalse(migration.restore(stranded, "ttsroad.example") { added++ })

        assertEquals(0, added)
        assertTrue(stranded in store.onDisk())
        assertTrue(migration.hasRetained("ttsroad.example"))
        store.failing = false
        assertTrue(migration.restore(stranded, "ttsroad.example") { added++ })
        assertEquals(1, added)
        assertFalse(stranded in store.onDisk())
    }

    @Test
    fun `a deletion whose retained copy cannot be dropped durably deletes nothing`() {
        seedMigrationLibrary()
        val store = flakyStore()
        val migration = DownloadIdentityMigration(store)
        val stranded = strand(migration)
        store.failing = true
        var removes = 0

        assertFalse(migration.forget(stranded, "ttsroad.example") { removes++ })
        assertFalse(migration.forgetAll("ttsroad.example") { removes++ })

        assertEquals(0, removes)
        assertTrue(stranded in store.onDisk())
        // The deletion did not happen, so the stranded chapter is still owed its download.
        store.failing = false
        val replacementIds = mutableListOf<String>()
        assertTrue(
            migration.migrate(
                "ttsroad.example",
                downloadIndex,
                downloadCache,
                rekey = rekeyRemovingFirst(mutableListOf(), replacementIds) { false },
            ),
        )
        assertTrue(stranded in replacementIds)
    }

    @Test
    fun `a failed pre-dispatch write is retried before any rekey, even with nothing new to add`() {
        seedMigrationLibrary()
        val store = flakyStore()
        val migration = DownloadIdentityMigration(store)
        store.failing = true
        val dispatched = mutableListOf<String>()
        val rekey: (DownloadRequest) -> Unit = { request ->
            assertTrue("dispatched before it was on disk", request.id in store.onDisk())
            dispatched += request.id
        }

        assertFalse(migration.migrate("ttsroad.example", downloadIndex, downloadCache, rekey = rekey))
        // Same index, same requests: they are already pending in memory and nothing is "added".
        assertFalse(migration.migrate("ttsroad.example", downloadIndex, downloadCache, rekey = rekey))
        assertFalse(migration.dispatchRetained("ttsroad.example", rekey = rekey))
        assertTrue(dispatched.isEmpty())

        store.failing = false
        assertTrue(migration.migrate("ttsroad.example", downloadIndex, downloadCache, rekey = rekey))
        assertEquals(setOf("chapter:1", "chapter:2"), dispatched.toSet())
    }

    @Test
    fun `a refused fresh add puts the retained request back`() {
        seedMigrationLibrary()
        val store = flakyStore()
        val migration = DownloadIdentityMigration(store)
        val stranded = strand(migration)

        val refused = runCatching {
            migration.restore(stranded, "ttsroad.example") { throw IllegalStateException("background start refused") }
        }

        assertTrue(refused.isFailure)
        assertTrue(stranded in store.onDisk())
        assertTrue(migration.hasRetained("ttsroad.example"))
        // And it survives a restart, still owed its download.
        val replacementIds = mutableListOf<String>()
        assertTrue(
            DownloadIdentityMigration(store).migrate(
                "ttsroad.example",
                downloadIndex,
                downloadCache,
                rekey = rekeyRemovingFirst(mutableListOf(), replacementIds) { false },
            ),
        )
        assertTrue(stranded in replacementIds)
    }

    @Test
    fun `a refused remove puts the retained request back`() {
        seedMigrationLibrary()
        val store = flakyStore()
        val migration = DownloadIdentityMigration(store)
        val stranded = strand(migration)

        assertTrue(runCatching { migration.forget(stranded, "ttsroad.example") { error("refused") } }.isFailure)
        assertTrue(runCatching { migration.forgetAll("ttsroad.example") { error("refused") } }.isFailure)

        assertTrue(stranded in store.onDisk())
        assertTrue(migration.hasRetained("ttsroad.example"))
    }

    @Test
    fun `a deletion after an unrecorded dispatch writes the disk before it can resurrect`() {
        seedMigrationLibrary()
        val store = flakyStore()
        val migration = DownloadIdentityMigration(store)
        // Every rekey goes out, but the disk fills up straight after the pre-dispatch write, so the
        // end-of-run write that would drop them never lands.
        val rekey: (DownloadRequest) -> Unit = { store.failing = true }
        assertTrue(migration.migrate("ttsroad.example", downloadIndex, downloadCache, rekey = rekey))
        assertEquals(setOf("chapter:1", "chapter:2"), store.onDisk())
        store.failing = false

        // Nothing for chapter:1 in memory any more, but the disk still names it.
        assertTrue(migration.forget("chapter:1", "ttsroad.example"))

        assertTrue(store.onDisk().isEmpty())
        val redispatched = mutableListOf<String>()
        assertTrue(DownloadIdentityMigration(store).dispatchRetained("ttsroad.example") { redispatched += it.id })
        assertTrue("resurrected $redispatched", redispatched.isEmpty())
    }

    @Test
    fun `a retained rekey round-trips every field a download sets`() {
        val request = DownloadRequest.Builder("chapter:7", Uri.parse("https://ttsroad.example/audio/7.mp3"))
            .setCustomCacheKey("/audio/7.mp3")
            .setMimeType("audio/mpeg")
            .setData(byteArrayOf(0, 1, 127, -128, -1))
            .build()
        val bare = DownloadRequest.Builder("chapter:8", Uri.parse("https://ttsroad.example/audio/8.mp3")).build()
        val retained = mapOf("ttsroad.example" to mapOf(request.id to request, bare.id to bare))

        assertEquals(retained, decodeRetainedRekeys(encodeRetainedRekeys(retained)))
    }

    @Test
    fun `an unreadable retained-rekey file reads as nothing retained`() {
        val file = File(RuntimeEnvironment.getApplication().filesDir, "rekeys_${System.nanoTime()}.json")
        file.writeText("{not json")

        assertTrue(RetainedRekeyFile(file).load().isEmpty())
    }

    /**
     * A rekey shaped like [OfflineDownloads]'s: the old record goes first, and the replacement add
     * is refused while [refuseAdd] — leaving a request only the migration still remembers.
     */
    private fun rekeyRemovingFirst(
        attempted: MutableList<String>,
        replacementIds: MutableList<String>,
        refuseAdd: () -> Boolean,
    ): (DownloadRequest) -> Unit = { request ->
        attempted += request.id
        downloadIndex.removeDownload(request.id)
        if (refuseAdd()) throw IllegalStateException("add refused")
        val replacement = DownloadRequest.Builder(request.id, request.uri)
            .setCustomCacheKey(DownloadCacheKeys.forUrl(request.uri.toString(), "ttsroad.example"))
            .setData(request.data)
            .build()
        downloadIndex.putDownload(Download(replacement, Download.STATE_QUEUED, 0, 0, -1, 0, 0))
        replacementIds += request.id
    }

    private fun assertFailedMigrationRetainsLibrary(index: DownloadIndex) {
        seedMigrationLibrary()
        val records = indexedRecords()
        val spans = cachedSpans()
        val bytes = downloadCache.cacheSpace
        val migration = DownloadIdentityMigration()
        val rekeyed = mutableListOf<DownloadRequest>()

        assertFalse(migration.migrate("ttsroad.example", index, downloadCache, rekey = rekeyed::add))

        assertEquals(null, migration.identity)
        assertTrue(rekeyed.isEmpty())
        assertEquals(records, indexedRecords())
        assertEquals(spans, cachedSpans())
        assertEquals(bytes, downloadCache.cacheSpace)
        serverReachable = false
        assertArrayEquals(body, readThroughChain())
    }

    private fun seedMigrationLibrary() {
        listOf(DefaultKey, "/audio/second.mp3", "other.example /audio/chapter.mp3").forEachIndexed { i, key ->
            writeInto(downloadCache, key)
            val request = DownloadRequest.Builder("chapter:${i + 1}", Uri.parse("https://ttsroad.example${key.substringAfter(' ')}"))
                .setCustomCacheKey(key)
                .setData(byteArrayOf(i.toByte()))
                .build()
            downloadIndex.putDownload(
                Download(request, Download.STATE_COMPLETED, 0, 0, body.size.toLong(), 0, 0),
            )
        }
        writeInto(downloadCache, DefaultKey, body.size.toLong())
        writeInto(downloadCache, "/audio/orphan.mp3")
        writeInto(downloadCache, "other.example /audio/orphan.mp3")
    }

    private fun indexedRecords(): List<Pair<DownloadRequest, Int>> =
        downloadIndex.getDownloads().use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.download.request to cursor.download.state)
            }
        }

    private fun cachedSpans(): Map<String, List<Pair<Long, List<Byte>>>> =
        downloadCache.keys.associateWith { key ->
            downloadCache.getCachedSpans(key).map { span -> span.position to span.file!!.readBytes().toList() }
        }

    // --- helpers ---------------------------------------------------------------------------

    private fun readThroughChain(url: String = Url, cacheKeyFactory: CacheKeyFactory = KeyFactory): ByteArray {
        val source = readThroughFactory(
            downloadCache = downloadCache,
            streamingCache = streamingCache,
            upstream = FakeUpstreamFactory(),
            cacheKeyFactory = cacheKeyFactory,
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
    private fun writeInto(cache: Cache, key: String = DefaultKey, position: Long = 0) {
        val hole = cache.startReadWrite(key, position, body.size.toLong())
        val file = cache.startFile(key, position, body.size.toLong())
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
