package dk.perspektiva.ttsroad

import android.app.NotificationManager
import android.app.job.JobScheduler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dk.perspektiva.ttsroad.data.ChapterNotificationChapter
import dk.perspektiva.ttsroad.data.ChapterNotificationEntry
import dk.perspektiva.ttsroad.data.ChapterNotificationFiction
import dk.perspektiva.ttsroad.data.FakeSessionStore
import dk.perspektiva.ttsroad.data.ServerCapabilities
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * New-chapter notices that became ready while the app was closed (#344): a persisted "already
 * seen" set, a background job behind the same gate as the in-app poll, and an in-app poll that
 * stops while the activity is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NewChapterPollingTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
    private val scheduler = context.getSystemService(JobScheduler::class.java)
    private val server = MockWebServer()
    private val polls = AtomicInteger()
    private var readyIds = emptySet<Int>()
    private var capable = true

    @Before
    fun setUp() {
        File(context.filesDir, NewChapterSeenStore.FileName).delete()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.requestUrl?.encodedPath) {
                    "/api/mobile/capabilities" ->
                        json("""{"capabilities":{"notifications":$capable,"follows":true}}""")
                    "/api/mobile/notifications" -> {
                        polls.incrementAndGet()
                        val rows = (1..3).joinToString(",") { id ->
                            val ready = id in readyIds
                            """{"id":$id,"state":"${if (ready) "ready" else "pulled"}","playable":$ready,""" +
                                """"fiction":{"id":7,"title":"Serial"},"chapter":{"id":${100 + id},"title":"Chapter $id"}}"""
                        }
                        json("""{"notifications":[$rows],"unread":3,"ready":${readyIds.size}}""")
                    }
                    "/api/mobile/library" -> json("""{"fictions":[]}""")
                    else -> MockResponse().setResponseCode(404)
                }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val session get() = SessionState(serverUrl = server.url("/").toString(), token = "test-token")

    private fun entry(id: Int, ready: Boolean) = ChapterNotificationEntry(
        id = id,
        state = if (ready) "ready" else "pulled",
        playable = ready,
        fiction = ChapterNotificationFiction(id = 7, title = "Serial"),
        chapter = ChapterNotificationChapter(id = 100 + id, title = "Chapter $id"),
    )

    @Test
    fun `seen store is per account and survives a new instance`() {
        val file = File(context.filesDir, "seen-test.txt").apply { delete() }
        val store = NewChapterSeenStore(file)
        assertNull(store.read("a"))
        store.write("a", setOf(3, 1))
        assertEquals(setOf(1, 3), NewChapterSeenStore(file).read("a"))
        assertNull(NewChapterSeenStore(file).read("b"))
        store.write("a", emptySet())
        assertEquals(emptySet<Int>(), store.read("a"))
        store.clear()
        assertNull(store.read("a"))
    }

    @Test
    fun `a cold start announces what became ready while closed but never the backlog`() = runBlocking {
        val store = NewChapterSeenStore(context)
        val notifier = NewChapterNotifier(context)
        val owner = SessionState(serverUrl = "https://ttsroad.example.com/", token = "t")

        // First look ever for this account: one chapter is already ready. Silent — it is backlog.
        announceNewlyReady(listOf(entry(1, true), entry(2, false)), owner, store, notifier) { true }
        assertEquals(0, manager.allNotifications.size)

        // "Process death": a fresh store over the same file. Chapter 2 became ready meanwhile.
        announceNewlyReady(listOf(entry(1, true), entry(2, true)), owner, NewChapterSeenStore(context), notifier) { true }
        assertEquals(1, manager.allNotifications.size)

        // Seen now; looking again is not news.
        NewChapterNotifier(context).clear()
        announceNewlyReady(listOf(entry(1, true), entry(2, true)), owner, NewChapterSeenStore(context), notifier) { true }
        assertEquals(0, manager.allNotifications.size)

        // Another account has no record, so its first look is silent too.
        val other = owner.copy(token = "other")
        announceNewlyReady(listOf(entry(5, true)), other, store, notifier) { true }
        assertEquals(0, manager.allNotifications.size)

        // Losing ownership mid-check records and announces nothing.
        announceNewlyReady(listOf(entry(5, true), entry(6, true)), other, store, notifier) { false }
        assertEquals(0, manager.allNotifications.size)
        assertEquals(setOf(5), store.read(notificationSessionKey(other)))
    }

    @Test
    fun `background check announces a pulled to ready transition with no UI`() = runBlocking {
        val repository = TtsRoadRepository(FakeSessionStore(session))
        val run = suspend {
            NewChapterPollJob.runCheck(context, repository = repository, currentSession = { session })
        }

        run()
        assertEquals(1, polls.get())
        assertEquals(0, manager.allNotifications.size)

        readyIds = setOf(2)
        run()
        assertEquals(2, polls.get())
        assertEquals(1, manager.allNotifications.size)
    }

    @Test
    fun `background check is gated on session and capability and unschedules itself`() = runBlocking {
        NewChapterPollJob.schedule(context)
        assertNotNull(scheduler.getPendingJob(NewChapterPollJob.JobId))
        // Idempotent: scheduling again leaves the one job in place.
        NewChapterPollJob.schedule(context)
        assertEquals(1, scheduler.allPendingJobs.size)

        val signedOut = SessionState(serverUrl = session.serverUrl)
        NewChapterPollJob.runCheck(
            context,
            repository = TtsRoadRepository(FakeSessionStore(signedOut)),
            currentSession = { signedOut },
            hasStoredToken = { true },
        )
        // A stored token that could not be opened is a Keystore failure, not a sign-out (#448).
        assertEquals(0, polls.get())
        assertNotNull(scheduler.getPendingJob(NewChapterPollJob.JobId))

        NewChapterPollJob.runCheck(
            context,
            repository = TtsRoadRepository(FakeSessionStore(signedOut)),
            currentSession = { signedOut },
            hasStoredToken = { false },
        )
        assertEquals(0, polls.get())
        assertNull(scheduler.getPendingJob(NewChapterPollJob.JobId))

        NewChapterPollJob.schedule(context)
        capable = false
        NewChapterPollJob.runCheck(
            context,
            repository = TtsRoadRepository(FakeSessionStore(session)),
            currentSession = { session },
        )
        assertEquals(0, polls.get())
        assertNull(scheduler.getPendingJob(NewChapterPollJob.JobId))
    }

    @Test
    fun `in-app poll stops while the activity is stopped and resumes on start`() {
        val repository = TtsRoadRepository(FakeSessionStore(session))
        runBlocking { repository.refreshCurrentCapabilities() }
        val lifecycle = TestLifecycle()
        compose.runOnUiThread { lifecycle.registry.currentState = Lifecycle.State.RESUMED }
        lateinit var state: NewChaptersState
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                state = rememberNewChapters(
                    repository, session, ServerCapabilities(notifications = true, follows = true),
                    { session }, true, ChapterNotificationLifetime(),
                )
            }
        }
        compose.waitUntil(5_000) { state.loadedOnce && !state.isLoading }
        assertEquals(1, polls.get())
        assertNotNull(scheduler.getPendingJob(NewChapterPollJob.JobId))

        compose.runOnUiThread { lifecycle.registry.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        repeat(3) {
            compose.mainClock.advanceTimeBy(61_000)
            compose.waitForIdle()
        }
        assertEquals(1, polls.get())

        compose.runOnUiThread { lifecycle.registry.currentState = Lifecycle.State.STARTED }
        compose.waitUntil(5_000) { polls.get() == 2 }
    }

    private class TestLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun json(body: String) =
        MockResponse().setBody(body).setHeader("Content-Type", "application/json")
}
