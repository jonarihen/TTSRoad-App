package dk.perspektiva.ttsroad.download

import android.content.Intent
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import dk.perspektiva.ttsroad.data.AudioInfo
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.LoginResponse
import dk.perspektiva.ttsroad.data.ReadAlongFileStore
import dk.perspektiva.ttsroad.data.ServerCapabilities
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.SessionStore
import dk.perspektiva.ttsroad.data.TokenStore
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineReaderPinLifecycleTest {
    private lateinit var server: MockWebServer
    private lateinit var directory: File
    private lateinit var repository: TtsRoadRepository
    private lateinit var disk: ReadAlongFileStore
    private var downloads: OfflineDownloads? = null
    private val release = CountDownLatch(1)
    private val results = mutableListOf<CompletableDeferred<Boolean>>()
    private val capabilities = MutableStateFlow(ServerCapabilities(readAlong = true))
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        server = MockWebServer().apply { start() }
        directory = kotlin.io.path.createTempDirectory("offline-reader").toFile()
        disk = ReadAlongFileStore(directory)
        repository = TtsRoadRepository(sessionStore(), readAlongStore = disk)
    }

    @After
    fun tearDown() {
        release.countDown()
        downloads?.closeWithoutManagerForTest()
        server.shutdown()
        directory.deleteRecursively()
        Dispatchers.resetMain()
    }

    private fun sessionStore() = object : SessionStore {
        private var state = SessionState(server.url("/").toString(), "reader-token", "admin")
        override suspend fun current() = state
        override suspend fun saveLogin(baseUrl: String, response: LoginResponse) = Unit
        override suspend fun clearToken() { state = state.copy(token = null) }
    }

    private fun createDownloads(ignoreCancellation: Boolean = false): OfflineDownloads = OfflineDownloads(
        context = context,
        tokenStore = TokenStore(context),
        capabilities = capabilities,
        downloadPrefs = emptyFlow(),
        pinReadAlong = { chapterId ->
            val result = CompletableDeferred<Boolean>()
            synchronized(results) { results += result }
            try {
                val held = if (ignoreCancellation) {
                    withContext(NonCancellable) { repository.pinReadAlong(chapterId) }
                } else {
                    repository.pinReadAlong(chapterId)
                }
                result.complete(held)
                held
            } catch (e: Exception) {
                result.completeExceptionally(e)
                throw e
            }
        },
        unpinReadAlong = repository::unpinReadAlong,
        initializeManager = false,
    ).also { downloads = it }

    private fun chapter(hasTimings: Boolean? = true, id: Int = 10) = ChapterSummary(
        id = id,
        fictionId = 1,
        playable = true,
        hasTimings = hasTimings,
        audio = AudioInfo(url = server.url("/audio/$id.mp3").toString()),
    )

    private fun body(text: String = "Original text.", id: Int = 10, timed: Boolean = true) = """
        {"chapter":{"id":$id,"fiction_id":1,"has_timings":$timed,"audio_duration":60},
         "text":"$text","paragraphs":[[0,${text.length}]],"cues":${if (timed) "[[0,3,0.0]]" else "[]"}}
    """.trimIndent()

    private suspend fun result(index: Int = 0): Boolean = withTimeout(10_000) {
        synchronized(results) { results[index] }.await()
    }

    private fun nextService(): Intent = requireNotNull(shadowOf(context).nextStartedService)

    private fun deferFirstResponse() {
        val requests = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (requests.getAndIncrement() == 0) {
                    check(release.await(10, SECONDS)) { "reader response was never released" }
                    return MockResponse().setBody(body()).setHeader("ETag", "\"old\"")
                }
                return MockResponse().setBody(body("New intent.")).setHeader("ETag", "\"new\"")
            }
        }
    }

    private fun awaitReaderRequest() {
        assertEquals("/api/mobile/chapters/10/readalong", server.takeRequest(10, SECONDS)?.path)
    }

    @Test
    fun `capable servers prefetch timed untimed and unknown chapters`() = runBlocking {
        val downloads = createDownloads()
        listOf(false, true, null).forEachIndexed { index, timing ->
            val id = 10 + index
            server.enqueue(MockResponse().setBody(body(id = id, timed = timing == true)))
            downloads.download(chapter(timing, id), server.url("/").toString())
            assertEquals("androidx.media3.exoplayer.downloadService.action.ADD_DOWNLOAD", nextService().action)
            assertTrue(result(index))
            assertEquals("/api/mobile/chapters/$id/readalong", server.takeRequest(10, SECONDS)?.path)
            assertTrue(ReadAlongFileStore(directory).isPinned(id))
        }
    }

    @Test
    fun `an untimed chapter downloaded before opening its reader reads offline after restart`() = runBlocking {
        val downloads = createDownloads()
        server.enqueue(MockResponse().setBody(body(timed = false)))
        downloads.download(chapter(false), server.url("/").toString())
        nextService()
        assertTrue(result())
        awaitReaderRequest()
        assertNull(repository.loadedReadAlong(10))
        downloads.closeWithoutManagerForTest()
        this@OfflineReaderPinLifecycleTest.downloads = null

        val restarted = TtsRoadRepository(sessionStore(), readAlongStore = ReadAlongFileStore(directory))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val document = restarted.readAlong(10)

        assertNotNull(document)
        assertEquals("Original text.", document!!.text)
        assertTrue(document.paragraphs.isNotEmpty())
        assertFalse(document.hasTimings)
        assertTrue(document.cues.isEmpty())
    }

    @Test
    fun `a server without readalong queues audio without a reader request`() {
        capabilities.value = ServerCapabilities.Baseline
        createDownloads().download(chapter(false), server.url("/").toString())

        assertNotNull(nextService())
        assertTrue(results.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `reader 404 and failure leave the audio request queued`() = runBlocking {
        val downloads = createDownloads()
        listOf(404, 500).forEachIndexed { index, code ->
            server.enqueue(MockResponse().setResponseCode(code))
            downloads.download(chapter(id = 10 + index), server.url("/").toString())
            assertEquals("androidx.media3.exoplayer.downloadService.action.ADD_DOWNLOAD", nextService().action)
            assertFalse(result(index))
            assertFalse(disk.isPinned(10 + index))
            assertNull(repository.sessionEnd.value)
            assertNull(shadowOf(context).nextStartedService)
        }
    }

    @Test
    fun `audio finishes successfully while reader prefetch fails`() = runBlocking {
        val audio = ByteArray(4096) { (it % 251).toByte() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == "/audio/10.mp3") {
                    MockResponse().setBody(Buffer().write(audio))
                } else {
                    MockResponse().setResponseCode(500)
                }
        }
        val downloads = createDownloads()
        downloads.download(chapter(), server.url("/").toString())
        val request = requireNotNull(nextService().getParcelableExtra("download_request", DownloadRequest::class.java))
        val cache = SimpleCache(File(directory, "audio"), NoOpCacheEvictor(), StandaloneDatabaseProvider(context))
        try {
            val downloader = ProgressiveDownloader(
                request.toMediaItem(),
                CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory()),
            )
            withContext(Dispatchers.IO) { downloader.download(null) }

            assertFalse(result())
            assertEquals(audio.size.toLong(), cache.cacheSpace)
            assertTrue(cache.isCached(requireNotNull(request.customCacheKey), 0, audio.size.toLong()))
            assertFalse(disk.isPinned(10))
        } finally {
            cache.release()
        }
    }

    @Test
    fun `remove all releases a completed pin not yet published in the download index`() = runBlocking {
        server.enqueue(MockResponse().setBody(body()))
        val downloads = createDownloads()
        downloads.download(chapter(), server.url("/").toString())
        nextService()
        assertTrue(result())
        assertTrue(downloads.downloads.value.isEmpty())

        downloads.removeAll()
        nextService()

        assertFalse(ReadAlongFileStore(directory).isPinned(10))
        assertNull(ReadAlongFileStore(directory).read(10))
    }

    @Test
    fun `remove prevents a delayed successful pin from recreating the document`() = runBlocking {
        deferFirstResponse()
        val downloads = createDownloads(ignoreCancellation = true)
        downloads.download(chapter(), server.url("/").toString())
        nextService()
        awaitReaderRequest()
        downloads.remove(10)
        nextService()
        release.countDown()

        assertFalse(result())
        assertFalse(ReadAlongFileStore(directory).isPinned(10))
        assertNull(ReadAlongFileStore(directory).read(10))
    }

    @Test
    fun `remove all invalidates a pin before its download reaches the index`() = runBlocking {
        deferFirstResponse()
        val downloads = createDownloads(ignoreCancellation = true)
        downloads.download(chapter(), server.url("/").toString())
        nextService()
        awaitReaderRequest()
        assertTrue(downloads.downloads.value.isEmpty())
        downloads.removeAll()
        nextService()
        release.countDown()

        assertFalse(result())
        assertFalse(ReadAlongFileStore(directory).isPinned(10))
        assertNull(ReadAlongFileStore(directory).read(10))
    }

    @Test
    fun `remove and redownload retain the new pin when the old response completes last`() = runBlocking {
        deferFirstResponse()
        val downloads = createDownloads(ignoreCancellation = true)
        downloads.download(chapter(), server.url("/").toString())
        nextService()
        awaitReaderRequest()
        downloads.remove(10)
        nextService()
        downloads.download(chapter(), server.url("/").toString())
        nextService()
        assertTrue(result(1))
        release.countDown()

        assertFalse(result())
        val restarted = ReadAlongFileStore(directory)
        assertTrue(restarted.isPinned(10))
        assertEquals("New intent.", restarted.read(10)?.response?.text)
        assertEquals("\"new\"", restarted.read(10)?.etag)
    }

    @Test
    fun `remove cancels a cooperative prefetch without sending an audio retry`() = runBlocking {
        deferFirstResponse()
        val downloads = createDownloads()
        downloads.download(chapter(), server.url("/").toString())
        nextService()
        awaitReaderRequest()
        downloads.remove(10)
        nextService()
        release.countDown()

        assertTrue(runCatching { result() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertFalse(disk.isPinned(10))
        assertNull(shadowOf(context).nextStartedService)
    }
}
