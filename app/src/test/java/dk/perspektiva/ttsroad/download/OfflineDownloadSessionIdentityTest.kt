package dk.perspektiva.ttsroad.download

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import dk.perspektiva.ttsroad.data.AudioInfo
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.LoginResponse
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.SessionStore
import dk.perspektiva.ttsroad.data.TokenStore
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
class OfflineDownloadSessionIdentityTest {
    private lateinit var context: Context
    private lateinit var offline: OfflineDownloads
    private lateinit var repository: TtsRoadRepository
    private lateinit var downloadCache: Cache
    private lateinit var streamingCache: Cache
    private lateinit var databaseProvider: StandaloneDatabaseProvider
    private val session = MutableStateFlow(SessionState())
    private val servers = mutableListOf<MockWebServer>()
    private val bodyA = ByteArray(32) { 1 }
    private val bodyB = ByteArray(32) { 2 }
    private var upstreamOpens = 0
    private var reachable = true

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        context = RuntimeEnvironment.getApplication()
        repository = TtsRoadRepository(object : SessionStore {
            override suspend fun current() = session.value
            override suspend fun saveLogin(baseUrl: String, response: LoginResponse) = Unit
            override suspend fun clearToken() {
                session.value = session.value.copy(token = null)
            }
        })
        openOfflineDownloads()
    }

    @After
    fun tearDown() {
        closeOfflineDownloads()
        servers.forEach { it.shutdown() }
        Dispatchers.resetMain()
    }

    @Test
    fun `switching to discovery without base_url never reads or writes the old scope`() = runTest {
        assertServerSwitch(MockResponse().setBody("""{"server":{"name":"Old server"},"capabilities":{}}"""))
    }

    @Test
    fun `switching to 404 discovery never reads or writes the old scope`() = runTest {
        assertServerSwitch(MockResponse().setResponseCode(404))
    }

    @Test
    fun `switching to failed discovery never reads or writes the old scope`() = runTest {
        assertServerSwitch(MockResponse().setResponseCode(503))
    }

    @Test
    fun `same-address discovery failure and restart preserve canonical downloads`() = runTest {
        val server = server()
        val address = server.url("/").toString()
        session.value = SessionState(serverUrl = address, token = "a")
        server.enqueue(canonicalDiscovery())
        repository.refreshCurrentCapabilities()
        runCurrent()
        val request = queue(address)
        write(downloadCache, request.customCacheKey!!, bodyA)
        val before = downloadCache.keys.toSet()

        repository.forgetCapabilities(address)
        server.enqueue(MockResponse().setResponseCode(503))
        repository.refreshCurrentCapabilities(forceRefresh = true)
        runCurrent()
        reachable = false
        assertArrayEquals(bodyA, read(address))
        assertEquals(before, downloadCache.keys)
        assertEquals(request.customCacheKey, queue(address).customCacheKey)
        assertEquals(0, upstreamOpens)

        closeOfflineDownloads()
        openOfflineDownloads()
        runCurrent()
        assertArrayEquals(bodyA, read(address))
        assertEquals(before, downloadCache.keys)
        assertEquals(0, upstreamOpens)
    }

    @Test
    fun `changing address with the same canonical identity keeps downloads offline`() = runTest {
        val first = server()
        val second = server()
        val addressA = first.url("/").toString()
        val addressB = second.url("/").toString()
        session.value = SessionState(serverUrl = addressA, token = "a")
        first.enqueue(canonicalDiscovery())
        repository.refreshCurrentCapabilities()
        runCurrent()
        val request = queue(addressA)
        write(downloadCache, request.customCacheKey!!, bodyA)

        session.value = SessionState(serverUrl = addressB, token = "b")
        second.enqueue(canonicalDiscovery())
        repository.refreshCurrentCapabilities()
        runCurrent()
        reachable = false
        assertArrayEquals(bodyA, read(addressB))
        assertEquals(request.customCacheKey, queue(addressB).customCacheKey)
        assertEquals(setOf(request.customCacheKey), downloadCache.keys)
        assertEquals(0, upstreamOpens)
    }

    @Test
    fun `downloads made before canonical discovery remain playable without rekeying`() = runTest {
        val server = server()
        val address = server.url("/").toString()
        session.value = SessionState(serverUrl = address, token = "a")
        server.enqueue(MockResponse().setResponseCode(503))
        repository.refreshCurrentCapabilities()
        runCurrent()
        val request = queue(address)
        write(downloadCache, request.customCacheKey!!, bodyA)

        server.enqueue(canonicalDiscovery())
        repository.refreshCurrentCapabilities(forceRefresh = true)
        runCurrent()
        reachable = false
        assertNotEquals(request.customCacheKey, queue(address).customCacheKey)
        assertArrayEquals(bodyA, read(address))
        assertEquals(setOf(request.customCacheKey), downloadCache.keys)
        assertEquals(0, upstreamOpens)
    }

    private suspend fun TestScope.assertServerSwitch(discovery: MockResponse) {
        val first = server()
        val second = server()
        val addressA = first.url("/").toString()
        val addressB = second.url("/").toString()
        session.value = SessionState(serverUrl = addressA, token = "a")
        first.enqueue(canonicalDiscovery())
        repository.refreshCurrentCapabilities()
        runCurrent()
        val requestA = queue(addressA)
        write(downloadCache, requestA.customCacheKey!!, bodyA)
        write(streamingCache, requestA.customCacheKey!!, bodyA)

        session.value = SessionState(serverUrl = addressB, token = "b")
        runCurrent()
        reachable = false
        assertTrue(runCatching { read(addressB) }.isFailure)
        assertNotEquals(requestA.customCacheKey, queue(addressB).customCacheKey)

        second.enqueue(discovery)
        repository.refreshCurrentCapabilities()
        runCurrent()
        val requestB = queue(addressB)
        assertNotEquals(requestA.customCacheKey, requestB.customCacheKey)
        assertTrue(DownloadCacheKeys.isScoped(requestB.customCacheKey!!))
        reachable = true
        assertArrayEquals(bodyB, read(addressB))
        assertEquals(1, upstreamOpens)
        assertTrue(streamingCache.keys.contains(requestB.customCacheKey))
        assertEquals(setOf(requestA.customCacheKey), downloadCache.keys)
        assertFalse(downloadCache.keys.contains(requestB.customCacheKey))

        write(downloadCache, requestB.customCacheKey!!, bodyB)
        reachable = false
        assertArrayEquals(bodyB, read(addressB))
        assertEquals(1, upstreamOpens)
    }

    private fun openOfflineDownloads() {
        offline = newOfflineDownloads()
        downloadCache = cache("getDownloadCache")
        streamingCache = cache("getStreamingCache")
        databaseProvider = OfflineDownloads::class.java.getDeclaredMethod("getDatabaseProvider")
            .apply { isAccessible = true }.invoke(offline) as StandaloneDatabaseProvider
    }

    private fun closeOfflineDownloads() {
        offline.closeWithoutManagerForTest()
        downloadCache.release()
        streamingCache.release()
        databaseProvider.close()
    }

    private fun newOfflineDownloads() = OfflineDownloads(
        context = context,
        tokenStore = TokenStore(context),
        capabilities = repository.currentCapabilities,
        downloadPrefs = emptyFlow(),
        initializeManager = false,
        session = session,
    )

    private fun cache(getter: String): Cache = OfflineDownloads::class.java.getDeclaredMethod(getter)
        .apply { isAccessible = true }.invoke(offline) as Cache

    private fun server() = MockWebServer().also {
        it.start()
        servers += it
    }

    private fun canonicalDiscovery() = MockResponse().setBody(
        """{"server":{"base_url":"https://canonical.example"},"capabilities":{}}""",
    )

    private fun queue(address: String): DownloadRequest {
        offline.download(
            ChapterSummary(id = 7, fictionId = 1, audio = AudioInfo(url = "${address}audio/chapter.mp3")),
            address,
        )
        val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        return intent.getParcelableExtra(DownloadService.KEY_DOWNLOAD_REQUEST, DownloadRequest::class.java)!!
    }

    private fun write(cache: Cache, key: String, bytes: ByteArray) {
        val hole = cache.startReadWrite(key, 0, bytes.size.toLong())
        val file = cache.startFile(key, 0, bytes.size.toLong())
        file.writeBytes(bytes)
        cache.commitFile(file, bytes.size.toLong())
        cache.releaseHoleSpan(hole)
    }

    private fun read(address: String): ByteArray {
        val source = offline.readThroughFactory(DataSource.Factory { object : DataSource {
            private var position = 0
            override fun addTransferListener(transferListener: TransferListener) = Unit
            override fun open(dataSpec: DataSpec): Long {
                if (!reachable) throw IOException("unreachable")
                upstreamOpens++
                position = dataSpec.position.toInt()
                return (bodyB.size - position).toLong()
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (position == bodyB.size) return C.RESULT_END_OF_INPUT
                val count = minOf(length, bodyB.size - position)
                bodyB.copyInto(buffer, offset, position, position + count)
                position += count
                return count
            }
            override fun getUri(): Uri = Uri.parse("${address}audio/chapter.mp3")
            override fun close() = Unit
        } }).createDataSource()
        return try {
            source.open(DataSpec.Builder().setUri("${address}audio/chapter.mp3").build())
            val result = ByteArray(bodyB.size)
            var filled = 0
            while (filled < result.size) {
                val count = source.read(result, filled, result.size - filled)
                if (count == C.RESULT_END_OF_INPUT) break
                filled += count
            }
            result.copyOf(filled)
        } finally {
            source.close()
        }
    }
}
