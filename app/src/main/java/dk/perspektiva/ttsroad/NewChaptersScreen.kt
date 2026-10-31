package dk.perspektiva.ttsroad

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dk.perspektiva.ttsroad.core.userMessage
import dk.perspektiva.ttsroad.data.ChapterNotificationEntry
import dk.perspektiva.ttsroad.data.ChapterNotificationState
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.data.canRetryConversion
import dk.perspektiva.ttsroad.data.chapterNotificationsEmptyNote
import dk.perspektiva.ttsroad.data.detailLabel
import dk.perspektiva.ttsroad.data.rawStateDetail
import dk.perspektiva.ttsroad.data.notificationBody
import dk.perspektiva.ttsroad.data.presentation
import dk.perspektiva.ttsroad.ui.AarisCard
import dk.perspektiva.ttsroad.ui.AarisColor
import dk.perspektiva.ttsroad.ui.ErrorDetails
import dk.perspektiva.ttsroad.ui.AarisTag
import dk.perspektiva.ttsroad.ui.MetaText
import dk.perspektiva.ttsroad.ui.MinTouchTargetSize
import dk.perspektiva.ttsroad.ui.SectionHeader
import dk.perspektiva.ttsroad.ui.ThinProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import retrofit2.HttpException

/**
 * New chapters on the serials you follow, from pulled to playable (#175).
 *
 * The screen holds two things that look alike and mean opposite things — a chapter that is coming
 * and one that has arrived — so every row states which it is rather than relying on colour.
 *
 * A converting row offers **no** Dismiss. The server answers 409 to that request, and a control
 * that cannot succeed is worse than no control: the notice is the only record that the chapter is
 * on its way, which is exactly what somebody would be trying to clear.
 */
@Composable
internal fun NewChaptersScreen(
    padding: PaddingValues,
    state: NewChaptersState,
    repository: TtsRoadRepository,
    onPlay: suspend (ChapterNotificationEntry) -> Unit,
    onOpenFiction: suspend (ChapterNotificationEntry) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val library by repository.currentCapabilities.collectAsStateWithLifecycle()
    var actionError by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var busyId by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Int?>(null) }
    var clearing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    fun refresh() {
        state.refreshRequest += 1
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.heightIn(min = 8.dp))
        SectionHeader(
            kicker = "09",
            title = "New chapters",
            actionLabel = if (state.hasClearable && !clearing) "Clear ${state.ready} ready" else null,
            onAction = if (state.hasClearable && !clearing) {
                {
                    // Caught here, not left to the scope: a dropped connection or a 500 must read as
                    // an inline error, never as an uncaught exception that takes the process down
                    // (#345). Success hands the reload to the poller rather than writing a second
                    // copy of the list from here, so the rows, the counts and the "already seen"
                    // set stay one answer.
                    scope.launch {
                        clearing = true
                        actionError = null
                        try {
                            runCatching { repository.dismissReadChapterNotifications() }
                                .onSuccess { cleared ->
                                    if (!cleared) actionError = "Could not clear the ready chapters"
                                }
                                .onFailure {
                                    if (it is CancellationException) throw it
                                    actionError = it.message ?: "Could not clear the ready chapters"
                                }
                            refresh()
                        } finally {
                            clearing = false
                        }
                    }
                }
            } else {
                null
            },
        )
        MetaText(
            "// A chapter stays here from the moment it is pulled until it can be played",
            color = AarisColor.Dim,
        )

        if (state.isLoading) {
            ThinProgress(fraction = 1f, modifier = Modifier.fillMaxWidth(), height = 2.dp)
        }

        if (state.isUnsupported) {
            MetaText(
                "This server does not support new chapter notifications. Update the backend to track new chapters.",
                color = AarisColor.Muted,
            )
        } else {
            val visibleError = actionError ?: state.error
            visibleError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { actionError = null; refresh() }, enabled = !state.isLoading) {
                    Text("RETRY")
                }
            }

            val rows = state.rows
            if (!state.loadedOnce && state.isLoading) {
                MetaText("Loading new chapters…", color = AarisColor.Muted)
            } else if (!state.loadedOnce && state.error != null) {
                Unit
            } else if (rows.isEmpty()) {
                MetaText(
                    chapterNotificationsEmptyNote(followsAnything = state.followsAnything != false),
                    color = AarisColor.Muted,
                )
            } else {
                rows.forEach { entry ->
                    NewChapterRow(
                        entry = entry,
                        busy = busyId == entry.id,
                        // Awaited here rather than fired into another scope, so a failure lands
                        // on this screen as an inline error instead of vanishing (#348).
                        onPlay = {
                            scope.launch {
                                busyId = entry.id
                                actionError = null
                                runCatching { onPlay(entry) }
                                    .onFailure {
                                        if (it is CancellationException) throw it
                                        actionError = it.message ?: "Could not play that chapter"
                                    }
                                busyId = null
                            }
                        },
                        onOpen = {
                            scope.launch {
                                actionError = null
                                runCatching { onOpenFiction(entry) }
                                    .onFailure {
                                        if (it is CancellationException) throw it
                                        actionError = it.message ?: "Could not open that fiction"
                                    }
                            }
                        },
                        // The same route as "Convert again" on the fiction screen (#350), offered
                        // only on a row whose chapter actually failed: a stalled row can also be an
                        // excluded chapter, which the server answers with a 409.
                        onRetry = if (library.chapterMaintenance && entry.canRetryConversion) {
                            {
                                scope.launch {
                                    busyId = entry.id
                                    actionError = null
                                    runCatching { repository.retryChapter(entry.chapter.id) }
                                        .onSuccess { result ->
                                            if (result == null) {
                                                actionError = "This server cannot retry conversions"
                                            } else {
                                                refresh()
                                            }
                                        }
                                        .onFailure {
                                            if (it is CancellationException) throw it
                                            actionError = if ((it as? HttpException)?.code() == 409) {
                                                "That chapter is already converting, or is excluded"
                                            } else {
                                                it.userMessage("Could not retry that chapter")
                                            }
                                        }
                                    busyId = null
                                }
                            }
                        } else {
                            null
                        },
                        onDismiss = {
                            scope.launch {
                                busyId = entry.id
                                actionError = null
                                runCatching { repository.dismissChapterNotification(entry.id) }
                                    .onSuccess { dismissed ->
                                        if (dismissed) refresh() else actionError = "Could not dismiss that chapter"
                                    }
                                    .onFailure {
                                        actionError = it.message ?: "Could not dismiss that chapter"
                                    }
                                busyId = null
                            }
                        },
                    )
                }
            }
        }
        Spacer(Modifier.heightIn(min = 24.dp))
    }
}

@Composable
private fun NewChapterRow(
    entry: ChapterNotificationEntry,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    busy: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    AarisCard(modifier = Modifier.fillMaxWidth(), onClick = onOpen) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                entry.fiction.title,
                style = MaterialTheme.typography.titleSmall,
                color = AarisColor.Ink,
            )
            if (entry.kind == "backlog") {
                AarisTag("Backlog alert", color = AarisColor.Accent)
            }
            val body = entry.notificationBody()
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = AarisColor.Ink,
            )
            if (body != entry.chapter.title) {
                Text(
                    entry.chapter.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = AarisColor.Muted,
                )
            }
            MetaText(
                entry.detailLabel(),
                color = when (entry.presentation) {
                    ChapterNotificationState.Ready -> AarisColor.Accent
                    ChapterNotificationState.Stalled -> AarisColor.Warning
                    else -> AarisColor.Muted
                },
            )
            ErrorDetails(details = entry.rawStateDetail())
            // Only a chapter with audio offers Play, and only the server's own `dismissible` offers
            // Dismiss. Neither is inferred from the state name.
            if (entry.playable || entry.dismissible || onRetry != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onRetry != null) {
                        OutlinedButton(
                            onClick = onRetry,
                            enabled = !busy,
                            shape = RectangleShape,
                            modifier = Modifier.heightIn(min = MinTouchTargetSize),
                        ) { Text(if (busy) "WORKING" else "CONVERT AGAIN") }
                    }
                    if (entry.playable) {
                        OutlinedButton(
                            onClick = onPlay,
                            enabled = !busy,
                            shape = RectangleShape,
                            modifier = Modifier.heightIn(min = MinTouchTargetSize),
                        ) { Text(if (busy) "WORKING" else "PLAY") }
                    }
                    if (entry.dismissible) {
                        OutlinedButton(
                            onClick = onDismiss,
                            enabled = !busy,
                            shape = RectangleShape,
                            modifier = Modifier.heightIn(min = MinTouchTargetSize),
                        ) { Text(if (busy) "WORKING" else "DISMISS") }
                    }
                }
            }
        }
    }
}
