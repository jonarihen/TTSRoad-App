package dk.perspektiva.ttsroad.download

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadIndex
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import dk.perspektiva.ttsroad.data.AudioHash
import dk.perspektiva.ttsroad.data.AudioHashesResponse
import dk.perspektiva.ttsroad.data.AudioInfo
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.DownloadPrefs
import dk.perspektiva.ttsroad.data.ServerCapabilities
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.TokenStore
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Two servers, one chapter number (#316).
 *
 * Chapter ids are only unique per server, and Media3 keys the whole download index on the request
 * id. Signed in to server B, server A's chapter 42 must not show as downloaded, must not count as
 * handled by keep-ahead, and must not be replaced or deleted by anything done to B's chapter 42 —
 * and its audio hash must survive B's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineDownloadServerScopeTest {
    private lateinit var context: CapturingContext
    private lateinit var downloads: OfflineDownloads
    private lateinit var scanner: StaleDownloadScanner
    private lateinit var record: AudioHashRecord
    private lateinit var serverA: MockWebServer
    private lateinit var serverB: MockWebServer
    private val bodyA = ByteArray(2048) { (it % 251).toByte() }
    private val bodyB = ByteArray(3072) { ((it + 7) % 241).toByte() }
    private val session = MutableStateFlow(SessionState())
    @Volatile private var serverHash = "hash-a"

    @Before
    fun setUp() {
        context = CapturingContext(RuntimeEnvironment.getApplication())
        record = AudioHashRecord(context).also { it.clear() }
        serverA = serving(bodyA)
        serverB = serving(bodyB)
        scanner = StaleDownloadScanner(record) {
            AudioHashesResponse(fictionId = 9, total = 1, chapters = listOf(AudioHash(42, serverHash)))
        }
        downloads = OfflineDownloads(
            context,
            TokenStore(context),
            capabilities = MutableStateFlow(ServerCapabilities.Baseline),
            downloadPrefs = flowOf(DownloadPrefs(keepAheadChapters = 2)),
            session = session,
            forgetAudioHash = { scope, chapterId -> scanner.forget(chapterId, scope) },
            initializeManager = false,
            replacementHash = scanner::replacementHash,
            replacementCompleted = { scope, chapterId, hash -> scanner.replacementCompleted(chapterId, hash, scope) },
            migrationIndex = object : DownloadIndex {
                override fun getDownload(id: String) = throw IOException("no migration here")
                override fun getDownloads(vararg states: Int) = throw IOException("no migration here")
            },
            audioHashScope = scanner::useScope,
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
        serverA.shutdown()
        serverB.shutdown()
    }

    @Test
    fun `a chapter downloaded on one server is neither seen nor touched from another`() {
        signIn(serverA)
        downloads.download(chapter(serverA), address(serverA))
        submitRequests()
        awaitDownloaded()
        scan()
        assertEquals(mapOf(42 to "hash-a"), record.current(scope(serverA)))

        signIn(serverB)
        await { downloads.downloads.value.isEmpty() }

        // Keep-ahead does not count A's row as B's chapter, and queues B's own.
        assertTrue(downloads.applyKeepAhead(listOf(chapter(serverB)), 42, 2, 9, address(serverB)))
        val requestB = submitRequests().single()
        assertTrue(requestB.id, isScopedDownloadId(requestB.id))
        assertEquals(42, chapterIdOfDownload(requestB.id))
        awaitDownloaded()
        assertArrayEquals(bodyB, playOffline(chapter(serverB)))
        assertEquals(DownloadOrigin.Auto, downloads.downloads.value.getValue("chapter:42").origin)

        // A's row and bytes were not merged over.
        val rowA = downloads.downloadManager.downloadIndex.getDownload("chapter:42")!!
        assertEquals(Download.STATE_COMPLETED, rowA.state)
        assertEquals(chapter(serverA).audio!!.url, rowA.request.uri.toString())

        // B's freshness check neither reads nor prunes A's hash.
        serverHash = "hash-b"
        scan()
        assertTrue(scanner.staleChapters.value.isEmpty())
        assertEquals(mapOf(42 to "hash-b"), record.current(scope(serverB)))
        assertEquals(mapOf(42 to "hash-a"), record.current(scope(serverA)))

        // Removing B's 42 removes B's row only.
        assertTrue(downloads.remove(42))
        assertEquals(listOf(requestB.id), submittedRemovals())
        await { downloads.downloads.value.isEmpty() && downloads.downloadManager.isIdle }
        assertNull(downloads.downloadManager.downloadIndex.getDownload(requestB.id))
        assertEquals(Download.STATE_COMPLETED, downloads.downloadManager.downloadIndex.getDownload("chapter:42")!!.state)
        assertFalse(42 in record.current(scope(serverB)))
        assertEquals(mapOf(42 to "hash-a"), record.current(scope(serverA)))

        signIn(serverA)
        awaitDownloaded()
        assertArrayEquals(bodyA, playOffline(chapter(serverA)))
    }

    @Test
    fun `hashes recorded before scoping are claimed by the first server to record`() {
        record.merge(mapOf(42 to "legacy"))
        signIn(serverA)
        shadowOf(Looper.getMainLooper()).idle()

        record.merge(mapOf(7 to "seven"), scope(serverA))

        assertEquals(mapOf(7 to "seven", 42 to "legacy"), record.current(scope(serverA)))
        assertTrue(record.current(scope(serverB)).isEmpty())
        assertTrue(record.current().isEmpty())
        assertEquals(record.current(scope(serverA)), AudioHashRecord(context).current(scope(serverA)))
    }

    private fun serving(body: ByteArray) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(Buffer().write(body))
        }
        start()
    }

    private fun address(server: MockWebServer) = server.url("/").toString()

    /** The address fallback identity: these servers never advertise one of their own. */
    private fun scope(server: MockWebServer) = "address:${address(server)}"

    private fun chapter(server: MockWebServer) =
        ChapterSummary(id = 42, fictionId = 9, audio = AudioInfo(url = server.url("/audio/42.mp3").toString()))

    private fun signIn(server: MockWebServer) {
        session.value = SessionState(serverUrl = address(server), token = "token-${server.port}")
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun scan() = runBlocking {
        val known = downloads.downloads.value.keys.mapTo(mutableSetOf()) { it.substringAfter(':').toInt() }
        scanner.scan(9, known)
    }

    private fun awaitDownloaded() = await {
        downloads.downloads.value["chapter:42"]?.state == ChapterDownloadState.Downloaded &&
            downloads.downloadManager.isIdle
    }

    /** Forward captured service commands to the manager; answers the requests that were added. */
    private fun submitRequests(): List<DownloadRequest> {
        val intents = context.intents.toList()
        context.intents.clear()
        return intents.mapNotNull { intent ->
            when (intent.action) {
                DownloadService.ACTION_ADD_DOWNLOAD -> checkNotNull(
                    intent.getParcelableExtra(DownloadService.KEY_DOWNLOAD_REQUEST, DownloadRequest::class.java),
                ).also(downloads.downloadManager::addDownload)
                DownloadService.ACTION_REMOVE_DOWNLOAD -> {
                    downloads.downloadManager.removeDownload(checkNotNull(intent.getStringExtra(DownloadService.KEY_CONTENT_ID)))
                    null
                }
                else -> null
            }
        }
    }

    private fun submittedRemovals(): List<String> {
        val removals = context.intents
            .filter { it.action == DownloadService.ACTION_REMOVE_DOWNLOAD }
            .map { checkNotNull(it.getStringExtra(DownloadService.KEY_CONTENT_ID)) }
        submitRequests()
        return removals
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

    private fun playOffline(chapter: ChapterSummary): ByteArray {
        val offline = DataSource.Factory {
            object : DataSource {
                override fun addTransferListener(listener: TransferListener) = Unit
                override fun open(dataSpec: DataSpec): Long = throw IOException("offline")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw IOException("offline")
                override fun getUri(): android.net.Uri? = null
                override fun close() = Unit
            }
        }
        val source = downloads.readThroughFactory(offline).createDataSource()
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

    private class CapturingContext(base: Context) : ContextWrapper(base) {
        val intents = mutableListOf<Intent>()
        override fun startService(service: Intent): ComponentName? {
            intents += service
            return service.component
        }
    }
}
