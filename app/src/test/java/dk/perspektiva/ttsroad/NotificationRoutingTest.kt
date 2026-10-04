package dk.perspektiva.ttsroad

import android.app.Application
import android.app.NotificationManager
import dk.perspektiva.ttsroad.core.ServiceLocator
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dk.perspektiva.ttsroad.data.ChapterNotificationChapter
import dk.perspektiva.ttsroad.data.ChapterNotificationEntry
import dk.perspektiva.ttsroad.data.ChapterNotificationFiction
import dk.perspektiva.ttsroad.data.ServerCapabilities
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.nav.AppScreen
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationRoutingTest {
    @get:Rule val compose = createComposeRule()
    private val context = RuntimeEnvironment.getApplication()
    private val signedIn = SessionState(serverUrl = "https://ttsroad.example.com/", token = "test-token")
    private val supported = ServerCapabilities(notifications = true, follows = true)

    private fun intent(play: Boolean = false, fiction: Int = 7, chapter: Int = 102) =
        Intent(context, MainActivity::class.java).apply {
            putExtra(NewChapterNotifier.ExtraOpenNotifications, true)
            if (play) {
                action = NewChapterNotifier.ActionPlay
                putExtra(NewChapterNotifier.ExtraFictionId, fiction)
                putExtra(NewChapterNotifier.ExtraChapterId, chapter)
            }
        }

    @Test
    fun `notification body opens list and explicit Play has separate immutable pending intent`() {
        val notifier = NewChapterNotifier(context)
        notifier.notifyReady("Serial", "Ready", ChapterNotificationEntry(
            playable = true,
            fiction = ChapterNotificationFiction(id = 7),
            chapter = ChapterNotificationChapter(id = 102),
        ))
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
        val notification = manager.getNotification(NewChapterNotifier.Tag, NewChapterNotifier.NotificationId)
        assertEquals(NewChapterNotifier.ChannelId, notification.channelId)
        assertEquals(1, notification.actions.size)
        assertEquals("Play", notification.actions.single().title.toString())
        val body = shadowOf(notification.contentIntent).savedIntent
        val action = shadowOf(notification.actions.single().actionIntent).savedIntent
        assertEquals(NotificationPlayActivity::class.java.name, action.component?.className)
        assertTrue(body.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(body.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(notification.actions.single().actionIntent.isImmutable)
        assertFalse(notification.contentIntent == notification.actions.single().actionIntent)
        assertEquals(NotificationRoute.List, consumeNotificationRoute(body))
        assertEquals(NotificationRoute.Play(7, 102), consumeNotificationRoute(action, allowPlay = true))
        assertNull(consumeNotificationRoute(action, allowPlay = true))
        assertFalse(action.hasExtra(NewChapterNotifier.ExtraChapterId))
    }

    @Test
    fun `batch and nonplayable notices have no Play and replace previous single destination`() {
        val notifier = NewChapterNotifier(context)
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
        for (entry in listOf(null, ChapterNotificationEntry(playable = false), ChapterNotificationEntry(playable = true))) {
            notifier.notifyReady("Chapters", "Ready", entry)
            val notification = manager.getNotification(NewChapterNotifier.Tag, NewChapterNotifier.NotificationId)
            assertTrue(notification.actions.isNullOrEmpty())
            assertEquals(NotificationRoute.List, consumeNotificationRoute(shadowOf(notification.contentIntent).savedIntent))
        }
    }

    @Test
    fun `malformed play is list only and legacy single body never starts playback`() {
        assertEquals(NotificationRoute.List, consumeNotificationRoute(intent(true, chapter = 0), allowPlay = true))
        assertEquals(NotificationRoute.List, consumeNotificationRoute(intent().putExtra(NewChapterNotifier.ExtraChapterId, 102)))
        assertNull(consumeNotificationRoute(Intent()))
        assertNull(consumeNotificationRoute(null))
    }

    @Test
    @Config(application = Application::class)
    fun `MainActivity consumes cold and warm intents without needing a collector`() {
        ServiceLocator.disableDownloadManagerForTest()
        val launch = intent()
        val activity = Robolectric.buildActivity(MainActivity::class.java, launch).create()
        try {
            val owner = activity.get().notificationRoutes
            assertEquals(NotificationRoute.List, owner.pending.value?.route)
            assertNull(consumeNotificationRoute(launch))
            owner.consume(requireNotNull(owner.pending.value))
            val warm = intent(true)
            activity.newIntent(warm)
            assertNull(owner.pending.value)
            assertNull(consumeNotificationRoute(warm))
        } finally {
            activity.destroy()
            ServiceLocator.restoreDownloadManagerAfterTest()
        }
    }

    @Test
    @Config(application = Application::class)
    fun `private Play PendingIntent finishes trampoline reuses MainActivity and clears tapped notice`() {
        val info = context.packageManager.getActivityInfo(android.content.ComponentName(context, NotificationPlayActivity::class.java), 0)
        assertFalse(info.exported)
        assertFalse(MainActivity::class.java.isAssignableFrom(NotificationPlayActivity::class.java))
        ServiceLocator.disableDownloadManagerForTest()
        val store = ServiceLocator.tokenStore(context)
        kotlinx.coroutines.runBlocking { store.saveLogin(signedIn.serverUrl,
            dk.perspektiva.ttsroad.data.LoginResponse(token = signedIn.token!!,
                user = dk.perspektiva.ttsroad.data.MobileUser(id = 1, username = "listener"))) }
        val main = Robolectric.buildActivity(MainActivity::class.java).create()
        val notifier = NewChapterNotifier(context)
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
        try {
            repeat(2) { attempt ->
                notifier.notifyReady("Ready", "Chapter", ChapterNotificationEntry(
                    playable = true, fiction = ChapterNotificationFiction(id = 7), chapter = ChapterNotificationChapter(id = 102 + attempt),
                ), signedIn)
                manager.getNotification(NewChapterNotifier.Tag, NewChapterNotifier.NotificationId).actions.single().actionIntent.send()
                val delivered = shadowOf(context).nextStartedActivity
                val trampoline = Robolectric.buildActivity(NotificationPlayActivity::class.java, delivered).create()
                try {
                    repeat(50) { shadowOf(android.os.Looper.getMainLooper()).idle() }
                    assertTrue(trampoline.get().isFinishing)
                    assertEquals(0, manager.allNotifications.size)
                    val forwarded = shadowOf(trampoline.get()).nextStartedActivity
                    assertEquals(MainActivity::class.java.name, forwarded.component?.className)
                    assertTrue(forwarded.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
                    assertTrue(forwarded.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
                    val retained = main.get().notificationRoutes
                    main.newIntent(forwarded)
                    org.junit.Assert.assertSame(retained, main.get().notificationRoutes)
                    assertEquals(NotificationRoute.Play(7, 102 + attempt), retained.pending.value?.route)
                    assertEquals(signedIn.token, retained.pending.value?.session?.token)
                    retained.consume(requireNotNull(retained.pending.value))
                    main.newIntent(forwarded)
                    assertNull(retained.pending.value)
                } finally {
                    trampoline.destroy()
                }
            }
        } finally {
            main.destroy()
            kotlinx.coroutines.runBlocking { store.clearToken() }
            ServiceLocator.restoreDownloadManagerAfterTest()
        }
    }

    @Test
    @Config(application = Application::class)
    fun `surviving action from old login clears notice without forwarding chapter IDs to next account`() {
        val store = ServiceLocator.tokenStore(context)
        kotlinx.coroutines.runBlocking { store.saveLogin("https://other.example.com/",
            dk.perspektiva.ttsroad.data.LoginResponse(token = "next-token",
                user = dk.perspektiva.ttsroad.data.MobileUser(id = 2, username = "next"))) }
        val notifier = NewChapterNotifier(context)
        notifier.notifyReady("Old account", "Ready", ChapterNotificationEntry(
            playable = true, fiction = ChapterNotificationFiction(id = 7), chapter = ChapterNotificationChapter(id = 102),
        ), signedIn)
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
        manager.getNotification(NewChapterNotifier.Tag, NewChapterNotifier.NotificationId).actions.single().actionIntent.send()
        val delivered = shadowOf(context).nextStartedActivity
        val trampoline = Robolectric.buildActivity(NotificationPlayActivity::class.java, delivered).create()
        try {
            repeat(50) { shadowOf(android.os.Looper.getMainLooper()).idle() }
            assertTrue(trampoline.get().isFinishing)
            assertNull(shadowOf(trampoline.get()).nextStartedActivity)
            assertEquals(0, manager.allNotifications.size)
        } finally {
            trampoline.destroy()
            kotlinx.coroutines.runBlocking { store.clearToken() }
        }
    }

    @Test
    fun `trusted pending route never uses old chapter IDs after login switches`() {
        val owner = NotificationRouteOwner()
        owner.accept(TrustedNotificationActions.issue(context, NotificationRoute.Play(7, 102), signedIn))
        val next = signedIn.copy(token = "next-token", serverUrl = "https://other.example.com/")
        compose.setContent {
            NotificationRouting(owner, next, supported, true, { next },
                play = { _, _ -> error("Old route must not play") }, navigate = { error("Old route must not navigate") })
        }
        compose.waitForIdle()
        assertNull(owner.pending.value)
    }

    @Test
    fun `forged privileged extras are rejected even with a forged private component name`() {
        assertNull(consumeNotificationRoute(intent(true)))
        assertNull(consumeNotificationRoute(intent(true).setClass(context, NotificationPlayActivity::class.java)))
    }

    @Test
    fun `transient discovery failure retries and routes when later discovery succeeds`() {
        val owner = NotificationRouteOwner().apply { accept(intent(true), allowPlay = true) }
        var resolved by mutableStateOf(false)
        var attempts = 0
        val destinations = mutableListOf<AppScreen>()
        compose.setContent {
            NotificationRouting(owner, signedIn, supported, resolved, { signedIn },
                play = { _, _ -> true }, navigate = { destinations += it },
                retryDiscovery = { attempts++; if (attempts == 2) resolved = true; resolved })
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(5_100)
        compose.waitForIdle()
        assertEquals(1, attempts)
        assertTrue(destinations.isEmpty())
        compose.mainClock.advanceTimeBy(5_100)
        compose.waitForIdle()
        assertEquals(2, attempts)
        assertEquals(listOf(AppScreen.Player), destinations)
        assertNull(owner.pending.value)
    }

    @Test
    fun `persistent discovery failure ends at usable Listening fallback rather than stuck pending`() {
        val owner = NotificationRouteOwner().apply { accept(intent()) }
        var attempts = 0
        val destinations = mutableListOf<AppScreen>()
        compose.setContent {
            NotificationRouting(owner, signedIn, supported, false, { signedIn },
                play = { _, _ -> error("Unresolved must not play") }, navigate = { destinations += it },
                retryDiscovery = { attempts++; throw java.io.IOException("offline") })
        }
        compose.waitForIdle()
        repeat(3) { compose.mainClock.advanceTimeBy(5_100); compose.waitForIdle() }
        assertEquals(3, attempts)
        assertEquals(listOf(AppScreen.Listening), destinations)
        assertNull(owner.pending.value)
    }

    @Test
    fun `failed queue startup opens New chapters not empty Player`() {
        val owner = NotificationRouteOwner().apply { accept(intent(true), allowPlay = true) }
        val destinations = mutableListOf<AppScreen>()
        compose.setContent {
            NotificationRouting(owner, signedIn, supported, true, { signedIn },
                play = { _, _ -> false }, navigate = { destinations += it })
        }
        compose.waitForIdle()
        assertEquals(listOf(AppScreen.NewChapters), destinations)
        assertNull(owner.pending.value)
    }

    @Test
    fun `cold request waits for session and discovery then navigates once and warm tap can repeat`() {
        val owner = NotificationRouteOwner().apply { accept(intent(true), allowPlay = true) }
        var session by mutableStateOf<SessionState?>(null)
        var resolved by mutableStateOf(false)
        val played = mutableListOf<NotificationRoute.Play>()
        val destinations = mutableListOf<AppScreen>()
        compose.setContent {
            NotificationRouting(owner, session, supported, resolved, { session ?: SessionState() },
                play = { route, _ -> played += route; true }, navigate = { destinations += it })
        }
        compose.runOnIdle { assertTrue(played.isEmpty()); session = signedIn }
        compose.runOnIdle { assertTrue(played.isEmpty()); resolved = true }
        compose.waitForIdle()
        assertEquals(listOf(NotificationRoute.Play(7, 102)), played)
        assertEquals(listOf(AppScreen.Player), destinations)
        assertNull(owner.pending.value)
        compose.runOnIdle { owner.accept(intent()) }
        compose.waitForIdle()
        assertEquals(AppScreen.NewChapters, destinations.last())
        compose.runOnIdle { owner.accept(intent(true), allowPlay = true) }
        compose.waitForIdle()
        assertEquals(2, played.size)
    }

    @Test
    fun `invalid session or either missing capability drops request and never replays at next login`() {
        val owner = NotificationRouteOwner()
        var session by mutableStateOf<SessionState?>(signedIn)
        var capabilities by mutableStateOf(supported)
        var calls = 0
        compose.setContent {
            NotificationRouting(owner, session, capabilities, true, { session ?: SessionState() },
                play = { _, _ -> calls++; true }, navigate = { calls++ })
        }
        for ((s, c) in listOf(
            SessionState() to supported,
            signedIn.copy(token = " ") to supported,
            signedIn to supported.copy(follows = false),
            signedIn to supported.copy(notifications = false),
        )) {
            compose.runOnIdle { session = s; capabilities = c; owner.accept(intent(true), allowPlay = true) }
            compose.waitForIdle()
            assertNull(owner.pending.value)
        }
        compose.runOnIdle { session = signedIn; capabilities = supported }
        compose.waitForIdle()
        assertEquals(0, calls)
    }

    @Test
    fun `session switch during suspended playback cannot navigate for the previous account`() {
        val owner = NotificationRouteOwner().apply { accept(intent(true), allowPlay = true) }
        var session by mutableStateOf<SessionState?>(signedIn)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Boolean>()
        val destinations = mutableListOf<AppScreen>()
        compose.setContent {
            NotificationRouting(owner, session, supported, true, { session ?: SessionState() },
                play = { _, _ -> started.complete(Unit); finish.await() }, navigate = { destinations += it })
        }
        compose.waitForIdle()
        assertTrue(started.isCompleted)
        compose.runOnIdle { session = SessionState() }
        compose.waitForIdle()
        compose.runOnIdle { finish.complete(true); session = signedIn.copy(token = "next-token") }
        compose.waitForIdle()
        assertTrue(destinations.isEmpty())
        assertNull(owner.pending.value)
    }
}
