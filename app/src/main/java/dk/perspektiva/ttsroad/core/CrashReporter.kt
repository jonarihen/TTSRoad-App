package dk.perspektiva.ttsroad.core

import android.content.Context
import dk.perspektiva.ttsroad.BuildConfig
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Starts crash reporting, if and only if a DSN was configured.
 *
 * Auto-init is disabled in the manifest so this is the single entry point; without that the SDK's
 * ContentProvider starts itself from manifest meta-data before any of the redaction below is
 * installed, and the first events go out unredacted.
 */
object CrashReporter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Latest signed-in address, read from the redaction callbacks.
     *
     * Volatile and collector-fed for the same reason `OfflineDownloads` and the media service do it
     * this way: the callbacks are not suspending, and the session changes while the process lives —
     * an event assembled after a re-login has to redact the *new* address.
     */
    @Volatile
    private var serverUrl: String? = null

    fun start(context: Context, serverUrls: Flow<String>) {
        val dsn = crashReportingDsn(BuildConfig.SENTRY_DSN) ?: return
        scope.launch { serverUrls.collectLatest { serverUrl = it } }
        SentryAndroid.init(context) { options ->
            options.dsn = dsn
            options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}"
            // Never attach device identifiers, IP addresses or usernames. The point of a
            // self-hosted instance is that there is one user; identifying them adds nothing, and
            // the account name is the one the server already authenticates.
            options.isSendDefaultPii = false
            // Crashes only. Performance tracing would sample ordinary playback requests, which is
            // a steady trickle of "what is being listened to, and when" for no diagnostic gain.
            options.tracesSampleRate = 0.0
            options.isEnableUserInteractionTracing = false
            options.beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
                redact(event, serverUrl)
            }
            options.setBeforeBreadcrumb { breadcrumb, _ ->
                breadcrumb.message = redactServerUrl(breadcrumb.message, serverUrl)
                breadcrumb
            }
        }
    }

    /**
     * Strip the server's address out of an assembled event.
     *
     * The places it reliably appears are the exception message, the message itself, and the request
     * URL on an HTTP error — which is most of what this app can fail at.
     */
    private fun redact(event: SentryEvent, serverUrl: String?): SentryEvent {
        if (serverUrl.isNullOrBlank()) return event
        event.message?.let { it.formatted = redactServerUrl(it.formatted, serverUrl) }
        event.request?.let { it.url = redactServerUrl(it.url, serverUrl) }
        event.exceptions?.forEach { exception ->
            exception.value = redactServerUrl(exception.value, serverUrl)
        }
        return event
    }
}
