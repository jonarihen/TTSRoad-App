package dk.perspektiva.ttsroad.download

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadCursor
import androidx.media3.exoplayer.offline.DownloadIndex
import androidx.media3.exoplayer.offline.DownloadProgress
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
    private var migrationReads = 0
    private var migrationRead: ((DefaultDownloadIndex) -> DownloadCursor)? = null

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

    @Test
    fun `legacy downloads are readable offline before discovery and after an old-server 404`() = runTest {
        assertLegacyPlayback(MockResponse().setResponseCode(404))
    }

    @Test
    fun `legacy downloads remain readable offline when discovery omits identity`() = runTest {
        assertLegacyPlayback(MockResponse().setBody("""{"capabilities":{}}"""))
    }

    @Test
    fun `legacy downloads remain readable offline during transient discovery failure`() = runTest {
        assertLegacyPlayback(MockResponse().setResponseCode(503))
    }

    @Test
    fun `legacy downloads never leak to another server with failed discovery`() = runTest {
        assertLegacyServerSwitch(MockResponse().setResponseCode(503))
    }

    @Test
    fun `legacy downloads never leak to another server with 404 discovery`() = runTest {
        assertLegacyServerSwitch(MockResponse().setResponseCode(404))
    }

    @Test
    fun `legacy downloads never leak to another server without identity`() = runTest {
        assertLegacyServerSwitch(MockResponse().setBody("""{"capabilities":{}}"""))
    }

    private suspend fun TestScope.assertLegacyServerSwitch(discovery: MockResponse) {
        val first = server().url("/").toString()
        val secondServer = server()
        val second = secondServer.url("/").toString()
        legacyDownload(first)
        session.value = SessionState(serverUrl = second, token = "b")
        secondServer.enqueue(discovery)
        repository.refreshCurrentCapabilities()
        runCurrent()
        reachable = false
        assertTrue(runCatching { read(second) }.isFailure)
        reachable = true
        assertArrayEquals(bodyB, read(second))
        assertEquals(setOf("/audio/chapter.mp3"), downloadCache.keys)
        assertEquals(setOf(queue(second).customCacheKey), streamingCache.keys)
    }

    @Test
    fun `unindexed legacy spans are not attributed to the current server`() = runTest {
        val address = server().url("/").toString()
        write(downloadCache, "/audio/chapter.mp3", bodyA)
        session.value = SessionState(serverUrl = address, token = "a")
        runCurrent()
        reachable = false
        assertTrue(runCatching { read(address) }.isFailure)
    }

    @Test
    fun `ambiguous legacy ownership cannot hand either servers audio to the other`() = runTest {
        val first = server().url("/").toString()
        val second = server().url("/").toString()
        legacyDownload(first)
        val request = DownloadRequest.Builder("chapter:8", Uri.parse(second + "audio/chapter.mp3"))
            .setCustomCacheKey("/audio/chapter.mp3").build()
        DefaultDownloadIndex(databaseProvider).putDownload(
            Download(request, Download.STATE_COMPLETED, 0, 0, bodyA.size.toLong(), 0, Download.FAILURE_REASON_NONE, DownloadProgress()),
        )
        session.value = SessionState(serverUrl = first, token = "a")
        runCurrent()
        reachable = false
        assertTrue(runCatching { read(first) }.isFailure)
    }

    @Test
    fun `partial legacy downloads never cause unscoped streaming writes`() = runTest {
        val address = server().url("/").toString()
        legacyDownload(address, bytes = bodyA.copyOf(8))
        session.value = SessionState(serverUrl = address, token = "a")
        runCurrent()
        assertArrayEquals(bodyA.copyOf(8) + bodyB.copyOfRange(8, bodyB.size), read(address))
        assertEquals(setOf(queue(address).customCacheKey), streamingCache.keys)
        assertEquals(setOf("/audio/chapter.mp3"), downloadCache.keys)
    }

    @Test
    fun `equivalent server spellings preserve address downloads offline`() = runTest {
        val first = "https://TTSRoad.Example:443/Books/"
        val second = "https://ttsroad.example/Books"
        session.value = SessionState(serverUrl = first, token = "a")
        runCurrent()
        val request = queue(first)
        write(downloadCache, request.customCacheKey!!, bodyA)

        session.value = SessionState(serverUrl = second, token = "b")
        runCurrent()
        reachable = false
        assertEquals(request.customCacheKey, queue(second).customCacheKey)
        assertArrayEquals(bodyA, read(second))
        closeOfflineDownloads()
        openOfflineDownloads()
        runCurrent()
        assertArrayEquals(bodyA, read(second))
        assertEquals(0, upstreamOpens)
    }

    @Test
    fun `legacy downloads stay offline across equivalent origins but not mount namespaces`() = runTest {
        val first = "https://TTSRoad.Example:443/Books/"
        val equivalent = "https://ttsroad.example/Books/"
        legacyDownload(first)
        session.value = SessionState(serverUrl = equivalent, token = "a")
        runCurrent()
        reachable = false
        assertArrayEquals(bodyA, read(equivalent))

        session.value = SessionState(serverUrl = "https://ttsroad.example/", token = "b")
        runCurrent()
        assertTrue(runCatching { read(first) }.isFailure)
    }

    @Test
    fun `legacy downloads survive verified canonical address aliases`() = runTest {
        val firstServer = server()
        val secondServer = server()
        val first = firstServer.url("/").toString()
        val second = secondServer.url("/").toString()
        legacyDownload(first)
        session.value = SessionState(serverUrl = first, token = "a")
        firstServer.enqueue(canonicalDiscovery())
        repository.refreshCurrentCapabilities()
        runCurrent()
        session.value = SessionState(serverUrl = second, token = "b")
        secondServer.enqueue(canonicalDiscovery())
        repository.refreshCurrentCapabilities()
        runCurrent()
        reachable = false
        assertArrayEquals(bodyA, read(second))
        assertEquals(0, upstreamOpens)
    }

    @Test
    fun `a failed migration preserves all spans records and scoped plus legacy offline reads`() = runTest {
        val server = server()
        val address = server.url("/").toString()
        legacyDownload(address)
        write(downloadCache, "/audio/orphan.mp3", bodyB)
        write(downloadCache, "canonical.example /audio/chapter.mp3", bodyB)
        val before = cachedSpans()
        val records = indexedRecords()
        session.value = SessionState(serverUrl = address, token = "a")
        server.enqueue(canonicalDiscovery())
        repository.refreshCurrentCapabilities()
        runCurrent()

        assertEquals(1, migrationReads)
        assertEquals(before, cachedSpans())
        assertEquals(records, indexedRecords())
        assertEquals(null, shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        reachable = false
        assertArrayEquals(bodyB, read(address))
        downloadCache.removeResource("canonical.example /audio/chapter.mp3")
        assertArrayEquals(bodyA, read(address))
        assertEquals(0, upstreamOpens)
    }

    @Test
    fun `a partial migration index failure never cleans or rekeys records`() = runTest {
        val server = server()
        val address = server.url("/").toString()
        legacyDownload(address)
        write(downloadCache, "/audio/orphan.mp3", bodyB)
        val before = cachedSpans()
        val records = indexedRecords()
        migrationRead = { index ->
            val cursor = index.getDownloads()
            object : DownloadCursor by cursor {
                override fun moveToNext(): Boolean {
                    if (cursor.position >= 0) throw IOException("partial read")
                    return cursor.moveToNext()
                }
            }
        }
        session.value = SessionState(serverUrl = address, token = "a")
        server.enqueue(canonicalDiscovery())
        repository.refreshCurrentCapabilities()
        runCurrent()

        assertEquals(before, cachedSpans())
        assertEquals(records, indexedRecords())
        assertEquals(null, shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        reachable = false
        assertArrayEquals(bodyA, read(address))
    }

    @Test
    fun `foreground retry after a read fault migrates only verified owners and genuine orphans`() = runTest {
        val server = server()
        val address = server.url("/").toString()
        legacyDownload(address)
        val foreign = DownloadRequest.Builder("chapter:8", Uri.parse("https://other.example/audio/foreign.mp3"))
            .setCustomCacheKey("/audio/foreign.mp3").build()
        DefaultDownloadIndex(databaseProvider).putDownload(
            Download(foreign, Download.STATE_COMPLETED, 0, 0, bodyB.size.toLong(), 0, 0),
        )
        write(downloadCache, "/audio/foreign.mp3", bodyB)
        write(downloadCache, "/audio/orphan.mp3", bodyB)
        session.value = SessionState(serverUrl = address, token = "a")
        server.enqueue(canonicalDiscovery())
        repository.refreshCurrentCapabilities()
        runCurrent()
        assertEquals(1, migrationReads)
        val fresh = queue(address)
        write(downloadCache, fresh.customCacheKey!!, bodyB)
        assertEquals("canonical.example /audio/chapter.mp3", fresh.customCacheKey)
        migrationRead = { it.getDownloads() }

        offline.retryPendingIdentityMigration()
        runCurrent()

        assertEquals(2, migrationReads)
        assertEquals(setOf("/audio/chapter.mp3", "/audio/foreign.mp3", fresh.customCacheKey), downloadCache.keys)
        val application = shadowOf(RuntimeEnvironment.getApplication())
        val removed = application.nextStartedService
        assertEquals(DownloadService.ACTION_REMOVE_DOWNLOAD, removed.action)
        assertEquals("chapter:7", removed.getStringExtra(DownloadService.KEY_CONTENT_ID))
        val added = application.nextStartedService
        val request = added.getParcelableExtra(DownloadService.KEY_DOWNLOAD_REQUEST, DownloadRequest::class.java)!!
        assertEquals(fresh.customCacheKey, request.customCacheKey)
        assertEquals(address + "audio/chapter.mp3", request.uri.toString())
        assertEquals(null, application.nextStartedService)
        offline.retryPendingIdentityMigration()
        runCurrent()
        assertEquals(2, migrationReads)
    }

    @Test
    fun `a download queued while the index snapshot closes is canonical scoped and remains offline`() = runTest {
        val server = server()
        val address = server.url("/").toString()
        legacyDownload(address)
        session.value = SessionState(serverUrl = address, token = "a")
        server.enqueue(canonicalDiscovery())
        repository.refreshCurrentCapabilities()
        runCurrent()
        var fresh: DownloadRequest? = null
        migrationRead = { index ->
            val cursor = index.getDownloads()
            object : DownloadCursor by cursor {
                override fun close() {
                    cursor.close()
                    fresh = queue(address)
                    write(downloadCache, fresh!!.customCacheKey!!, bodyB)
                }
            }
        }

        offline.retryPendingIdentityMigration()
        runCurrent()

        assertEquals("canonical.example /audio/chapter.mp3", fresh!!.customCacheKey)
        reachable = false
        assertArrayEquals(bodyB, read(address))
        assertTrue(downloadCache.isCached(fresh!!.customCacheKey!!, 0, bodyB.size.toLong()))
    }

    @Test
    fun `ambiguous legacy records are not rekeyed during a successful migration`() = runTest {
        val server = server()
        val address = server.url("/").toString()
        legacyDownload(address)
        val foreign = DownloadRequest.Builder("chapter:8", Uri.parse("https://other.example/audio/chapter.mp3"))
            .setCustomCacheKey("/audio/chapter.mp3").build()
        DefaultDownloadIndex(databaseProvider).putDownload(
            Download(foreign, Download.STATE_COMPLETED, 0, 0, bodyB.size.toLong(), 0, 0),
        )
        val before = cachedSpans()
        val records = indexedRecords()
        migrationRead = { it.getDownloads() }
        session.value = SessionState(serverUrl = address, token = "a")
        server.enqueue(canonicalDiscovery())
        repository.refreshCurrentCapabilities()
        runCurrent()

        assertEquals(before, cachedSpans())
        assertEquals(records, indexedRecords())
        assertEquals(null, shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        reachable = false
        assertTrue(runCatching { read(address) }.isFailure)
    }

    @Test
    fun `failed A migration is cancelled when B has old server discovery`() = runTest {
        assertFailedMigrationServerSwitch(MockResponse().setResponseCode(404))
    }

    @Test
    fun `failed A migration is cancelled when B omits a canonical identity`() = runTest {
        assertFailedMigrationServerSwitch(MockResponse().setBody("""{"capabilities":{}}"""))
    }

    private suspend fun TestScope.assertFailedMigrationServerSwitch(discovery: MockResponse) {
        val first = server()
        val second = server()
        val addressA = first.url("/").toString()
        val addressB = second.url("/").toString()
        legacyDownload(addressA)
        session.value = SessionState(serverUrl = addressA, token = "a")
        first.enqueue(canonicalDiscovery())
        repository.refreshCurrentCapabilities()
        runCurrent()
        val requestA = queue(addressA)
        write(downloadCache, requestA.customCacheKey!!, bodyA)
        val before = cachedSpans()
        val records = indexedRecords()
        assertEquals(1, migrationReads)
        session.value = SessionState(serverUrl = addressB, token = "b")
        second.enqueue(discovery)
        repository.refreshCurrentCapabilities()
        runCurrent()
        migrationRead = { it.getDownloads() }
        offline.retryPendingIdentityMigration()
        runCurrent()

        assertEquals(1, migrationReads)
        assertEquals(before, cachedSpans())
        assertEquals(records, indexedRecords())
        assertEquals(null, shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        reachable = false
        assertTrue(runCatching { read(addressB) }.isFailure)
        val requestB = queue(addressB)
        assertNotEquals(requestA.customCacheKey, requestB.customCacheKey)
        assertTrue(requestB.customCacheKey!!.startsWith("address:"))
        write(downloadCache, requestB.customCacheKey!!, bodyB)
        assertArrayEquals(bodyB, read(addressB))
    }

    @Test
    fun `session switch inside the index snapshot aborts destructive work`() = runTest {
        val first = server()
        val second = server().url("/").toString()
        val address = first.url("/").toString()
        legacyDownload(address)
        write(downloadCache, "/audio/orphan.mp3", bodyB)
        val before = cachedSpans()
        val records = indexedRecords()
        migrationRead = { index ->
            val cursor = index.getDownloads()
            object : DownloadCursor by cursor {
                override fun close() {
                    cursor.close()
                    session.value = SessionState(serverUrl = second, token = "b")
                    testScheduler.runCurrent()
                }
            }
        }
        session.value = SessionState(serverUrl = address, token = "a")
        first.enqueue(canonicalDiscovery())
        repository.refreshCurrentCapabilities()
        runCurrent()
        offline.retryPendingIdentityMigration()
        runCurrent()

        assertEquals(1, migrationReads)
        assertEquals(before, cachedSpans())
        assertEquals(records, indexedRecords())
        assertEquals(null, shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        reachable = false
        assertTrue(runCatching { read(second) }.isFailure)
    }

    private fun indexedRecords(): List<Pair<DownloadRequest, Int>> =
        DefaultDownloadIndex(databaseProvider).getDownloads().use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.download.request to cursor.download.state)
            }
        }

    private fun cachedSpans(): Map<String, List<Pair<Long, List<Byte>>>> =
        downloadCache.keys.associateWith { key ->
            downloadCache.getCachedSpans(key).map { it.position to it.file!!.readBytes().toList() }
        }

    private suspend fun TestScope.assertLegacyPlayback(discovery: MockResponse) {
        val server = server()
        val address = server.url("/").toString()
        legacyDownload(address)
        session.value = SessionState(serverUrl = address, token = "a")
        runCurrent()
        reachable = false
        assertArrayEquals(bodyA, read(address))
        server.enqueue(discovery)
        repository.refreshCurrentCapabilities()
        runCurrent()
        assertArrayEquals(bodyA, read(address))
        assertTrue(DownloadCacheKeys.isScoped(queue(address).customCacheKey!!))
        closeOfflineDownloads()
        openOfflineDownloads()
        runCurrent()
        assertArrayEquals(bodyA, read(address))
        assertEquals(setOf("/audio/chapter.mp3"), downloadCache.keys)
        assertEquals(0, upstreamOpens)
    }

    private fun legacyDownload(address: String, bytes: ByteArray = bodyA) {
        val url = address.trimEnd('/') + "/audio/chapter.mp3"
        val key = DownloadCacheKeys.forUrl(url)
        val request = DownloadRequest.Builder("chapter:7", Uri.parse(url)).setCustomCacheKey(key).build()
        DefaultDownloadIndex(databaseProvider).putDownload(
            Download(request, Download.STATE_COMPLETED, 0, 0, bodyA.size.toLong(), 0, Download.FAILURE_REASON_NONE, DownloadProgress()),
        )
        write(downloadCache, key, bytes)
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
        migrationIndex = object : DownloadIndex {
            override fun getDownload(id: String): Download? = DefaultDownloadIndex(databaseProvider).getDownload(id)
            override fun getDownloads(vararg states: Int): DownloadCursor {
                migrationReads++
                val index = DefaultDownloadIndex(databaseProvider)
                return migrationRead?.invoke(index) ?: throw IOException("migration disabled")
            }
        },
        migrationDispatcher = Dispatchers.Main,
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
        val url = address.trimEnd('/') + "/audio/chapter.mp3"
        offline.download(
            ChapterSummary(id = 7, fictionId = 1, audio = AudioInfo(url = url)),
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
        val url = address.trimEnd('/') + "/audio/chapter.mp3"
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
            override fun getUri(): Uri = Uri.parse(url)
            override fun close() = Unit
        } }).createDataSource()
        return try {
            source.open(DataSpec.Builder().setUri(url).build())
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
