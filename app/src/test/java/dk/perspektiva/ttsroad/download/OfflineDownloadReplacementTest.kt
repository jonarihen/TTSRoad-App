package dk.perspektiva.ttsroad.download

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import dk.perspektiva.ttsroad.data.AudioHash
import dk.perspektiva.ttsroad.data.AudioHashesResponse
import dk.perspektiva.ttsroad.data.AudioInfo
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.TokenStore
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineDownloadReplacementTest {
    private lateinit var context: CapturingContext
    private lateinit var downloads: OfflineDownloads
    private lateinit var scanner: StaleDownloadScanner
    private lateinit var record: AudioHashRecord
    private lateinit var server: MockWebServer
    private lateinit var chapters: List<ChapterSummary>
    private val a = ByteArray(4096) { (it % 251).toByte() }
    private val longerB = ByteArray(9127) { ((it + 93) % 241).toByte() }
    private val shorterB = ByteArray(1723) { ((it + 19) % 239).toByte() }
    @Volatile private var bodies = mapOf(1 to a, 2 to a)
    @Volatile private var fail = false
    private var hashes = bodies.mapValues { sha256(it.value) }

    @Before
    fun setUp() {
        context = CapturingContext(RuntimeEnvironment.getApplication())
        record = AudioHashRecord(context).also { it.clear() }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (fail) return MockResponse().setResponseCode(503)
                val id = request.path!!.substringAfterLast('/').substringBefore('.').toInt()
                val body = bodies.getValue(id)
                return MockResponse().setBody(Buffer().write(body))
            }
        }
        server.start()
        chapters = (1..2).map { id ->
            ChapterSummary(id = id, fictionId = 9, audio = AudioInfo(url = server.url("/audio/$id.mp3").toString()))
        }
        scanner = StaleDownloadScanner(record) {
            AudioHashesResponse(fictionId = 9, total = hashes.size, chapters = hashes.map { AudioHash(it.key, it.value) })
        }
        downloads = OfflineDownloads(
            context,
            TokenStore(context),
            forgetAudioHash = scanner::forget,
            initializeManager = false,
            replacementHash = scanner::replacementHash,
            replacementCompleted = scanner::replacementCompleted,
        )
        downloads.downloadManager.apply {
            requirements = Requirements(0)
            minRetryCount = 0
            resumeDownloads()
        }
        await { downloads.downloadManager.isInitialized }
    }

    @After
    fun tearDown() {
        downloads.releaseForTest()
        server.shutdown()
    }

    @Test
    fun `Update replaces same URL with longer content B in both caches`() {
        seed(listOf(chapters.first()))
        bodies = mapOf(1 to longerB, 2 to a)
        markStale()

        downloads.download(chapters.first(), server.url("/").toString(), replaceExisting = true)
        assertEquals(setOf(1), scanner.staleChapters.value)
        assertEquals(sha256(a), record.current()[1])
        submitRequests()
        awaitCompleted(setOf(1))

        assertArrayEquals(longerB, play(chapters.first()))
        assertEquals(longerB.size.toLong(), downloads.downloads.value.getValue("chapter:1").bytesDownloaded)
        assertTrue(scanner.staleChapters.value.isEmpty())
        assertEquals(sha256(longerB), record.current()[1])
        assertEquals(0L, downloads.streamingCacheBytes.value)
    }

    @Test
    fun `Update all replaces same URLs with both longer and shorter content B`() {
        seed(chapters)
        bodies = mapOf(1 to longerB, 2 to shorterB)
        markStale()

        downloads.download(chapters, server.url("/").toString(), replaceExisting = true)
        assertEquals(setOf(1, 2), scanner.staleChapters.value)
        submitRequests()
        awaitCompleted(setOf(1, 2))

        chapters.forEach { chapter ->
            assertArrayEquals(bodies.getValue(chapter.id), play(chapter))
            assertEquals(bodies.getValue(chapter.id).size.toLong(), downloads.downloads.value.getValue("chapter:${chapter.id}").bytesDownloaded)
        }
        assertTrue(scanner.staleChapters.value.isEmpty())
        assertEquals(hashes, record.current())
        assertEquals(0L, downloads.streamingCacheBytes.value)
    }

    @Test
    fun `a failed replacement stays stale and retries fetch only B`() {
        seed(listOf(chapters.first()))
        bodies = mapOf(1 to longerB, 2 to a)
        markStale()
        fail = true
        downloads.download(chapters.first(), server.url("/").toString(), replaceExisting = true)
        submitRequests()
        await { downloads.downloads.value["chapter:1"]?.state == ChapterDownloadState.Failed }
        scan()

        assertEquals(setOf(1), scanner.staleChapters.value)
        assertEquals(sha256(a), record.current()[1])
        assertEquals(0L, downloads.downloads.value.getValue("chapter:1").bytesDownloaded)

        fail = false
        downloads.download(chapters.first(), server.url("/").toString(), replaceExisting = true)
        submitRequests()
        awaitCompleted(setOf(1))
        assertArrayEquals(longerB, play(chapters.first()))
        assertTrue(scanner.staleChapters.value.isEmpty())
    }

    @Test
    fun `bytes whose hash disagrees with the replacement verdict never become fresh`() {
        seed(listOf(chapters.first()))
        bodies = mapOf(1 to longerB, 2 to a)
        markStale()
        bodies = mapOf(1 to shorterB, 2 to a)
        downloads.download(chapters.first(), server.url("/").toString(), replaceExisting = true)
        submitRequests()
        await { downloads.downloads.value["chapter:1"]?.state == ChapterDownloadState.Failed }
        scan()

        assertEquals(setOf(1), scanner.staleChapters.value)
        assertEquals(sha256(a), record.current()[1])
    }

    @Test
    fun `a cancelled replacement retains its stale verdict and old hash`() {
        seed(listOf(chapters.first()))
        bodies = mapOf(1 to longerB, 2 to a)
        markStale()
        downloads.downloadManager.pauseDownloads()
        downloads.download(chapters.first(), server.url("/").toString(), replaceExisting = true)
        submitRequests()
        await { downloads.downloads.value["chapter:1"]?.state == ChapterDownloadState.Queued }
        downloads.remove(1)
        submitRequests()
        await { "chapter:1" !in downloads.downloads.value }
        scan()

        assertEquals(setOf(1), scanner.staleChapters.value)
        assertEquals(sha256(a), record.current()[1])
    }

    @Test
    fun `a refused service start cannot clear stale status or record the new hash`() {
        seed(listOf(chapters.first()))
        bodies = mapOf(1 to longerB, 2 to a)
        markStale()
        context.refuse = true

        assertTrue(runCatching {
            downloads.download(chapters.first(), server.url("/").toString(), replaceExisting = true)
        }.isFailure)
        scan()
        assertEquals(setOf(1), scanner.staleChapters.value)
        assertEquals(sha256(a), record.current()[1])
        assertArrayEquals(a, play(chapters.first()))
    }

    private fun seed(selected: List<ChapterSummary>) {
        selected.forEach { assertArrayEquals(a, play(it, offline = false)) }
        downloads.download(selected, server.url("/").toString())
        submitRequests()
        awaitCompleted(selected.mapTo(mutableSetOf()) { it.id })
        scan()
        assertFalse(record.current().isEmpty())
    }

    private fun markStale() {
        hashes = bodies.mapValues { sha256(it.value) }
        scan()
        assertFalse(scanner.staleChapters.value.isEmpty())
    }

    private fun scan() = runBlocking {
        val known = downloads.downloads.value
        scanner.scan(
            9,
            known.filterValues { it.state.isAvailableOffline }.keys.mapTo(mutableSetOf()) { it.substringAfter(':').toInt() },
            known.keys.mapTo(mutableSetOf()) { it.substringAfter(':').toInt() },
        )
    }

    private fun submitRequests() {
        val intents = context.intents.toList()
        context.intents.clear()
        intents.forEach { intent ->
            when (intent.action) {
                DownloadService.ACTION_ADD_DOWNLOAD -> downloads.downloadManager.addDownload(
                    checkNotNull(intent.getParcelableExtra(DownloadService.KEY_DOWNLOAD_REQUEST, DownloadRequest::class.java)),
                )
                DownloadService.ACTION_REMOVE_DOWNLOAD -> downloads.downloadManager.removeDownload(
                    checkNotNull(intent.getStringExtra(DownloadService.KEY_CONTENT_ID)),
                )
            }
        }
    }

    private fun awaitCompleted(ids: Set<Int>) = await {
        ids.all { downloads.downloads.value["chapter:$it"]?.state == ChapterDownloadState.Downloaded } &&
            downloads.downloadManager.isIdle
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("download state: ${downloads.downloads.value}", condition())
    }

    private fun play(chapter: ChapterSummary, offline: Boolean = true): ByteArray {
        val upstream = if (offline) DataSource.Factory {
            object : DataSource {
                override fun addTransferListener(listener: TransferListener) = Unit
                override fun open(dataSpec: DataSpec): Long = throw IOException("offline")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw IOException("offline")
                override fun getUri(): android.net.Uri? = null
                override fun close() = Unit
            }
        } else DefaultHttpDataSource.Factory()
        val source = downloads.readThroughFactory(upstream).createDataSource()
        return try {
            source.open(DataSpec.Builder().setUri(chapter.audio!!.url).build())
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count == C.RESULT_END_OF_INPUT) break
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        } finally {
            source.close()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private class CapturingContext(base: Context) : ContextWrapper(base) {
        val intents = mutableListOf<Intent>()
        var refuse = false
        override fun startService(service: Intent): ComponentName? {
            if (refuse) throw IllegalStateException("service refused")
            intents += service
            return service.component
        }
    }
}
