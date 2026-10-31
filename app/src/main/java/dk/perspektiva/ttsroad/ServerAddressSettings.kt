package dk.perspektiva.ttsroad

import dk.perspektiva.ttsroad.ui.labelCase
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.data.ServerAddressAction
import dk.perspektiva.ttsroad.data.ServerAddressResult
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.ui.AarisCard
import dk.perspektiva.ttsroad.ui.AarisColor
import dk.perspektiva.ttsroad.ui.ErrorDetails
import dk.perspektiva.ttsroad.ui.MetaText
import kotlinx.coroutines.launch

/**
 * Settings > Profile: the other addresses this server answers on (#372).
 *
 * The same server on its LAN address at home and its Tailscale name away used to mean signing out
 * and in again on every move, because the token is only ever sent to the one address signed in
 * at. That rule stays. This lets the address in use be *replaced* — after the new one has proved,
 * without the token, that it is the same server, and then, with it, that it is the same account.
 * See `TtsRoadRepository.verifyServerAddress` for the order those checks run in and why.
 */
@Composable
internal fun ServerAddressSettings(session: SessionState, repository: TtsRoadRepository) {
    val failover by repository.addressFailover.collectAsState()
    ServerAddressCard(
        inUse = session.serverUrl,
        autoSwitchedFrom = failover?.takeIf { it.to == session.serverUrl }?.from,
        saved = session.alternateUrls,
        check = repository::verifyServerAddress,
        forget = repository::forgetServerAddress,
    )
}

/**
 * The card itself, taking the actions as plain functions so it renders and is driven on the JVM
 * without a repository or a DataStore behind it.
 */
@Composable
internal fun ServerAddressCard(
    inUse: String,
    saved: List<String>,
    check: suspend (String, ServerAddressAction) -> ServerAddressResult,
    forget: suspend (String) -> Unit,
    autoSwitchedFrom: String? = null,
) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf<String?>(null) }
    var outcome by remember { mutableStateOf<AddressOutcome?>(null) }

    fun run(address: String, action: ServerAddressAction) {
        if (checking != null) return
        checking = address
        outcome = null
        scope.launch {
            val result = try {
                check(address, action)
            } finally {
                checking = null
            }
            outcome = when (result) {
                ServerAddressResult.Done -> {
                    if (sameDraft(draft, address)) draft = ""
                    AddressOutcome(serverAddressDoneMessage(action), isError = false)
                }

                is ServerAddressResult.Refused -> AddressOutcome(result.message, isError = true, details = result.details)
            }
        }
    }

    MetaText(text = "// Other addresses", color = AarisColor.Accent)
    AarisCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MetaText(
                text = "The same server under another name — its home network address, its " +
                    "Tailscale name. Switching keeps you signed in. Each address is checked to be this " +
                    "server before your sign-in is sent to it, and checked again every time you switch.",
                color = AarisColor.Dim,
            )
            MetaText(text = "In use")
            Text(text = inUse, style = MaterialTheme.typography.bodyMedium)
            autoSwitchedFrom?.let {
                MetaText(
                    text = "Switched here automatically because $it could not be reached. " +
                        "The app tries your other saved addresses when the one in use is " +
                        "unreachable, and only ones that pass the same checks as SWITCH.",
                    color = AarisColor.Accent,
                )
            }

            saved.forEach { address ->
                HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
                Text(text = address, style = MaterialTheme.typography.bodyMedium)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { run(address, ServerAddressAction.Use) },
                        enabled = checking == null,
                        shape = RectangleShape,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag(ServerAddressUseTag)
                            .semantics { contentDescription = "Switch to $address" },
                    ) {
                        Text(if (checking == address) labelCase("Checking") else labelCase("Switch"))
                    }
                    TextButton(
                        onClick = {
                            outcome = null
                            scope.launch { forget(address) }
                        },
                        enabled = checking == null,
                        shape = RectangleShape,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "Forget $address" },
                    ) {
                        Text(labelCase("Forget"))
                    }
                }
            }

            HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(labelCase("Another address")) },
                placeholder = { Text("https://server.tailnet.ts.net") },
                singleLine = true,
                enabled = checking == null,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { run(draft, ServerAddressAction.Remember) },
                    enabled = checking == null && draft.isNotBlank(),
                    shape = RectangleShape,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(labelCase("Check and save"))
                }
                OutlinedButton(
                    onClick = { run(draft, ServerAddressAction.Use) },
                    enabled = checking == null && draft.isNotBlank(),
                    shape = RectangleShape,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(labelCase("Check and switch"))
                }
            }
            if (checking != null) MetaText(text = "Checking ${checking.orEmpty()}…", color = AarisColor.Dim)
            outcome?.let {
                MetaText(text = it.message, color = if (it.isError) AarisColor.Danger else AarisColor.Accent)
                ErrorDetails(details = it.details)
            }
        }
    }
}

private data class AddressOutcome(val message: String, val isError: Boolean, val details: String? = null)

private fun sameDraft(draft: String, address: String): Boolean = draft.trim() == address.trim()

internal fun serverAddressDoneMessage(action: ServerAddressAction): String = when (action) {
    ServerAddressAction.Remember -> "Saved. It is checked again whenever you switch to it."
    ServerAddressAction.Use -> "Switched, and still signed in. Anything that was playing over the old address has stopped."
}

internal const val ServerAddressUseTag = "server-address-use"
