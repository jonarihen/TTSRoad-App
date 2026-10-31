package dk.perspektiva.ttsroad

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.LibraryCache
import dk.perspektiva.ttsroad.download.OfflineDownloads
import dk.perspektiva.ttsroad.download.downloadsByFiction
import dk.perspektiva.ttsroad.download.formatStorageSize
import dk.perspektiva.ttsroad.download.playedAmong
import dk.perspektiva.ttsroad.ui.AarisColor
import dk.perspektiva.ttsroad.ui.MetaText
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** A cleanup waiting on a yes: what to say, and the chapters it would delete. */
private data class PendingCleanup(val title: String, val chapterIds: List<Int>)

/**
 * What is downloaded, across every book, with the two cleanups the single "delete all" lacked (#497):
 * drop what has been played, per fiction or everywhere.
 *
 * "Played" comes from the same chapter lists the fiction screen uses ([LibraryCache]), loaded on
 * demand and read from disk when offline. A fiction whose list is unavailable offers no played
 * cleanup, since deleting audio on a guess is worse than leaving it.
 */
@Composable
internal fun DownloadsOverview(
    downloads: OfflineDownloads,
    cache: LibraryCache,
    onError: (String?) -> Unit,
) {
    val downloadState by downloads.downloads.collectAsStateWithLifecycle()
    val library by cache.library.collectAsStateWithLifecycle()
    val groups = remember(downloadState) { downloadsByFiction(downloadState) }
    val fictionIds = remember(groups) { groups.map { it.fictionId }.filter { it != 0 } }
    var pending by remember { mutableStateOf<PendingCleanup?>(null) }

    LaunchedEffect(fictionIds) { fictionIds.forEach(cache::ensureChapters) }
    val chapters by remember(fictionIds) {
        if (fictionIds.isEmpty()) {
            flowOf(emptyMap<Int, List<ChapterSummary>?>())
        } else {
            combine(fictionIds.map { id -> cache.chapters(id).map { id to it.value } }) { it.toMap() }
        }
    }.collectAsStateWithLifecycle(emptyMap())

    if (groups.isEmpty()) return
    HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)

    val played = groups.associate { it.fictionId to playedAmong(it.chapterIds, chapters[it.fictionId]) }
    val allPlayed = played.values.flatten()
    val titles = library.value?.fictions.orEmpty().associate { it.id to it.title }

    MetaText(text = "Downloaded books")
    for (group in groups) {
        val name = titles[group.fictionId]
            ?: if (group.fictionId == 0) "Other downloads" else "Fiction ${group.fictionId}"
        val playedIds = played[group.fictionId].orEmpty()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingsItem(
                label = name,
                value = "${group.chapterIds.size} chapters · ${formatStorageSize(group.bytes)}",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { pending = PendingCleanup("Played downloads of $name", playedIds) },
                    enabled = playedIds.isNotEmpty(),
                    shape = RectangleShape,
                ) { Text("DELETE PLAYED (${playedIds.size})") }
                OutlinedButton(
                    onClick = { pending = PendingCleanup("All downloads of $name", group.chapterIds) },
                    shape = RectangleShape,
                ) { Text("DELETE ALL") }
            }
        }
    }
    OutlinedButton(
        onClick = { pending = PendingCleanup("Played downloads in every book", allPlayed) },
        enabled = allPlayed.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
        shape = RectangleShape,
    ) { Text("DELETE PLAYED DOWNLOADS (${allPlayed.size})") }

    pending?.let { cleanup ->
        ConfirmDialog(
            title = cleanup.title.uppercase(),
            body = "${cleanup.chapterIds.size} downloaded chapters are removed from this phone. " +
                "Nothing is lost on the server, and they stream again if you play them.",
            confirmLabel = "DELETE",
            onConfirm = {
                pending = null
                val failed = cleanup.chapterIds.count { !downloads.remove(it) }
                onError(if (failed == 0) null else "Could not delete $failed chapters. They were left in place.")
            },
            onDismiss = { pending = null },
        )
    }
}
