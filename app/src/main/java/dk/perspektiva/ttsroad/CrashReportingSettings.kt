package dk.perspektiva.ttsroad

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.core.CrashReporter
import dk.perspektiva.ttsroad.ui.AarisColor
import dk.perspektiva.ttsroad.ui.MetaText
import dk.perspektiva.ttsroad.ui.MinTouchTargetSize

/**
 * Whether this APK reports crashes, and where (#367).
 *
 * The DSN is fixed at build time, so an APK the updater installed could report somewhere without
 * the phone ever saying so. The status is always shown; the switch only when there is a DSN to turn
 * off, since "on" is not something a build without one can be.
 */
@Composable
internal fun CrashReportingSetting() {
    val context = LocalContext.current
    CrashReportingSettingContent(
        configured = CrashReporter.isConfigured,
        initiallyEnabled = remember { CrashReporter.isEnabled(context) },
        status = { CrashReporter.status(context) },
        onEnabledChange = { CrashReporter.setEnabled(context, it) },
    )
}

@Composable
internal fun CrashReportingSettingContent(
    configured: Boolean,
    initiallyEnabled: Boolean,
    status: () -> String,
    onEnabledChange: (Boolean) -> Unit,
) {
    var enabled by remember { mutableStateOf(initiallyEnabled) }
    // Re-read after every change so the line and the switch can never disagree.
    val line = remember(enabled) { status() }
    if (!configured) {
        SettingsItem(label = "Crash reporting", value = line)
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTargetSize)
            .testTag(CrashReportingToggleTag)
            .toggleable(
                value = enabled,
                role = Role.Switch,
                onValueChange = {
                    onEnabledChange(it)
                    enabled = it
                },
            )
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingsItem(label = "Crash reporting", value = line)
            MetaText(
                text = "Crash reports go to the address this build was made with. The signed-in " +
                    "server's address is removed from them. Turning this off takes effect at once.",
                color = AarisColor.Dim,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = null)
    }
}

internal const val CrashReportingToggleTag = "crash_reporting_toggle"
