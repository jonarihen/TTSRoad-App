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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import dk.perspektiva.ttsroad.ui.SectionHeader
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

    fun refresh() {
        scope.launch { runCatching { repository.chapterNotifications() } }
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

        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        val rows = state.rows
        if (rows.isEmpty()) {
            // On most screens an empty list is a mild disappointment. Here it is usually the answer
            // somebody came for, so it is described rather than left blank.
            MetaText(
                chapterNotificationsEmptyNote(followsAnything = state.loadedOnce),
                color = AarisColor.Muted,
            )
        } else {
            rows.forEach { entry ->
                NewChapterRow(
                    entry = entry,
                    onPlay = { onPlay(entry) },
                    onOpen = { onOpenFiction(entry) },
                    onDismiss = {
                        scope.launch {
                            if (repository.dismissChapterNotification(entry.id)) {
                                repository.chapterNotifications()?.let { fresh ->
                                    state.notifications = fresh.notifications
                                    state.unread = fresh.unread
                                    state.ready = fresh.ready
                                }
                            } else {
                                // The server refused, which means this list is out of date rather
                                // than that anything failed. Re-reading it is the honest answer.
                                refresh()
                            }
                        }
                    },
                )
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
                        OutlinedButton(onClick = onPlay, shape = RectangleShape) { Text("PLAY") }
                    }
                    if (entry.dismissible) {
                        OutlinedButton(onClick = onDismiss, shape = RectangleShape) { Text("DISMISS") }
                    }
                }
            }
        }
    }
}
