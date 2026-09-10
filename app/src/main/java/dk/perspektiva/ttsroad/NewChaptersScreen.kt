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
import dk.perspektiva.ttsroad.data.ChapterNotificationEntry
import dk.perspektiva.ttsroad.data.ChapterNotificationState
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.data.chapterNotificationsEmptyNote
import dk.perspektiva.ttsroad.data.detailLabel
import dk.perspektiva.ttsroad.data.presentation
import dk.perspektiva.ttsroad.ui.AarisCard
import dk.perspektiva.ttsroad.ui.AarisColor
import dk.perspektiva.ttsroad.ui.MetaText
import dk.perspektiva.ttsroad.ui.MinTouchTargetSize
import dk.perspektiva.ttsroad.ui.SectionHeader
import dk.perspektiva.ttsroad.ui.ThinProgress
import kotlinx.coroutines.launch

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
    onPlay: (ChapterNotificationEntry) -> Unit,
    onOpenFiction: (ChapterNotificationEntry) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val library by repository.currentCapabilities.collectAsStateWithLifecycle()
    var actionError by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var busyId by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Int?>(null) }

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
            actionLabel = if (state.hasClearable) "Clear ${state.ready} ready" else null,
            onAction = if (state.hasClearable) {
                {
                    scope.launch {
                        repository.dismissReadChapterNotifications()
                        repository.chapterNotifications()?.let { fresh ->
                            state.notifications = fresh.notifications
                            state.unread = fresh.unread
                            state.ready = fresh.ready
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
                    chapterNotificationsEmptyNote(followsAnything = state.followsAnything == true),
                    color = AarisColor.Muted,
                )
            } else {
                rows.forEach { entry ->
                    NewChapterRow(
                        entry = entry,
                        busy = busyId == entry.id,
                        onPlay = {
                            runCatching { onPlay(entry) }
                                .onFailure { actionError = it.message ?: "Could not play that chapter" }
                        },
                        onOpen = {
                            runCatching { onOpenFiction(entry) }
                                .onFailure { actionError = it.message ?: "Could not open that fiction" }
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
            MetaText(
                entry.detailLabel(),
                color = when (entry.presentation) {
                    ChapterNotificationState.Ready -> AarisColor.Accent
                    ChapterNotificationState.Stalled -> AarisColor.Warning
                    else -> AarisColor.Muted
                },
            )
            // Only a chapter with audio offers Play, and only the server's own `dismissible` offers
            // Dismiss. Neither is inferred from the state name.
            if (entry.playable || entry.dismissible) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
