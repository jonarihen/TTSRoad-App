package dk.perspektiva.ttsroad

import android.content.Intent
import android.content.Context
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import dk.perspektiva.ttsroad.core.ServiceLocator
import java.util.UUID
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dk.perspektiva.ttsroad.data.ChaptersResponse
import dk.perspektiva.ttsroad.data.ServerCapabilities
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.media.TtsRoadMediaItems
import dk.perspektiva.ttsroad.nav.AppScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface NotificationRoute {
    data object List : NotificationRoute
    data class Play(val fictionId: Int, val chapterId: Int) : NotificationRoute
}

class NotificationPlayActivity : androidx.activity.ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessionKey = intent.getStringExtra(NewChapterNotifier.ExtraSessionKey)
        val route = consumeNotificationRoute(intent, allowPlay = true)
        if (route !is NotificationRoute.Play) {
            finish()
            return
        }
        NewChapterNotifier(this).clear()
        lifecycleScope.launch {
            try {
                val session = ServiceLocator.tokenStore(this@NotificationPlayActivity).current()
                if (session.isLoggedIn && sessionKey == notificationSessionKey(session)) {
                    startActivity(TrustedNotificationActions.issue(this@NotificationPlayActivity, route, session))
                }
            } finally {
                finish()
            }
        }
    }
}

internal object TrustedNotificationActions {
    private const val TicketExtra = "dk.perspektiva.ttsroad.NOTIFICATION_TICKET"
    private var ticket: Pair<String, NotificationRouteOwner.Request>? = null

    @Synchronized
    fun issue(context: Context, route: NotificationRoute.Play, session: SessionState): Intent {
        val key = UUID.randomUUID().toString()
        ticket = key to NotificationRouteOwner.Request(route).apply { this.session = session }
        return Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(TicketExtra, key)
    }

    @Synchronized
    fun consume(intent: Intent?): NotificationRouteOwner.Request? {
        val key = intent?.getStringExtra(TicketExtra) ?: return null
        intent.removeExtra(TicketExtra)
        val held = ticket ?: return null
        if (held.first != key) return null
        ticket = null
        return held.second
    }
}

internal fun consumeNotificationRoute(intent: Intent?, allowPlay: Boolean = false): NotificationRoute? {
    if (intent?.getBooleanExtra(NewChapterNotifier.ExtraOpenNotifications, false) != true) return null
    val fictionId = intent.getIntExtra(NewChapterNotifier.ExtraFictionId, 0)
    val chapterId = intent.getIntExtra(NewChapterNotifier.ExtraChapterId, 0)
    val play = intent.action == NewChapterNotifier.ActionPlay
    intent.removeExtra(NewChapterNotifier.ExtraOpenNotifications)
    intent.removeExtra(NewChapterNotifier.ExtraFictionId)
    intent.removeExtra(NewChapterNotifier.ExtraChapterId)
    if (play && !allowPlay) return null
    return if (play && fictionId > 0 && chapterId > 0) {
        NotificationRoute.Play(fictionId, chapterId)
    } else {
        NotificationRoute.List
    }
}

internal fun canUseChapterNotifications(
    capabilities: ServerCapabilities,
    session: SessionState?,
    capabilitiesResolved: Boolean,
): Boolean = capabilitiesResolved && capabilities.notifications && capabilities.follows && session?.isLoggedIn == true

internal class NotificationRouteOwner : ViewModel() {
    val lifetime = ChapterNotificationLifetime()
    internal class Request(val route: NotificationRoute) {
        var session: SessionState? = null
    }
    private val mutablePending = MutableStateFlow<Request?>(null)
    val pending = mutablePending.asStateFlow()

    fun accept(intent: Intent?, allowPlay: Boolean = false) {
        val trusted = TrustedNotificationActions.consume(intent)
        if (trusted != null) {
            mutablePending.value = trusted
        } else {
            consumeNotificationRoute(intent, allowPlay)?.let { mutablePending.value = Request(it) }
        }
    }

    fun consume(request: Request) {
        mutablePending.compareAndSet(request, null)
    }
}

@Composable
internal fun NotificationRouting(
    owner: NotificationRouteOwner,
    session: SessionState?,
    capabilities: ServerCapabilities,
    capabilitiesResolved: Boolean,
    currentSession: suspend () -> SessionState,
    play: suspend (NotificationRoute.Play, SessionState) -> Boolean,
    navigate: (AppScreen) -> Unit,
    retryDiscovery: suspend () -> Boolean = { false },
) {
    val request by owner.pending.collectAsStateWithLifecycle()
    val latestNavigate by rememberUpdatedState(navigate)
    val latestPlay by rememberUpdatedState(play)
    val latestCurrentSession by rememberUpdatedState(currentSession)
    val latestRetryDiscovery by rememberUpdatedState(retryDiscovery)
    LaunchedEffect(request, session?.serverUrl, session?.token, capabilities.notifications, capabilities.follows, capabilitiesResolved) {
        val pending = request ?: return@LaunchedEffect
        val signedIn = session ?: return@LaunchedEffect
        if (!signedIn.isLoggedIn) {
            owner.consume(pending)
            return@LaunchedEffect
        }
        val bound = pending.session
        if (bound != null && (bound.serverUrl != signedIn.serverUrl || bound.token != signedIn.token)) {
            owner.consume(pending)
            return@LaunchedEffect
        }
        pending.session = signedIn
        if (!capabilitiesResolved) {
            repeat(3) {
                delay(5_000)
                val live = latestCurrentSession()
                if (live.serverUrl != signedIn.serverUrl || live.token != signedIn.token || !live.isLoggedIn) {
                    owner.consume(pending)
                    return@LaunchedEffect
                }
                try {
                    if (latestRetryDiscovery()) return@LaunchedEffect
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Unit
                }
            }
            currentCoroutineContext().ensureActive()
            val finished = latestCurrentSession()
            if (finished.isLoggedIn && finished.serverUrl == signedIn.serverUrl && finished.token == signedIn.token) {
                latestNavigate(AppScreen.Listening)
            }
            owner.consume(pending)
            return@LaunchedEffect
        }
        if (!canUseChapterNotifications(capabilities, signedIn, capabilitiesResolved)) {
            owner.consume(pending)
            return@LaunchedEffect
        }
        val live = latestCurrentSession()
        if (live.serverUrl != signedIn.serverUrl || live.token != signedIn.token || !live.isLoggedIn) {
            owner.consume(pending)
            return@LaunchedEffect
        }
        val destination = when (val route = pending.route) {
            NotificationRoute.List -> AppScreen.NewChapters
            is NotificationRoute.Play -> {
                val played = try {
                    latestPlay(route, signedIn)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                if (played) AppScreen.Player else AppScreen.NewChapters
            }
        }
        currentCoroutineContext().ensureActive()
        val finished = latestCurrentSession()
        if (finished.isLoggedIn && finished.serverUrl == signedIn.serverUrl && finished.token == signedIn.token) {
            latestNavigate(destination)
        }
        owner.consume(pending)
    }
}

internal suspend fun playNotificationChapter(
    route: NotificationRoute.Play,
    session: SessionState,
    load: suspend (Int) -> ChaptersResponse,
    stillAllowed: suspend () -> Boolean,
    playQueue: suspend (ChaptersResponse, Int) -> Boolean,
): Boolean {
    if (!session.isLoggedIn || !stillAllowed()) return false
    val response = load(route.fictionId)
    currentCoroutineContext().ensureActive()
    if (!stillAllowed() || response.fiction.id != route.fictionId) return false
    val chapters = response.chapters.filter {
        TtsRoadMediaItems.chapter(it, response.fiction, session.serverUrl) != null
    }
    if (chapters.none { it.resolvedChapterId == route.chapterId }) return false
    val installed = playQueue(response.copy(chapters = chapters), route.chapterId)
    currentCoroutineContext().ensureActive()
    return installed && stillAllowed()
}
