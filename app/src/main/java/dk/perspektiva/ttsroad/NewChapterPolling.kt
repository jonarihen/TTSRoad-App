package dk.perspektiva.ttsroad

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import dk.perspektiva.ttsroad.core.ServiceLocator
import dk.perspektiva.ttsroad.data.ChapterNotificationEntry
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.data.newlyReady
import dk.perspektiva.ttsroad.data.readyNotificationText
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Which notices were already ready the last time *anything* looked, kept on disk (#344).
 *
 * In memory, that set was lost with the process, so the first look of every session was silent by
 * necessity — and a chapter that became ready while the app was closed was absorbed into that
 * silent look and never announced. On disk, a cold start (or the background job) compares against
 * what was last seen and announces only the difference.
 *
 * The rule that keeps the backlog quiet survives unchanged: an account this handset has **no**
 * record for reads as null, and [newlyReady] announces nothing on a null. One record, for one
 * account, keyed by [notificationSessionKey] — a hash, so the token itself is never written here.
 * A different account (or a rotated token) finds no record and starts silent, which is exactly the
 * "never announce the previous account's chapters" rule.
 *
 * Plain text rather than Moshi on purpose: nothing reflective lives outside `data/` without a
 * proguard line, and two lines of text do not need one.
 */
internal class NewChapterSeenStore(private val file: File) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, FileName))

    fun read(account: String): Set<Int>? = synchronized(Lock) {
        val lines = runCatching { file.readLines() }.getOrNull() ?: return null
        if (lines.firstOrNull() != account) return null
        lines.getOrNull(1).orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
    }

    fun write(account: String, ids: Set<Int>) = synchronized(Lock) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText("$account\n${ids.sorted().joinToString(",")}\n")
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        }
        Unit
    }

    fun clear() = synchronized(Lock) {
        runCatching { file.delete() }
        Unit
    }

    companion object {
        const val FileName: String = "new_chapters_seen.txt"
        private val Lock = Any()
    }
}

/**
 * One check: compare [notifications] against what this account last saw, remember the new set,
 * and post the pulled → ready difference. Shared by the in-app poller and [NewChapterPollJob], so
 * the "is this news" rule has one implementation.
 *
 * Serialised process-wide: the job and the in-app poll can land in the same second, and both
 * reading the old set would announce one chapter twice.
 *
 * [stillOwns] is checked after the read and before anything is written or posted, so a sign-out
 * or account switch mid-request neither records nor announces the old account's chapters.
 */
internal suspend fun announceNewlyReady(
    notifications: List<ChapterNotificationEntry>,
    session: SessionState,
    store: NewChapterSeenStore,
    notifier: NewChapterNotifier,
    stillOwns: suspend () -> Boolean,
) {
    AnnounceLock.withLock {
        val account = notificationSessionKey(session)
        val (fresh, seen) = newlyReady(notifications, store.read(account))
        if (!stillOwns()) return
        store.write(account, seen)
        readyNotificationText(fresh)?.let { (title, body) ->
            notifier.notifyReady(title, body, fresh.singleOrNull(), session)
        }
    }
}

private val AnnounceLock = Mutex()

/**
 * The check that runs with the app closed (#344).
 *
 * A platform [JobService] rather than WorkManager: the job is one periodic, network-constrained
 * request, the platform scheduler does exactly that, and the app gains no dependency (and no
 * reflective initializer for R8 to worry about) for it.
 *
 * Gated exactly as the in-app poller is — signed in, capabilities resolved, and both
 * `notifications` and `follows` — because discovery is unauthenticated and a capable server says
 * `notifications: true` to a phone with nobody signed in. Signed out, the job cancels itself; the
 * next sign-in schedules it again.
 */
class NewChapterPollJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters?): Boolean {
        scope.launch {
            try {
                runCheck(applicationContext)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A failed poll is not an error worth surfacing from the background: the next
                // period tries again, and the in-app poll shows failures where someone is looking.
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        scope.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        internal const val JobId: Int = 4176

        /** Thirty minutes: well inside a reading session, and gentle on a phone left in a drawer. */
        internal val Period: Long = TimeUnit.MINUTES.toMillis(30)

        /**
         * Schedules the periodic check unless it already is. Idempotent, and deliberately does not
         * re-register an existing job: that would restart its period on every launch.
         */
        fun schedule(context: Context) {
            val scheduler = ContextCompat.getSystemService(context, JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JobId) != null) return
            val job = JobInfo.Builder(JobId, ComponentName(context, NewChapterPollJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(Period)
                .setPersisted(true)
                .build()
            runCatching { scheduler.schedule(job) }
        }

        fun cancel(context: Context) {
            val scheduler = ContextCompat.getSystemService(context, JobScheduler::class.java) ?: return
            runCatching { scheduler.cancel(JobId) }
        }

        /** The body of one run, separate from the service so it is callable from a test. */
        internal suspend fun runCheck(
            context: Context,
            repository: TtsRoadRepository = ServiceLocator.repository(context),
            currentSession: suspend () -> SessionState = { ServiceLocator.tokenStore(context).current() },
            store: NewChapterSeenStore = NewChapterSeenStore(context),
            notifier: NewChapterNotifier = NewChapterNotifier(context.applicationContext),
        ) {
            val session = currentSession()
            if (!session.isLoggedIn) {
                cancel(context)
                return
            }
            repository.refreshCurrentCapabilities()
            val resolved = repository.currentCapabilitiesResolved.value
            val capabilities = repository.currentCapabilities.value
            if (!canUseChapterNotifications(capabilities, session, resolved)) {
                // A resolved "no" is this server's answer and will not change under us; an
                // unresolved one is a dropped request, and the next period asks again.
                if (resolved) cancel(context)
                return
            }
            val response = repository.chapterNotifications() ?: return
            announceNewlyReady(response.notifications, session, store, notifier) {
                val live = currentSession()
                live.isLoggedIn && live.serverUrl == session.serverUrl && live.token == session.token
            }
        }
    }
}
