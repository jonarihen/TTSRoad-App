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
import dk.perspektiva.ttsroad.ui.AarisColor
import dk.perspektiva.ttsroad.ui.MetaText
import dk.perspektiva.ttsroad.ui.MinTouchTargetSize
import dk.perspektiva.ttsroad.update.UpdatePreferences

/**
 * Whether the app asks GitHub for a newer release on every launch (#369). On by default; off means
 * nothing leaves the phone for updates until CHECK FOR UPDATES is tapped.
 */
@Composable
internal fun AutoUpdateCheckSetting() {
    val context = LocalContext.current
    AutoUpdateCheckSettingContent(
        initiallyEnabled = remember { UpdatePreferences.read(context).autoCheck },
        onEnabledChange = { UpdatePreferences.setAutoCheck(context, it) },
    )
}

@Composable
internal fun AutoUpdateCheckSettingContent(initiallyEnabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    var enabled by remember { mutableStateOf(initiallyEnabled) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTargetSize)
            .testTag(AutoUpdateCheckToggleTag)
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
            SettingsItem(label = "Check for updates on launch", value = if (enabled) "On" else "Off")
            MetaText(
                text = "Asks GitHub for a newer release each time the app starts. When off, the app " +
                    "only checks when you tap CHECK FOR UPDATES.",
                color = AarisColor.Dim,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = null)
    }
}

internal const val AutoUpdateCheckToggleTag = "auto_update_check_toggle"
