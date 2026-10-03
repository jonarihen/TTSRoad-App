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
        assertEquals(MainActivity::class.java.name, action.component?.className)
        assertTrue(action.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(action.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(notification.actions.single().actionIntent.isImmutable)
        assertFalse(notification.contentIntent == notification.actions.single().actionIntent)
        assertEquals(NotificationRoute.List, consumeNotificationRoute(body))
        assertEquals(NotificationRoute.Play(7, 102), consumeNotificationRoute(action))
        assertNull(consumeNotificationRoute(action))
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
        assertEquals(NotificationRoute.List, consumeNotificationRoute(intent(true, chapter = 0)))
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
            assertEquals(NotificationRoute.Play(7, 102), owner.pending.value?.route)
            assertNull(consumeNotificationRoute(warm))
        } finally {
            activity.destroy()
            ServiceLocator.restoreDownloadManagerAfterTest()
        }
    }

    @Test
    fun `cold request waits for session and discovery then navigates once and warm tap can repeat`() {
        val owner = NotificationRouteOwner().apply { accept(intent(true)) }
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
        compose.runOnIdle { owner.accept(intent(true)) }
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
            compose.runOnIdle { session = s; capabilities = c; owner.accept(intent(true)) }
            compose.waitForIdle()
            assertNull(owner.pending.value)
        }
        compose.runOnIdle { session = signedIn; capabilities = supported }
        compose.waitForIdle()
        assertEquals(0, calls)
    }

    @Test
    fun `session switch during suspended playback cannot navigate for the previous account`() {
        val owner = NotificationRouteOwner().apply { accept(intent(true)) }
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
