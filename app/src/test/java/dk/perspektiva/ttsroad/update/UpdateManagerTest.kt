package dk.perspektiva.ttsroad.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateManagerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var server: MockWebServer
    private var installedFile: File? = null

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        installedFile = null
        File(context.cacheDir, "update.apk").delete()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        File(context.cacheDir, "update.apk").delete()
    }

    private fun updateManager(scope: CoroutineScope): UpdateManager =
        UpdateManager(
            client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build(),
            scope = scope,
            installer = { _, file -> installedFile = file },
            apiBaseUrl = server.url("/repos/test").toString().removeSuffix("/"),
        )

    private fun release(apkPath: String = "/update.apk") = ReleaseInfo(
        versionName = "1.0.0",
        notes = "Bugfixes",
        apkUrl = server.url(apkPath).toString(),
    )

    @Test
    fun `download cancellation aborts in-flight network call and deletes partial file`() = runTest {
        val manager = updateManager(this)
        val body = Buffer().write(ByteArray(512 * 1024) { 1 })
        server.enqueue(
            MockResponse()
                .setBody(body)
                .throttleBody(1024, 100, TimeUnit.MILLISECONDS),
        )

        val job = manager.downloadAndInstall(context, release())
        // Wait until download enters in-flight state
        while (manager.state.value !is UpdateState.Downloading) {
            delay(10)
        }

        manager.cancelDownload()
        job.join()

        assertEquals(UpdateState.Idle, manager.state.value)
        assertNull("installer must not be invoked on cancellation", installedFile)
        assertFalse("partial file must be cleaned up on cancellation", File(context.cacheDir, "update.apk").exists())
    }

    @Test
    fun `dismiss while downloading cancels download and resets to Idle`() = runTest {
        val manager = updateManager(this)
        val body = Buffer().write(ByteArray(512 * 1024) { 1 })
        server.enqueue(
            MockResponse()
                .setBody(body)
                .throttleBody(1024, 100, TimeUnit.MILLISECONDS),
        )

        val job = manager.downloadAndInstall(context, release())
        while (manager.state.value !is UpdateState.Downloading) {
            delay(10)
        }

        manager.dismiss()
        job.join()

        assertEquals(UpdateState.Idle, manager.state.value)
        assertNull(installedFile)
        assertFalse(File(context.cacheDir, "update.apk").exists())
    }

    @Test
    fun `download survives transient caller scope cancellation and invokes installer`() = runTest {
        val backgroundScope = CoroutineScope(Dispatchers.Default)
        try {
            val manager = updateManager(backgroundScope)
            val content = "valid fake apk bytes".toByteArray()
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Length", content.size)
                    .setBody(Buffer().write(content)),
            )

            val callerScope = CoroutineScope(Dispatchers.Default)
            // Composition launches or triggers the download
            val downloadJob = manager.downloadAndInstall(context, release())

            // Immediately cancel the caller's transient composition scope
            callerScope.cancel()

            downloadJob.join()

            assertEquals(UpdateState.Idle, manager.state.value)
            assertNotNull("installer must be called with APK despite caller scope cancellation", installedFile)
            assertEquals(File(context.cacheDir, "update.apk"), installedFile)
            assertTrue(installedFile!!.exists())
        } finally {
            backgroundScope.cancel()
        }
    }

    @Test
    fun `failed download transitions to Failed state and cleans up file`() = runTest {
        val manager = updateManager(this)
        server.enqueue(MockResponse().setResponseCode(500))

        val job = manager.downloadAndInstall(context, release())
        job.join()

        assertTrue(manager.state.value is UpdateState.Failed)
        assertNull(installedFile)
        assertFalse(File(context.cacheDir, "update.apk").exists())
    }

    @Test
    fun `checking for updates parses remote release and notes`() = runTest {
        val manager = updateManager(this)
        val releaseJson = """
            {
                "tag_name": "v1.2.0",
                "body": "Exciting new features",
                "assets": [
                    {
                        "name": "app-release.apk",
                        "browser_download_url": "${server.url("/download/app.apk")}"
                    }
                ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(releaseJson))

        manager.check(currentVersionName = "1.0.0", manual = true)

        val state = manager.state.value
        assertTrue(state is UpdateState.Available)
        val available = state as UpdateState.Available
        assertEquals("1.2.0", available.release.versionName)
        assertEquals("Exciting new features", available.release.notes)
        assertEquals(server.url("/download/app.apk").toString(), available.release.apkUrl)
    }

    @Test
    fun `checking when up to date transitions to UpToDate`() = runTest {
        val manager = updateManager(this)
        val releaseJson = """
            {
                "tag_name": "v1.0.0",
                "body": "No changes",
                "assets": [
                    {
                        "name": "app-release.apk",
                        "browser_download_url": "${server.url("/download/app.apk")}"
                    }
                ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(releaseJson))

        manager.check(currentVersionName = "1.0.0", manual = true)

        assertEquals(UpdateState.UpToDate, manager.state.value)
    }

    @Test
    fun `manual check cancelled mid-fetch propagates cancellation and does not set Failed`() = runTest {
        val manager = updateManager(this)
        server.enqueue(
            MockResponse()
                .setBody("""{"tag_name": "v9.9.9", "assets": []}""")
                .setBodyDelay(500, TimeUnit.MILLISECONDS),
        )

        var thrown: Throwable? = null
        val checkJob = launch {
            try {
                manager.check(currentVersionName = "1.0.0", manual = true)
            } catch (e: Throwable) {
                thrown = e
            }
        }

        while (server.requestCount == 0) {
            delay(10)
        }
        assertEquals(UpdateState.Checking, manager.state.value)

        checkJob.cancel()
        checkJob.join()

        assertTrue(thrown is CancellationException)
        assertEquals(UpdateState.Idle, manager.state.value)
    }
}
