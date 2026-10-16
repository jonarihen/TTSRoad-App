package dk.perspektiva.ttsroad.core

import android.content.Context
import dk.perspektiva.ttsroad.BuildConfig
import io.sentry.Breadcrumb
import io.sentry.Sentry
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
 * Starts crash reporting, if and only if a DSN was configured and it has not been turned off on
 * this phone.
 *
 * Auto-init is disabled in the manifest so this is the single entry point; without that the SDK's
 * ContentProvider starts itself from manifest meta-data before any of the redaction below is
 * installed, and the first events go out unredacted.
 */
object CrashReporter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Every server signed in to recently, newest first, read from the redaction callbacks.
     *
     * Volatile and collector-fed for the same reason `OfflineDownloads` and the media service do it
     * this way: the callbacks are not suspending, and the session changes while the process lives —
     * an event assembled after a re-login has to redact the *new* address. The earlier ones are kept
     * (and persisted) because they outlive the sign-in: old breadcrumbs, cached URLs, queue items.
     */
    @Volatile
    private var servers: List<String> = emptyList()

    @Volatile
    private var running = false

    /** Whether this build was made with a DSN at all. Without one nothing here ever runs. */
    val isConfigured: Boolean
        get() = crashReportingEnabled(BuildConfig.SENTRY_DSN)

    /** The Settings line: off/not configured, or on/off and where reports go. */
    fun status(context: Context): String = crashReportingStatus(BuildConfig.SENTRY_DSN, isEnabled(context))

    /** The user's on-device choice. Defaults to on, since a DSN is only built in deliberately. */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KeyEnabled, true)

    fun start(context: Context, serverUrls: Flow<List<String>>) {
        if (!isConfigured) return
        val appContext = context.applicationContext
        servers = prefs(appContext).getString(KeyServers, null)
            ?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        scope.launch {
            serverUrls.collectLatest { urls ->
                val updated = rememberServers(servers, urls)
                if (updated != servers) {
                    servers = updated
                    prefs(appContext).edit().putString(KeyServers, updated.joinToString("\n")).apply()
                }
            }
        }
        if (isEnabled(appContext)) initialise(appContext)
    }

    /**
     * Turn reporting on or off for this phone, taking effect immediately: off closes the SDK so
     * nothing more is sent this session, on starts it again with the same redaction.
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        prefs(appContext).edit().putBoolean(KeyEnabled, enabled).apply()
        if (!isConfigured) return
        if (enabled) initialise(appContext) else shutDown()
    }

    @Synchronized
    private fun initialise(context: Context) {
        if (running) return
        val dsn = crashReportingDsn(BuildConfig.SENTRY_DSN) ?: return
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
                redactEvent(event, servers)
            }
            options.setBeforeBreadcrumb { breadcrumb, _ -> redactBreadcrumb(breadcrumb, servers) }
        }
        running = true
    }

    @Synchronized
    private fun shutDown() {
        if (!running) return
        Sentry.close()
        running = false
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    private const val PrefsName = "ttsroad_crash_reporting"
    private const val KeyEnabled = "enabled"
    private const val KeyServers = "redacted_servers"
}

/**
 * Strip the servers' addresses out of an assembled event.
 *
 * The places they reliably appear are the exception message, the message itself, and the request
 * URL on an HTTP error — which is most of what this app can fail at.
 */
internal fun redactEvent(event: SentryEvent, servers: List<String>): SentryEvent {
    if (servers.isEmpty()) return event
    event.message?.let { it.formatted = redactServerUrls(it.formatted, servers) }
    event.request?.let { it.url = redactServerUrls(it.url, servers) }
    event.exceptions?.forEach { exception ->
        exception.value = redactServerUrls(exception.value, servers)
    }
    return event
}

/** Strip the servers' addresses from a breadcrumb's message and from every string in its data. */
internal fun redactBreadcrumb(breadcrumb: Breadcrumb, servers: List<String>): Breadcrumb {
    if (servers.isEmpty()) return breadcrumb
    breadcrumb.message = redactServerUrls(breadcrumb.message, servers)
    redactStringValues(breadcrumb.data) { redactServerUrls(it, servers) }
        .forEach { (key, value) -> if (value != null) breadcrumb.setData(key, value) }
    return breadcrumb
}
