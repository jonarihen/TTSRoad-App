package dk.perspektiva.ttsroad

import android.content.Intent
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface NotificationRoute {
    data object List : NotificationRoute
    data class Play(val fictionId: Int, val chapterId: Int) : NotificationRoute
}

internal fun consumeNotificationRoute(intent: Intent?): NotificationRoute? {
    if (intent?.getBooleanExtra(NewChapterNotifier.ExtraOpenNotifications, false) != true) return null
    val fictionId = intent.getIntExtra(NewChapterNotifier.ExtraFictionId, 0)
    val chapterId = intent.getIntExtra(NewChapterNotifier.ExtraChapterId, 0)
    val play = intent.action == NewChapterNotifier.ActionPlay
    intent.removeExtra(NewChapterNotifier.ExtraOpenNotifications)
    intent.removeExtra(NewChapterNotifier.ExtraFictionId)
    intent.removeExtra(NewChapterNotifier.ExtraChapterId)
    return if (play && fictionId > 0 && chapterId > 0) {
        NotificationRoute.Play(fictionId, chapterId)
    } else {
        NotificationRoute.List
    }
}

internal fun canUseChapterNotifications(capabilities: ServerCapabilities, session: SessionState?): Boolean =
    capabilities.notifications && capabilities.follows && session?.isLoggedIn == true

internal class NotificationRouteOwner : ViewModel() {
    internal class Request(val route: NotificationRoute) {
        var session: SessionState? = null
    }
    private val mutablePending = MutableStateFlow<Request?>(null)
    val pending = mutablePending.asStateFlow()

    fun accept(intent: Intent?) {
        consumeNotificationRoute(intent)?.let { mutablePending.value = Request(it) }
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
) {
    val request by owner.pending.collectAsStateWithLifecycle()
    val latestNavigate by rememberUpdatedState(navigate)
    val latestPlay by rememberUpdatedState(play)
    val latestCurrentSession by rememberUpdatedState(currentSession)
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
        if (!capabilitiesResolved) return@LaunchedEffect
        if (!canUseChapterNotifications(capabilities, signedIn)) {
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
    playQueue: suspend (ChaptersResponse, Int) -> Unit,
): Boolean {
    if (!session.isLoggedIn || !stillAllowed()) return false
    val response = load(route.fictionId)
    currentCoroutineContext().ensureActive()
    if (!stillAllowed() || response.fiction.id != route.fictionId) return false
    val chapters = response.chapters.filter {
        TtsRoadMediaItems.chapter(it, response.fiction, session.serverUrl) != null
    }
    if (chapters.none { it.resolvedChapterId == route.chapterId }) return false
    playQueue(response.copy(chapters = chapters), route.chapterId)
    return true
}
