package dk.perspektiva.ttsroad

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.data.AccountActionResult
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.data.TwoFactorSetup
import dk.perspektiva.ttsroad.data.TwoFactorStatus
import dk.perspektiva.ttsroad.data.hasUnsavedRecoveryCodes
import dk.perspektiva.ttsroad.ui.AarisCard
import dk.perspektiva.ttsroad.ui.AarisColor
import dk.perspektiva.ttsroad.ui.MetaText
import kotlinx.coroutines.launch

/** Password and two-factor controls backed by the native account-security contract (#118). */
@Composable
internal fun AccountSecuritySettings(repository: TtsRoadRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<TwoFactorStatus?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    var showPassword by remember { mutableStateOf(false) }
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    var setup by remember { mutableStateOf<TwoFactorSetup?>(null) }
    var authenticationCode by remember { mutableStateOf("") }
    var showReissueConfirmation by remember { mutableStateOf(false) }
    var showDisable by remember { mutableStateOf(false) }
    var disablePassword by remember { mutableStateOf("") }
    var recoveryCodes by remember { mutableStateOf<List<String>?>(null) }

    fun rejected(message: String) {
        error = message
        note = null
    }

    LaunchedEffect(Unit) {
        runCatching { repository.twoFactorStatus() }
            .onSuccess { status = it }
            .onFailure { rejected(it.message ?: "Could not read two-factor status") }
    }

    MetaText(text = "// Account security", color = AarisColor.Accent)
    AarisCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MetaText(text = "Password")
            MetaText(
                text = "Changing it signs every other browser and device out. This phone adopts " +
                    "the replacement session the server returns.",
                color = AarisColor.Dim,
            )
            OutlinedButton(
                onClick = {
                    error = null
                    showPassword = true
                },
                enabled = !busy,
                shape = RectangleShape,
            ) {
                Text("CHANGE PASSWORD")
            }

            HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)

            MetaText(text = "Two-factor authentication")
            MetaText(
                text = when (val current = status) {
                    null -> "Checking this account…"
                    else -> if (current.enabled) {
                        "On · ${current.recoveryCodesRemaining} recovery codes left"
                    } else {
                        "Off · an authenticator code will be required after your password when on."
                    }
                },
                color = if (status?.enabled == true) AarisColor.Ok else AarisColor.Dim,
            )

            if (status?.enabled == true) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            error = null
                            showReissueConfirmation = true
                        },
                        enabled = !busy,
                        shape = RectangleShape,
                    ) {
                        Text("NEW CODES")
                    }
                    OutlinedButton(
                        onClick = {
                            error = null
                            showDisable = true
                        },
                        enabled = !busy,
                        shape = RectangleShape,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AarisColor.Danger),
                    ) {
                        Text("TURN OFF")
                    }
                }
            } else if (status != null) {
                OutlinedButton(
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            when (val result = repository.startTwoFactorSetup()) {
                                is AccountActionResult.Done -> {
                                    setup = result.value
                                    authenticationCode = ""
                                }
                                is AccountActionResult.Refused -> rejected(result.message)
                                AccountActionResult.Unsupported -> rejected(
                                    "This server no longer offers account security.",
                                )
                            }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    shape = RectangleShape,
                ) {
                    Text("SET UP 2FA")
                }
            }

            error?.let { MetaText(text = it, color = AarisColor.Danger) }
            note?.let { MetaText(text = it, color = AarisColor.Ok) }
        }
    }

    if (showPassword) {
        AlertDialog(
            onDismissRequest = { if (!busy) showPassword = false },
            title = { Text("Change password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PasswordField("Current password", currentPassword) { currentPassword = it }
                    PasswordField("New password", newPassword) { newPassword = it }
                    PasswordField("Repeat new password", confirmPassword) { confirmPassword = it }
                    if (newPassword.isNotEmpty() && confirmPassword.isNotEmpty() &&
                        newPassword != confirmPassword
                    ) {
                        MetaText(text = "The new passwords do not match.", color = AarisColor.Danger)
                    }
                    error?.let { MetaText(text = it, color = AarisColor.Danger) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && currentPassword.isNotBlank() && newPassword.isNotBlank() &&
                        newPassword == confirmPassword,
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            when (val result = repository.changePassword(currentPassword, newPassword)) {
                                is AccountActionResult.Done -> {
                                    showPassword = false
                                    currentPassword = ""
                                    newPassword = ""
                                    confirmPassword = ""
                                    note = "Password changed. Every other session was signed out."
                                }
                                is AccountActionResult.Refused -> rejected(result.message)
                                AccountActionResult.Unsupported -> rejected(
                                    "This server no longer offers password changes.",
                                )
                            }
                            busy = false
                        }
                    },
                ) { Text("CHANGE") }
            },
            dismissButton = {
                TextButton(onClick = { showPassword = false }, enabled = !busy) { Text("CANCEL") }
            },
        )
    }

    setup?.let { provisional ->
        AlertDialog(
            onDismissRequest = { if (!busy) setup = null },
            title = { Text("Connect an authenticator") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetaText(
                        text = "Open this account in your authenticator, then enter its six-digit code.",
                        color = AarisColor.Dim,
                    )
                    provisional.otpauthUri?.let { uri ->
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
                                }.onFailure {
                                    rejected("No authenticator app could open the setup link.")
                                }
                            },
                            shape = RectangleShape,
                        ) { Text("OPEN AUTHENTICATOR") }
                    }
                    MetaText(text = "Manual key")
                    Text(provisional.secret)
                    OutlinedTextField(
                        value = authenticationCode,
                        onValueChange = { authenticationCode = it.filter(Char::isDigit).take(6) },
                        label = { Text("Authentication code") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let { MetaText(text = it, color = AarisColor.Danger) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && authenticationCode.length == 6,
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            when (val result = repository.enableTwoFactor(authenticationCode)) {
                                is AccountActionResult.Done -> {
                                    setup = null
                                    recoveryCodes = result.value.recoveryCodes
                                    status = TwoFactorStatus(
                                        enabled = true,
                                        recoveryCodesRemaining = result.value.recoveryCodes.size,
                                    )
                                    note = "Two-factor authentication is on."
                                }
                                is AccountActionResult.Refused -> rejected(result.message)
                                AccountActionResult.Unsupported -> rejected(
                                    "This server no longer offers two-factor setup.",
                                )
                            }
                            busy = false
                        }
                    },
                ) { Text("ENABLE") }
            },
            dismissButton = {
                TextButton(onClick = { setup = null }, enabled = !busy) { Text("CANCEL") }
            },
        )
    }

    if (showReissueConfirmation) {
        AlertDialog(
            onDismissRequest = { if (!busy) showReissueConfirmation = false },
            title = { Text("Replace recovery codes?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Every old recovery code will stop working immediately.")
                    error?.let { MetaText(text = it, color = AarisColor.Danger) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            when (val result = repository.reissueRecoveryCodes()) {
                                is AccountActionResult.Done -> {
                                    showReissueConfirmation = false
                                    recoveryCodes = result.value.recoveryCodes
                                    status = status?.copy(
                                        recoveryCodesRemaining = result.value.recoveryCodes.size,
                                    )
                                }
                                is AccountActionResult.Refused -> rejected(result.message)
                                AccountActionResult.Unsupported -> rejected(
                                    "This server no longer offers recovery codes.",
                                )
                            }
                            busy = false
                        }
                    },
                ) { Text("REPLACE") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showReissueConfirmation = false },
                    enabled = !busy,
                ) { Text("CANCEL") }
            },
        )
    }

    if (showDisable) {
        AlertDialog(
            onDismissRequest = { if (!busy) showDisable = false },
            title = { Text("Turn off two-factor authentication?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Enter your current password to remove the second factor.")
                    PasswordField("Current password", disablePassword) { disablePassword = it }
                    error?.let { MetaText(text = it, color = AarisColor.Danger) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && disablePassword.isNotBlank(),
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            when (val result = repository.disableTwoFactor(disablePassword)) {
                                is AccountActionResult.Done -> {
                                    showDisable = false
                                    disablePassword = ""
                                    status = TwoFactorStatus(enabled = false)
                                    note = "Two-factor authentication is off."
                                }
                                is AccountActionResult.Refused -> rejected(result.message)
                                AccountActionResult.Unsupported -> rejected(
                                    "This server no longer offers two-factor changes.",
                                )
                            }
                            busy = false
                        }
                    },
                ) { Text("TURN OFF") }
            },
            dismissButton = {
                TextButton(onClick = { showDisable = false }, enabled = !busy) { Text("CANCEL") }
            },
        )
    }

    if (hasUnsavedRecoveryCodes(recoveryCodes)) {
        val codes = recoveryCodes.orEmpty()
        AlertDialog(
            // The server stores hashes, so these values can never be fetched again. Require an
            // explicit acknowledgement instead of losing them to a tap outside the dialog.
            onDismissRequest = {},
            title = { Text("Save these recovery codes") },
            text = {
                Column {
                    Text("Each code works once. Store them somewhere outside this phone.")
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(codes.joinToString("\n"))
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { shareRecoveryCodes(context, codes) },
                        shape = RectangleShape,
                    ) { Text("SHARE A COPY") }
                }
            },
            confirmButton = {
                TextButton(onClick = { recoveryCodes = null }) { Text("I SAVED THEM") }
            },
        )
    }
}

@Composable
private fun PasswordField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun shareRecoveryCodes(context: android.content.Context, codes: List<String>) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "TTSRoad recovery codes")
        putExtra(Intent.EXTRA_TEXT, codes.joinToString("\n"))
    }
    context.startActivity(Intent.createChooser(intent, "Save recovery codes"))
}
