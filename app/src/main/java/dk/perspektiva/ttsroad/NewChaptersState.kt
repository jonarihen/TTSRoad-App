package dk.perspektiva.ttsroad

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dk.perspektiva.ttsroad.data.ChapterNotificationEntry
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.data.newlyReady
import dk.perspektiva.ttsroad.data.readyNotificationText
import dk.perspektiva.ttsroad.data.visibleNotifications
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext

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

    var loadedOnce: Boolean by mutableStateOf(false)
        internal set

    val rows: List<ChapterNotificationEntry> get() = visibleNotifications(notifications)

    val hasClearable: Boolean get() = ready > 0
}

/**
 * Polls for new-chapter notices and posts one when a chapter becomes playable (#175).
 *
 * Hoisted to the activity rather than owned by the screen, and for the reason that decides the
 * whole feature: the badge and the notification are driven by a poll that has to run whether or not
 * anybody has opened the list. A poller owned by the screen would only find out about a new chapter
 * while you were already looking at the place it would be shown.
 *
 * The notification fires **only** on the pulled → ready transition, and never on the first look of
 * a session — see [newlyReady]. A chapter that was already ready when the app started is not news,
 * and announcing it would re-announce the backlog on every cold start.
 *
 * Gated on the session as well as the capability. Discovery is unauthenticated, so a capable server
 * reports `notifications: true` while the login form is still on screen; polling there would fail
 * every minute against a request with no credential to send.
 */
@Composable
internal fun rememberNewChapters(
    repository: TtsRoadRepository,
    isLoggedIn: Boolean,
    available: Boolean,
): NewChaptersState {
    val context = LocalContext.current
    val state = remember { NewChaptersState() }
    val notifier = remember(context) { NewChapterNotifier(context.applicationContext) }

    // Which notices were already ready last time we looked. Null until the first successful load,
    // which is what makes that load silent.
    var readySeen by remember { mutableStateOf<Set<Int>?>(null) }

    // Created up front rather than at the first post, so somebody can turn this off in system
    // settings *before* being interrupted rather than only in response to it.
    LaunchedEffect(available) { if (available) notifier.ensureChannel() }

    LaunchedEffect(isLoggedIn, available) {
        if (!isLoggedIn || !available) {
            // Signing out forgets what was seen as well as what was shown: notices belong to an
            // account, and keeping the set would let the next account's ready chapters arrive
            // silently — or announce the previous account's.
            state.notifications = emptyList()
            state.unread = 0
            state.ready = 0
            state.loadedOnce = false
            readySeen = null
            notifier.clear()
            return@LaunchedEffect
        }
        while (currentCoroutineContext().isActive) {
            runCatching { repository.chapterNotifications() }
                .onSuccess { response ->
                    if (response != null) {
                        val (fresh, seen) = newlyReady(response.notifications, readySeen)
                        readySeen = seen
                        state.notifications = response.notifications
                        state.unread = response.unread
                        state.ready = response.ready
                        state.error = null
                        state.loadedOnce = true
                        readyNotificationText(fresh)?.let { (title, body) ->
                            notifier.notifyReady(title, body, fresh.singleOrNull())
                        }
                    }
                }
                .onFailure {
                    // Content is kept. A poll that failed says nothing about the notices already on
                    // screen, and blanking them would lose the very thing being waited for.
                    state.error = it.message ?: "Could not check for new chapters"
                }
            delay(PollIntervalMs)
        }
    }
    return state
}
