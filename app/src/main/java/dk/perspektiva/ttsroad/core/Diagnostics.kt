package dk.perspektiva.ttsroad.core

import dk.perspektiva.ttsroad.data.ServerCapabilities

/** The newest failure worded for a person this process, so "Copy diagnostics" can include it (#506). */
object LastError {
    @Volatile
    var latest: UserFacingError? = null
        internal set
}

/**
 * Plain text for a bug report or a message to whoever runs the server (#506).
 *
 * Holds no token and no address: [serverAddresses] (the signed-in and saved ones) are stripped from
 * the whole report with the same rule the crash reporter uses, so a self-hosted home address does not
 * leave the phone by way of the clipboard either.
 */
internal fun diagnosticsReport(
    appVersion: String,
    androidVersion: String,
    device: String,
    capabilities: ServerCapabilities,
    lastError: UserFacingError?,
    serverAddresses: Collection<String>,
): String {
    // shortcut: reflection over the Boolean fields, so a new capability shows up without editing this.
    val enabled = capabilities.javaClass.declaredFields
        .filter { it.type == java.lang.Boolean.TYPE && !it.isSynthetic }
        .filter { runCatching { it.isAccessible = true; it.getBoolean(capabilities) }.getOrDefault(false) }
        .map { it.name }.sorted()
    val report = buildString {
        appendLine("TTSRoad app $appVersion")
        appendLine("Android $androidVersion, $device")
        appendLine("Server: ${capabilities.serverName} ${capabilities.serverVersion ?: "(version not reported)"}, API ${capabilities.apiVersion}")
        appendLine("Capabilities: ${enabled.joinToString().ifEmpty { "none" }}")
        appendLine("Address: [redacted]")
        if (lastError == null) {
            append("Last error: none this session")
        } else {
            append("Last error: ${lastError.message}")
            lastError.details?.let { append("\n").append(it) }
        }
    }
    return redactServerUrls(report, serverAddresses) ?: report
}
