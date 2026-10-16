package dk.perspektiva.ttsroad.download

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import androidx.media3.exoplayer.offline.DownloadIndex
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import dk.perspektiva.ttsroad.data.AudioInfo
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.ServerCapabilities
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.TokenStore
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Sign-out and a 401 delete every pinned read-along document but keep the audio (#447). Whatever is
 * downloaded must be pinned again once a session can fetch it — and so must a chapter queued before
 * discovery said the server could serve read-along at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineReaderRepinTest {
    private lateinit var context: CapturingContext
    private lateinit var downloads: OfflineDownloads
    private lateinit var server: MockWebServer
    private val session = MutableStateFlow(SessionState())
    private val capabilities = MutableStateFlow(ServerCapabilities.Baseline)
    private val pins = mutableListOf<Int>()

    @Before
    fun setUp() {
        context = CapturingContext(RuntimeEnvironment.getApplication())
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    MockResponse().setBody(Buffer().write(ByteArray(2048) { it.toByte() }))
            }
            start()
        }
        session.value = SessionState(serverUrl = address, token = "token")
        downloads = OfflineDownloads(
            context,
            TokenStore(context),
            capabilities = capabilities,
            downloadPrefs = emptyFlow(),
            session = session,
            pinReadAlong = { chapterId -> synchronized(pins) { pins += chapterId }; true },
            initializeManager = false,
            migrationIndex = object : DownloadIndex {
                override fun getDownload(id: String) = throw IOException("no migration here")
                override fun getDownloads(vararg states: Int) = throw IOException("no migration here")
            },
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
    fun `downloads are pinned once read-along resolves and again after signing back in`() {
        // Queued while discovery has not answered: nothing to pin against yet.
        downloads.download(chapter(), address)
        submitRequests()
        await {
            downloads.downloads.value["chapter:42"]?.state == ChapterDownloadState.Downloaded &&
                downloads.downloadManager.isIdle
        }
        assertTrue(pinned().isEmpty())

        capabilities.value = ServerCapabilities(readAlong = true, discoveryBaseUrl = address)
        await { pinned() == listOf(42) }

        // Another download changing state must not re-request a pin already asked for.
        downloads.download(chapter(43), address)
        submitRequests()
        await { downloads.downloads.value["chapter:43"]?.state == ChapterDownloadState.Downloaded }
        assertEquals(listOf(42, 43), pinned())

        // A 401 or sign-out clears the pinned documents; the audio stays.
        session.value = session.value.copy(token = null)
        idle()
        session.value = session.value.copy(token = "fresh-token")
        await { pinned().size == 4 }
        assertEquals(setOf(42, 43), pinned().drop(2).toSet())
    }

    private val address get() = server.url("/").toString()

    private fun pinned(): List<Int> = synchronized(pins) { pins.toList() }

    private fun chapter(id: Int = 42) =
        ChapterSummary(id = id, fictionId = 9, audio = AudioInfo(url = server.url("/audio/$id.mp3").toString()))

    private fun submitRequests() {
        val intents = context.intents.toList()
        context.intents.clear()
        intents.filter { it.action == DownloadService.ACTION_ADD_DOWNLOAD }.forEach { intent ->
            downloads.downloadManager.addDownload(
                checkNotNull(intent.getParcelableExtra(DownloadService.KEY_DOWNLOAD_REQUEST, DownloadRequest::class.java)),
            )
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition() && System.nanoTime() < deadline) {
            idle()
            Thread.sleep(10)
        }
        idle()
        assertTrue("pins: ${pinned()}, downloads: ${downloads.downloads.value}", condition())
    }

    private class CapturingContext(base: Context) : ContextWrapper(base) {
        val intents = mutableListOf<Intent>()
        override fun startService(service: Intent): ComponentName? {
            intents += service
            return service.component
        }
    }
}
