package dk.perspektiva.ttsroad

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dk.perspektiva.ttsroad.data.ChapterNotificationEntry
import dk.perspektiva.ttsroad.data.ServerCapabilities
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.data.visibleNotifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

/**
 * How long between polls.
 *
 * A minute is well inside how long a chapter takes to convert, and the request is one small JSON
 * list. This is what the feature runs on until push exists; when it does, this stays as the
 * catch-up path for a phone that was asleep when the message was sent.
 */
private const val PollIntervalMs: Long = 60_000L

/** What the New chapters screen and the Listening badge read. */
@Stable
class NewChaptersState internal constructor() {
    var notifications: List<ChapterNotificationEntry> by mutableStateOf(emptyList())
        internal set

    /** Everything not dismissed, converting chapters included. What the badge counts. */
    var unread: Int by mutableStateOf(0)
        internal set

    var ready: Int by mutableStateOf(0)
        internal set

    var error: String? by mutableStateOf(null)
        internal set

    var isUnsupported: Boolean by mutableStateOf(false)
        internal set

    var isLoading: Boolean by mutableStateOf(true)
        internal set

    var loadedOnce: Boolean by mutableStateOf(false)
        internal set

    var followsAnything: Boolean? by mutableStateOf(null)
        internal set

    var refreshRequest: Int by mutableStateOf(0)
        internal set

    val rows: List<ChapterNotificationEntry> get() = visibleNotifications(notifications)

    val hasClearable: Boolean get() = ready > 0

    /**
     * Rows with audio, read off the wire's `playable` rather than derived from the state name.
     * What the navigation badge counts (#346): [unread] includes chapters still converting, which
     * would promise more than the list can deliver.
     */
    val playable: Int get() = rows.count { it.playable }
}

/**
 * "New chapters (2 ready · 3 waiting)". Split so the row does not present converting chapters as
 * things to listen to (#346); "waiting" rather than "converting" because a failed conversion is
 * counted here too.
 */
internal fun newChaptersRowTitle(unread: Int, playable: Int): String {
    val ready = playable.coerceIn(0, unread.coerceAtLeast(0))
    val waiting = unread - ready
    val parts = listOfNotNull(
        "$ready ready".takeIf { ready > 0 },
        "$waiting waiting".takeIf { waiting > 0 },
    )
    return if (parts.isEmpty()) "New chapters" else "New chapters (${parts.joinToString(" · ")})"
}

/**
 * Polls for new-chapter notices and posts one when a chapter becomes playable (#175).
 *
 * Hoisted to the activity rather than owned by the screen, and for the reason that decides the
 * whole feature: the badge and the notification are driven by a poll that has to run whether or not
 * anybody has opened the list. A poller owned by the screen would only find out about a new chapter
 * while you were already looking at the place it would be shown.
 *
 * The notification fires **only** on the pulled → ready transition, compared against what this
 * account last saw — persisted by [NewChapterSeenStore] and shared with [NewChapterPollJob], so a
 * chapter that became ready while the app was closed is still announced (#344). The first look for
 * an account with no record stays silent — see [newlyReady] — so a backlog is never announced.
 *
 * Polls only while the activity is STARTED; with the app in the background or closed, the
 * periodic [NewChapterPollJob] takes over.
 *
 * Gated on the session as well as the capability. Discovery is unauthenticated, so a capable server
 * reports `notifications: true` while the login form is still on screen; polling there would fail
 * every minute against a request with no credential to send.
 */
internal class ChapterNotificationLifetime {
    private var session: SessionState? = null
    private var flags: Pair<Boolean, Boolean>? = null

    fun changed(current: SessionState?, capabilities: ServerCapabilities, resolved: Boolean): Boolean {
        if (current == null) return false
        val previous = session
        val accountChanged = previous != null &&
            (previous.serverUrl != current.serverUrl || previous.token != current.token)
        session = current
        if (accountChanged) flags = null
        if (!current.isLoggedIn) return previous == null || accountChanged
        if (!resolved) return accountChanged
        val next = capabilities.notifications to capabilities.follows
        val capabilitiesChanged = flags != null && flags != next
        flags = next
        return accountChanged || capabilitiesChanged
    }
}

@Composable
internal fun rememberNewChapters(
    repository: TtsRoadRepository,
    session: SessionState?,
    capabilities: ServerCapabilities,
    currentSession: suspend () -> SessionState,
    capabilitiesResolved: Boolean,
    lifetime: ChapterNotificationLifetime,
): NewChaptersState {
    val context = LocalContext.current
    val available = canUseChapterNotifications(capabilities, session, capabilitiesResolved)
    val state = remember(repository, session?.serverUrl, session?.token, capabilities.notifications, capabilities.follows, capabilitiesResolved) {
        NewChaptersState()
    }
    val latestSession by rememberUpdatedState(session)
    val latestAvailable by rememberUpdatedState(available)
    val latestCurrentSession by rememberUpdatedState(currentSession)

    suspend fun ownsSession(): Boolean {
        currentCoroutineContext().ensureActive()
        val owner = session ?: return false
        val live = latestCurrentSession()
        return latestAvailable && live.isLoggedIn && live.serverUrl == owner.serverUrl &&
            live.token == owner.token && latestSession?.serverUrl == owner.serverUrl &&
            latestSession?.token == owner.token
    }
    val notifier = remember(context) { NewChapterNotifier(context.applicationContext) }
    // Which notices were already ready last time anything looked — on disk, shared with the
    // background job, so a chapter that became ready while the app was closed is still news on the
    // next cold start (#344). No record for this account reads as null, which keeps that first
    // look silent and the backlog unannounced.
    val seenStore = remember(context) { NewChapterSeenStore(context) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Created up front rather than at the first post, so somebody can turn this off in system
    // settings *before* being interrupted rather than only in response to it.
    LaunchedEffect(session?.serverUrl, session?.token, capabilities.notifications, capabilities.follows, capabilitiesResolved) {
        if (lifetime.changed(session, capabilities, capabilitiesResolved)) notifier.clear()
        if (available) {
            notifier.ensureChannel()
            // The poll that runs with the app closed. Scheduled here, behind the same gate as the
            // in-app poll, so it never runs for a signed-out phone or an incapable server.
            NewChapterPollJob.schedule(context.applicationContext)
        } else if (session != null && !session.isLoggedIn) {
            NewChapterPollJob.cancel(context.applicationContext)
        }
    }

    LaunchedEffect(state, state.refreshRequest) {
        if (!available) {
            // Signing out forgets what was seen as well as what was shown: notices belong to an
            // account, and keeping the set would let the next account's ready chapters arrive
            // silently — or announce the previous account's.
            state.notifications = emptyList()
            state.unread = 0
            state.ready = 0
            state.loadedOnce = false
            state.followsAnything = null
            state.isUnsupported = false
            state.isLoading = false
            state.error = null
            // Only an actual sign-out clears the record. "Unavailable" is also the state of every
            // cold start before capabilities resolve, and clearing then would make the first look
            // silent again — which is the bug the record exists to fix.
            if (session != null && !session.isLoggedIn) seenStore.clear()
            return@LaunchedEffect
        }
        var followsAsked = false
        // Only while the activity is at least STARTED (#344). A stopped activity keeps its
        // composition, and this loop used to poll every minute behind the home screen; the
        // background job is what covers that time now. Returning to the app polls at once.
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (currentCoroutineContext().isActive) {
                if (!ownsSession()) return@repeatOnLifecycle
                state.isLoading = true
                runCatching { repository.chapterNotifications() }
                    .onSuccess { response ->
                        if (!ownsSession()) return@repeatOnLifecycle
                        if (response != null) {
                            state.notifications = response.notifications
                            state.unread = response.unread
                            state.ready = response.ready
                            state.error = null
                            state.isUnsupported = false
                            state.loadedOnce = true
                            // Asked once per explicit load, not once per poll: a failing or slow
                            // library endpoint left this null, and every minute asked again for the
                            // whole library (#322). Unknown stays null, which the empty note reads as
                            // "follows something"; the next refresh tries again.
                            if (state.followsAnything == null && !followsAsked) {
                                followsAsked = true
                                state.followsAnything = runCatching { repository.library().fictions.isNotEmpty() }
                                    .onFailure { if (it is CancellationException) throw it }
                                    .getOrNull()
                            }
                            if (!ownsSession()) return@repeatOnLifecycle
                            val owner = session ?: return@repeatOnLifecycle
                            announceNewlyReady(response.notifications, owner, seenStore, notifier) { ownsSession() }
                        } else {
                            state.isUnsupported = true
                            state.error = null
                            state.loadedOnce = true
                        }
                    }
                    .onFailure {
                        if (it is CancellationException) throw it
                        if (!ownsSession()) return@repeatOnLifecycle
                        state.error = it.message ?: "Could not check for new chapters"
                        state.isUnsupported = false
                    }
                state.isLoading = false
                delay(PollIntervalMs)
            }
        }
    }
    return state
}
