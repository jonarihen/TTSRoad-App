package dk.perspektiva.ttsroad

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.ui.AarisColor
import dk.perspektiva.ttsroad.ui.MetaText
import dk.perspektiva.ttsroad.update.ReleaseInfo
import dk.perspektiva.ttsroad.update.UpdateState
import dk.perspektiva.ttsroad.update.renderReleaseNotes
import dk.perspektiva.ttsroad.update.splitReleaseNotes

internal const val InstallPermissionExplanation =
    "Once the download finishes, Android will ask whether TTSRoad may install updates. Tap Allow, then come back here — " +
        "the update installs from there. You are only asked once."

/**
 * The updater's dialogs.
 *
 * [canInstallPackages] is asked when DOWNLOAD is tapped: if Android has not yet let this app install
 * packages, the system's unknown-sources screen is about to appear, and it says nothing about why.
 * The app explains first (#363). [onLater] snoozes, [onSkip] stops offering this version.
 */
@Composable
internal fun UpdateOverlay(
    state: UpdateState,
    onDownload: (ReleaseInfo) -> Unit,
    onDismiss: () -> Unit,
    onLater: () -> Unit = onDismiss,
    onSkip: (ReleaseInfo) -> Unit = { onDismiss() },
    canInstallPackages: () -> Boolean = { true },
) {
    var explainingInstall by remember(state) { mutableStateOf(false) }
    when (state) {
        is UpdateState.Available -> when {
            !state.announce -> Unit
            explainingInstall -> AlertDialog(
                onDismissRequest = { explainingInstall = false },
                shape = RectangleShape,
                containerColor = AarisColor.BgRaise,
                title = { Text("ALLOW UPDATES", style = MaterialTheme.typography.titleLarge) },
                text = { Text(InstallPermissionExplanation, style = MaterialTheme.typography.bodyMedium) },
                confirmButton = {
                    Button(onClick = { onDownload(state.release) }, shape = RectangleShape) {
                        Text("CONTINUE")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { explainingInstall = false }, shape = RectangleShape) {
                        Text("BACK")
                    }
                },
            )
            else -> UpdateAvailableDialog(
                release = state.release,
                onDownload = {
                    if (canInstallPackages()) onDownload(state.release) else explainingInstall = true
                },
                onLater = onLater,
                onSkip = { onSkip(state.release) },
            )
        }

        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            containerColor = AarisColor.BgRaise,
            title = { Text("DOWNLOADING UPDATE", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LinearProgressIndicator(
                        progress = { state.percent / 100f },
                        color = AarisColor.Accent,
                        trackColor = AarisColor.Line,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    MetaText(text = "${state.percent}%")
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("CANCEL") }
            },
        )

        // A refused install has to be said where the person is looking: the download dialog just
        // closed, and a line in Settings is not where anyone would look for why nothing happened.
        is UpdateState.Rejected -> AlertDialog(
            onDismissRequest = onDismiss,
            shape = RectangleShape,
            containerColor = AarisColor.BgRaise,
            title = { Text("UPDATE NOT INSTALLED", style = MaterialTheme.typography.titleLarge) },
            text = { Text(state.message, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                Button(onClick = onDismiss, shape = RectangleShape) { Text("OK") }
            },
        )

        else -> Unit // Idle / Checking / UpToDate / Failed surface in Settings instead
    }
}

internal const val PlainUpdateSummary = "A new version is available. It installs over this one and keeps your library."

@Composable
private fun UpdateAvailableDialog(
    release: ReleaseInfo,
    onDownload: () -> Unit,
    onLater: () -> Unit,
    onSkip: () -> Unit,
) {
    // The note region takes a share of the actual window, capped for ordinary portrait
    // phones. A fixed 320 dp cap still clipped the actions in landscape and short split
    // screen windows once the title and dialog padding were counted.
    val notesMaxHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.42f)
        .coerceIn(96.dp, 320.dp)
    val notes = remember(release.notes) { splitReleaseNotes(release.notes) }
    // With a plain-language lead, the changelog under it is for whoever wants it (#363).
    var showDetails by remember(release.notes) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onLater,
        containerColor = AarisColor.BgRaise,
        title = { Text("UPDATE AVAILABLE", style = MaterialTheme.typography.titleLarge) },
        text = {
            // Bounded and scrollable rather than truncated. Release notes are the only thing
            // in this dialog that says what the install actually changes, and `take(400)` cut
            // them mid-sentence with nothing on screen admitting anything was missing — so a
            // long entry ended in a severed word and the rest was simply unreachable.
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .heightIn(max = notesMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .testTag("update-notes"),
            ) {
                MetaText(text = "Version ${release.versionName}", color = AarisColor.Accent)
                // Always a plain line first, so a changelog that opens with "### Fixed" never does.
                if (notes.details.isNotBlank() || notes.summary != null) {
                    Text(
                        text = renderReleaseNotes(notes.summary ?: PlainUpdateSummary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AarisColor.Ink,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { showDetails = !showDetails }, shape = RectangleShape) {
                        Text(if (showDetails) "HIDE DETAILS" else "WHAT CHANGED IN DETAIL")
                    }
                }
                if (showDetails && notes.details.isNotBlank()) {
                    Text(
                        text = renderReleaseNotes(notes.details),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AarisColor.Muted,
                        // Left, explicitly. Prose and bullet lists are read down a common left
                        // edge; centring ragged lines costs the eye that edge on every line.
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDownload, shape = RectangleShape) {
                Text("DOWNLOAD & INSTALL")
            }
        },
        dismissButton = {
            // Two children of the dialog's flowing button row, so they wrap on a narrow phone.
            TextButton(onClick = onSkip, shape = RectangleShape) { Text("SKIP THIS VERSION") }
            TextButton(onClick = onLater, shape = RectangleShape) { Text("LATER") }
        },
    )
}
