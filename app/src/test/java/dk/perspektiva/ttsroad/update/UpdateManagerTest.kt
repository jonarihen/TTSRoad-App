package dk.perspektiva.ttsroad.update

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateManagerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var server: MockWebServer
    private var installedFile: File? = null
    private val installedFiles = mutableListOf<File>()
    private val installedContents = mutableListOf<List<Byte>>()

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        installedFile = null
        installedFiles.clear()
        installedContents.clear()
        ReflectionHelpers.getStaticField<MutableMap<String, Any>>(FileProvider::class.java, "sCache").clear()
        updateFiles().forEach { it.delete() }
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        updateFiles().forEach { it.delete() }
    }

    private fun updateFiles(): List<File> = context.cacheDir.listFiles().orEmpty().filter {
        it.name == "update.apk" || it.name.startsWith("update-")
    }

    private fun updateManager(
        scope: CoroutineScope,
        client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build(),
        onInstall: (File) -> Unit = {},
    ): UpdateManager =
        UpdateManager(
            client = client,
            scope = scope,
            installer = { _, file ->
                onInstall(file)
                installedFile = file
                installedFiles += file
                installedContents += file.readBytes().toList()
            },
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
        runCurrent()
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
        assertTrue(manager.state.value is UpdateState.Downloading)

        manager.cancelDownload()
        job.join()

        assertEquals(UpdateState.Idle, manager.state.value)
        assertNull("installer must not be invoked on cancellation", installedFile)
        assertTrue("partial file must be cleaned up on cancellation", updateFiles().isEmpty())
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
        runCurrent()
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
        assertTrue(manager.state.value is UpdateState.Downloading)

        manager.dismiss()
        job.join()

        assertEquals(UpdateState.Idle, manager.state.value)
        assertNull(installedFile)
        assertTrue(updateFiles().isEmpty())
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
            assertEquals("apk", installedFile!!.extension)
            assertTrue(installedFile!!.name.startsWith("update-"))
            assertEquals(content.toList(), installedFile!!.readBytes().toList())
            assertEquals(listOf(installedFile), updateFiles())
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
        assertTrue(updateFiles().isEmpty())
    }

    @Test
    fun `empty successful response cannot reach installer`() = runTest {
        val manager = updateManager(this)
        server.enqueue(MockResponse().setBody(""))

        manager.downloadAndInstall(context, release()).join()

        assertTrue(manager.state.value is UpdateState.Failed)
        assertNull(installedFile)
        assertTrue(updateFiles().isEmpty())
    }

    @Test
    fun `early EOF with mismatched content length cannot reach installer`() = runTest {
        val content = "truncated apk".toByteArray()
        val body = DelayedBody(content, content.size, declaredLength = content.size + 10L)
        body.resume.countDown()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body)
                .build()
        }.build()
        val manager = updateManager(this, client)

        manager.downloadAndInstall(context, release()).join()

        assertEquals(UpdateState.Failed("Incomplete download"), manager.state.value)
        assertNull(installedFile)
        assertTrue(updateFiles().isEmpty())
    }

    @Test
    fun `complete chunked response can reach installer`() = runTest {
        val manager = updateManager(this)
        val content = "complete chunked apk"
        server.enqueue(MockResponse().setChunkedBody(content, 4))

        manager.downloadAndInstall(context, release()).join()

        assertEquals(UpdateState.Idle, manager.state.value)
        assertEquals(content, installedFile!!.readText())
        assertEquals("apk", installedFile!!.extension)
        assertEquals(listOf(installedFile), updateFiles())
    }

    @Test
    fun `sequential successful handoffs retain only the latest installer readable APK`() = runTest {
        val manager = updateManager(this)
        repeat(4) { number ->
            val previous = installedFile
            val content = "complete apk $number"
            server.enqueue(MockResponse().setBody(content))

            manager.downloadAndInstall(context, release()).join()

            val current = installedFile!!
            assertEquals(content, readInstallerApk(current))
            assertEquals(listOf(current), updateFiles())
            assertTrue(previous == null || !previous.exists())
            manager.dismiss()
            manager.cancelDownload()
            assertEquals(content, readInstallerApk(current))
            assertEquals(listOf(current), updateFiles())
        }
        assertEquals(4, installedFiles.distinct().size)
    }

    @Test
    fun `failed replacement preserves the current installer handoff until a successful retry`() = runTest {
        var failInstaller = false
        var previous: File? = null
        val manager = updateManager(this, onInstall = { file ->
            assertTrue(file.exists())
            previous?.let { assertEquals("first apk", readInstallerApk(it)) }
            if (failInstaller) throw IOException("Installer unavailable")
        })
        server.enqueue(MockResponse().setBody("first apk"))
        manager.downloadAndInstall(context, release()).join()
        previous = installedFile!!

        server.enqueue(MockResponse().setResponseCode(500))
        manager.downloadAndInstall(context, release()).join()
        assertTrue(manager.state.value is UpdateState.Failed)
        assertEquals(listOf(previous), updateFiles())
        assertEquals("first apk", readInstallerApk(previous))

        failInstaller = true
        server.enqueue(MockResponse().setBody("second apk"))
        manager.downloadAndInstall(context, release()).join()
        assertEquals(UpdateState.Failed("Installer unavailable"), manager.state.value)
        assertEquals(listOf(previous), updateFiles())
        assertEquals("first apk", readInstallerApk(previous))

        failInstaller = false
        server.enqueue(MockResponse().setBody("retry apk"))
        manager.downloadAndInstall(context, release()).join()
        assertEquals(UpdateState.Idle, manager.state.value)
        assertEquals(listOf(installedFile), updateFiles())
        assertEquals("retry apk", readInstallerApk(installedFile!!))
        assertTrue(!previous.exists())
        assertEquals(2, installedFiles.size)
    }

    @Test
    fun `new manager prunes stale process artifacts but retains latest installer APK during cancellation`() = runTest {
        val manager = updateManager(this)
        server.enqueue(MockResponse().setBody("installer apk"))
        manager.downloadAndInstall(context, release()).join()
        val handoff = installedFile!!
        assertTrue(handoff.setLastModified(4_000L))
        val oldApk = cachedArtifact("apk", "superseded apk", 2_000L)
        val partial = cachedArtifact("part", "abandoned partial", 6_000L)
        val emptyApk = cachedArtifact("apk", "", 8_000L)
        val legacy = File(context.cacheDir, "update.apk").apply {
            writeText("legacy apk")
            assertTrue(setLastModified(1_000L))
        }
        val unrelated = File(context.cacheDir, "update-other.apk").apply { writeText("unrelated") }
        val body = DelayedBody("next apk".toByteArray(), 4)
        val restarted = updateManager(this, delayedClient(mapOf("/next.apk" to body)))
        try {
            val job = restarted.downloadAndInstall(context, release("/next.apk"))
            runCurrent()
            body.awaitRead()
            assertEquals("installer apk", readInstallerApk(handoff))
            assertTrue(listOf(oldApk, partial, emptyApk, legacy).none { it.exists() })
            assertEquals("unrelated", unrelated.readText())
            assertEquals(3, updateFiles().size)

            restarted.cancelDownload()
            body.resume.countDown()
            job.join()
            assertEquals(setOf(handoff, unrelated), updateFiles().toSet())
            assertEquals("installer apk", readInstallerApk(handoff))

            server.enqueue(MockResponse().setBody("replacement apk"))
            updateManager(this).downloadAndInstall(context, release()).join()
            assertEquals(setOf(installedFile, unrelated), updateFiles().toSet())
            assertEquals("replacement apk", readInstallerApk(installedFile!!))
            assertTrue(!handoff.exists())
        } finally {
            body.resume.countDown()
            restarted.cancelDownload()
        }
    }

    @Test
    fun `legacy installer APK survives retry failure then is superseded`() = runTest {
        val legacy = File(context.cacheDir, "update.apk").apply { writeText("legacy apk") }
        val manager = updateManager(this)
        server.enqueue(MockResponse().setResponseCode(500))
        manager.downloadAndInstall(context, release()).join()
        assertEquals(listOf(legacy), updateFiles())
        assertEquals("legacy apk", readInstallerApk(legacy))

        server.enqueue(MockResponse().setBody("new apk"))
        manager.downloadAndInstall(context, release()).join()
        assertEquals(listOf(installedFile), updateFiles())
        assertEquals("new apk", readInstallerApk(installedFile!!))
        assertTrue(!legacy.exists())
    }

    @Test
    fun `success cancel and retry cleanup protects all unwinding attempts and installer handoffs`() = runTest {
        val oldBody = DelayedBody("cancelled apk".toByteArray(), 4)
        val retryBody = DelayedBody("retry apk".toByteArray(), 4)
        var handoff: File? = null
        val manager = updateManager(
            this,
            delayedClient(mapOf("/old.apk" to oldBody, "/retry.apk" to retryBody)),
            onInstall = { handoff?.let { file -> assertEquals("first apk", readInstallerApk(file)) } },
        )
        try {
            server.enqueue(MockResponse().setBody("first apk"))
            manager.downloadAndInstall(context, release()).join()
            handoff = installedFile!!

            val oldJob = manager.downloadAndInstall(context, release("/old.apk"))
            runCurrent()
            oldBody.awaitRead()
            val oldFile = updateFiles().single { it != handoff }
            manager.cancelDownload()

            val retryRelease = release("/retry.apk")
            val retryJob = manager.downloadAndInstall(context, retryRelease)
            runCurrent()
            retryBody.awaitRead()
            val retryFile = updateFiles().single { it != handoff && it != oldFile }
            assertEquals("part", oldFile.extension)
            assertEquals("part", retryFile.extension)
            assertEquals(3, updateFiles().size)
            assertEquals("first apk", readInstallerApk(handoff))
            assertTrue(retryJob === manager.downloadAndInstall(context, retryRelease))

            retryBody.resume.countDown()
            retryJob.join()
            val replacement = installedFile!!
            assertEquals("retry apk", readInstallerApk(replacement))
            assertEquals(setOf(oldFile, replacement), updateFiles().toSet())
            assertTrue(!handoff.exists())

            oldBody.resume.countDown()
            oldJob.join()
            manager.cancelDownload()
            manager.dismiss()
            assertEquals(UpdateState.Idle, manager.state.value)
            assertEquals(listOf(replacement), updateFiles())
            assertEquals("retry apk", readInstallerApk(replacement))
            assertEquals(2, installedFiles.size)
        } finally {
            oldBody.resume.countDown()
            retryBody.resume.countDown()
            manager.cancelDownload()
        }
    }

    private fun cachedArtifact(extension: String, content: String, modified: Long): File =
        File(context.cacheDir, "update-${UUID.randomUUID()}.$extension").apply {
            writeText(content)
            assertTrue(setLastModified(modified))
        }

    private fun readInstallerApk(file: File): String {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
    }

    private fun delayedClient(bodies: Map<String, ResponseBody>): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val body = bodies[chain.request().url.encodedPath]
            if (body == null) {
                chain.proceed(chain.request())
            } else {
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body)
                    .build()
            }
        }.build()

    @Test
    fun `cancelled completion leaves retry file state and job intact`() = runTest {
        cancelThenRetry(failOld = false, finishRetryFirst = false)
    }

    @Test
    fun `cancelled failure leaves retry file state and job intact`() = runTest {
        cancelThenRetry(failOld = true, finishRetryFirst = false)
    }

    @Test
    fun `cancelled completion cannot delete the installed retry APK`() = runTest {
        cancelThenRetry(failOld = false, finishRetryFirst = true)
    }

    @Test
    fun `cancelled failure cannot delete the installed retry APK`() = runTest {
        cancelThenRetry(failOld = true, finishRetryFirst = true)
    }

    private suspend fun TestScope.cancelThenRetry(failOld: Boolean, finishRetryFirst: Boolean) {
        val oldContent = "old apk bytes".toByteArray()
        val retryContent = "retry apk bytes are different".toByteArray()
        val oldBody = DelayedBody(oldContent, oldContent.size, failOld)
        val retryBody = DelayedBody(retryContent, retryContent.size / 2)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(if (chain.request().url.encodedPath == "/old.apk") oldBody else retryBody)
                .build()
        }.build()
        val manager = updateManager(this, client)
        try {
            val oldJob = manager.downloadAndInstall(context, release("/old.apk"))
            runCurrent()
            oldBody.awaitRead()
            val oldFile = updateFiles().single()
            assertEquals("part", oldFile.extension)
            assertEquals(oldContent.toList(), oldFile.readBytes().toList())

            manager.cancelDownload()
            val retryRelease = release("/retry.apk")
            val retryJob = manager.downloadAndInstall(context, retryRelease)
            runCurrent()
            retryBody.awaitRead()
            val retryFile = updateFiles().single { it != oldFile }
            assertEquals("part", retryFile.extension)
            val retryPrefix = retryContent.take(retryContent.size / 2)
            assertEquals(retryPrefix, retryFile.readBytes().toList())
            val retryState = manager.state.value
            assertEquals(UpdateState.Downloading(retryContent.size / 2 * 100 / retryContent.size), retryState)
            assertNull(installedFile)

            if (finishRetryFirst) {
                retryBody.resume.countDown()
                withContext(Dispatchers.Default) { withTimeout(10_000) { retryJob.join() } }
                assertEquals(UpdateState.Idle, manager.state.value)
                assertEquals(retryContent.toList(), installedFile!!.readBytes().toList())
            }

            oldBody.resume.countDown()
            withContext(Dispatchers.Default) { withTimeout(10_000) { oldJob.join() } }
            assertTrue(oldJob.isCancelled)
            assertTrue(!oldFile.exists())
            if (finishRetryFirst) {
                assertEquals(UpdateState.Idle, manager.state.value)
                assertEquals(listOf(installedFile), updateFiles())
            } else {
                assertEquals(retryState, manager.state.value)
                assertEquals(retryPrefix, retryFile.readBytes().toList())
                assertEquals(listOf(retryFile), updateFiles())
                assertNull(installedFile)
                assertTrue(retryJob === manager.downloadAndInstall(context, retryRelease))
                retryBody.resume.countDown()
                withContext(Dispatchers.Default) { withTimeout(10_000) { retryJob.join() } }
            }

            assertEquals(UpdateState.Idle, manager.state.value)
            assertEquals(1, installedFiles.size)
            assertEquals(listOf(retryContent.toList()), installedContents)
            assertEquals("apk", installedFile!!.extension)
            assertEquals(retryContent.toList(), installedFile!!.readBytes().toList())
            assertEquals(listOf(installedFile), updateFiles())
        } finally {
            oldBody.resume.countDown()
            retryBody.resume.countDown()
            manager.cancelDownload()
        }
    }

    private class DelayedBody(
        content: ByteArray,
        initialBytes: Int,
        fail: Boolean = false,
        private val declaredLength: Long = content.size.toLong(),
    ) : ResponseBody() {
        private val waiting = CountDownLatch(1)
        val resume = CountDownLatch(1)
        private val bytes = Buffer().write(content)
        private val buffered = object : Source {
            private var reads = 0

            override fun read(sink: Buffer, byteCount: Long): Long {
                when (reads++) {
                    0 -> return bytes.read(sink, minOf(initialBytes.toLong(), byteCount))
                    1 -> {
                        waiting.countDown()
                        if (!resume.await(10, TimeUnit.SECONDS)) throw IOException("Response was not released")
                        if (fail) throw IOException("Delayed read failed")
                    }
                }
                return bytes.read(sink, byteCount)
            }

            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = Unit
        }.buffer()

        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = declaredLength
        override fun source(): BufferedSource = buffered

        suspend fun awaitRead() = withContext(Dispatchers.IO) {
            assertTrue("download did not reach delayed read", waiting.await(10, TimeUnit.SECONDS))
        }
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
