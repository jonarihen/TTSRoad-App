package dk.perspektiva.ttsroad

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.data.FictionStorageRow
import dk.perspektiva.ttsroad.data.ServerCapabilities
import dk.perspektiva.ttsroad.data.ServerStorageOverview
import dk.perspektiva.ttsroad.data.ServerStorageResponse
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.data.canReadServerStorage
import dk.perspektiva.ttsroad.data.serverStorageOverview
import dk.perspektiva.ttsroad.ui.AarisCard
import dk.perspektiva.ttsroad.ui.AarisColor
import dk.perspektiva.ttsroad.ui.MetaText
import dk.perspektiva.ttsroad.ui.MinTouchTargetSize
import dk.perspektiva.ttsroad.ui.ThinProgress
import kotlinx.coroutines.launch

/**
 * How much disk the *server* is using, next to the card that says how much this phone is (#124).
 *
 * The two sit together on purpose. "Storage" in this app used to mean the download cache — tens or
 * hundreds of megabytes on a phone — while the thing actually at risk of filling up is the volume
 * the server writes its MP3s to, and finding that out meant opening a browser on a laptop.
 *
 * **Read-only, deliberately and permanently.** The web console can scan for orphans, delete them,
 * drop voice samples, drop the audio of excluded chapters and wipe one fiction's audio; none of that
 * is mirrored here, and the backend publishes no mobile route for any of it. They are irreversible,
 * and a phone is a bad place to confirm an irreversible delete of somebody's audio library. Seeing
 * which book is holding two gigabytes is most of the value; acting on it can wait for a keyboard.
 *
 * Every size on this card is a string the server rendered. Nothing here formats a byte count — see
 * [dk.perspektiva.ttsroad.data.ServerStorageOverview] for why that matters more than it looks.
 */
@Composable
internal fun ServerStorageSettings(
    capabilities: ServerCapabilities,
    isAdmin: Boolean,
    repository: TtsRoadRepository,
) {
    val scope = rememberCoroutineScope()
    val allowed = canReadServerStorage(capabilities, isAdmin)
    var storage by remember { mutableStateOf<ServerStorageResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var showEveryFiction by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            isLoading = true
            error = null
            runCatching { repository.serverStorage() }
                .onSuccess { storage = it }
                .onFailure { error = it.message ?: "Could not read the server's disk usage" }
            isLoading = false
        }
    }

    // Both halves of the gate are in the key, so an account that would be refused is never asked and
    // a server discovered after Settings opened still loads.
    LaunchedEffect(allowed) { if (allowed) load() }

    ServerStorageSection(
        allowed = allowed,
        storage = storage,
        isLoading = isLoading,
        error = error,
        showEveryFiction = showEveryFiction,
        onToggleEveryFiction = { showEveryFiction = !showEveryFiction },
        onRefresh = ::load,
    )
}

/**
 * The card itself, split from the loading so its states can be drawn in a test.
 *
 * There are four and they are not the same thing: nothing to show because this account may not ask,
 * nothing yet because the request is in flight, nothing because the request failed, and a server
 * whose fictions genuinely hold no audio. Only the first is silent.
 */
@Composable
internal fun ServerStorageSection(
    allowed: Boolean,
    storage: ServerStorageResponse?,
    isLoading: Boolean,
    error: String?,
    showEveryFiction: Boolean,
    onToggleEveryFiction: () -> Unit,
    onRefresh: () -> Unit,
) {
    // Hidden rather than explained. Unlike the Logs screen, which someone navigates to and is owed
    // an answer, this is a card on a page about something else: a non-admin has no way to have
    // expected it, and a paragraph about a permission they do not have is noise.
    if (!allowed) return

    MetaText(text = "// Server storage", color = AarisColor.Accent)
    AarisCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MetaText(
                text = "What the server itself is holding, not this phone. Reclaiming any of it — " +
                    "orphan files, excluded chapters' audio, voice samples — stays on the web " +
                    "console, where an irreversible delete can be confirmed properly.",
                color = AarisColor.Dim,
            )
            error?.let { MetaText(text = it, color = AarisColor.Danger) }
            if (isLoading && storage == null) {
                ThinProgress(fraction = 1f, modifier = Modifier.fillMaxWidth(), height = 2.dp)
            }

            val overview = remember(storage) { serverStorageOverview(storage) }
            val loaded = storage
            if (overview == null || loaded == null) {
                if (error == null) {
                    MetaText(text = "Loading…", color = AarisColor.Muted)
                }
            } else {
                ServerStorageTotals(response = loaded, overview = overview)

                HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
                MetaText(text = "Per fiction, largest first")
                if (overview.rows.isEmpty()) {
                    MetaText(
                        text = "No fiction on this server has any audio on disk yet.",
                        color = AarisColor.Muted,
                    )
                } else {
                    // The whole table is a scroll of its own on a server with a few hundred books,
                    // and this card lives inside a Settings page that already scrolls. The top few
                    // answer "what is using the disk"; the rest is there for anyone who asks.
                    val visible = if (showEveryFiction) {
                        overview.rows
                    } else {
                        overview.rows.take(TopFictionsShown)
                    }
                    val largest = overview.rows.first().audioBytes
                    visible.forEach { row ->
                        FictionStorageListItem(row = row, largestBytes = largest)
                    }
                    if (overview.rows.size > TopFictionsShown) {
                        OutlinedButton(
                            onClick = onToggleEveryFiction,
                            modifier = Modifier.heightIn(min = MinTouchTargetSize),
                            shape = RectangleShape,
                        ) {
                            Text(
                                if (showEveryFiction) {
                                    "SHOW TOP $TopFictionsShown"
                                } else {
                                    "SHOW ALL ${overview.rows.size}"
                                },
                            )
                        }
                    }
                }
                if (overview.emptyFictions > 0) {
                    MetaText(
                        text = "${overview.emptyFictions} more " +
                            (if (overview.emptyFictions == 1) "fiction has" else "fictions have") +
                            " no audio on disk yet.",
                        color = AarisColor.Muted,
                    )
                }
            }

            OutlinedButton(
                onClick = onRefresh,
                enabled = !isLoading,
                modifier = Modifier.heightIn(min = MinTouchTargetSize),
                shape = RectangleShape,
            ) {
                Text("REFRESH")
            }
        }
    }
}

/** How many fictions the card shows before it has to be asked for the rest. */
private const val TopFictionsShown = 6

/**
 * The volume, then what is on it, broken out by kind.
 *
 * The bar is the only figure here derived rather than rendered server-side, and it has to be: "1.4
 * TB" and "312 GB" are strings, and a share of a disk is a division.
 */
@Composable
private fun ServerStorageTotals(
    response: ServerStorageResponse,
    overview: ServerStorageOverview,
) {
    MetaText(text = "Volume")
    ThinProgress(
        fraction = overview.usedFraction,
        modifier = Modifier.fillMaxWidth(),
        height = 3.dp,
    )
    MetaText(
        text = "${response.volumeFreeLabel.ifBlank { "unknown" }} free of " +
            response.volumeTotalLabel.ifBlank { "unknown" },
        color = AarisColor.Dim,
    )

    HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
    SettingsItem(label = "Audio", value = response.totalAudioLabel)
    if (response.excludedAudioBytes > 0L) {
        // The reclaimable figure, and the reason this card is worth having at all: audio for
        // chapters that are excluded from the feed and still on disk. The delete is on the web.
        MetaText(
            text = "${response.excludedAudioLabel} of that belongs to excluded chapters and " +
                "could be reclaimed from the web console.",
            color = AarisColor.Dim,
        )
    }
    SettingsItem(label = "Audiobook exports", value = response.exportLabel)
    overview.encoderNote?.let { MetaText(text = it, color = AarisColor.Warning) }
    SettingsItem(label = "Source EPUBs", value = response.epubLabel)
    SettingsItem(label = "Cover art", value = response.coverLabel)
    SettingsItem(
        label = "Voice samples",
        value = if (response.voiceSampleCount > 0) {
            "${response.voiceSampleLabel} · ${response.voiceSampleCount} files"
        } else {
            response.voiceSampleLabel
        },
    )
}

/**
 * One fiction's share of the disk.
 *
 * The bar is relative to the largest fiction rather than to the volume: on a healthy install every
 * book is a rounding error against a terabyte, and a row of empty bars would say nothing. Relative
 * to the biggest, the shape of "one serial is most of this" is visible at a glance.
 */
@Composable
private fun FictionStorageListItem(row: FictionStorageRow, largestBytes: Long) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MetaText(
                text = row.title.ifBlank { row.slug.ifBlank { "Fiction ${row.id}" } },
                color = AarisColor.Ink,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(12.dp))
            MetaText(text = row.audioLabel, color = AarisColor.Accent)
        }
        ThinProgress(
            fraction = if (largestBytes > 0L) {
                (row.audioBytes.toDouble() / largestBytes.toDouble()).toFloat()
            } else {
                0f
            },
            modifier = Modifier.fillMaxWidth(),
            height = 2.dp,
        )
        if (row.excludedBytes > 0L) {
            MetaText(
                text = "${row.excludedLabel} excluded",
                color = AarisColor.Dim,
            )
        }
    }
}
