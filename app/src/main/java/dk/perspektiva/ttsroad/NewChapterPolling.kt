package dk.perspektiva.ttsroad

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.core.util.AtomicFile
import dk.perspektiva.ttsroad.core.ServiceLocator
import dk.perspektiva.ttsroad.core.deleteUnderLock
import dk.perspektiva.ttsroad.core.readUtf8OrNull
import dk.perspektiva.ttsroad.core.writeUtf8
import dk.perspektiva.ttsroad.data.ChapterNotificationEntry
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.data.newlyReady
import dk.perspektiva.ttsroad.data.readyNotificationText
import dk.perspektiva.ttsroad.data.readySerialLines
import dk.perspektiva.ttsroad.download.newChapterDownloadTargets
import dk.perspektiva.ttsroad.download.newChaptersToDownload
import dk.perspektiva.ttsroad.player.historyAccountKeys
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
import kotlinx.coroutines.withContext

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
 * account, keyed by `historyAccountKey` — server and account, hashed, so a rotated token keeps the
 * record (#455) and nothing identifying is written here. A different account finds no record and
 * starts silent, which is exactly the "never announce the previous account's chapters" rule.
 *
 * Plain text rather than Moshi on purpose: nothing reflective lives outside `data/` without a
 * proguard line, and two lines of text do not need one.
 */
internal class NewChapterSeenStore(file: File) {
    private val file = AtomicFile(file)

    constructor(context: Context) : this(File(context.applicationContext.filesDir, FileName))

    fun read(account: String): Set<Int>? = synchronized(Lock) {
        val lines = file.readUtf8OrNull()?.lines() ?: return null
        if (lines.firstOrNull() != account) return null
        lines.getOrNull(1).orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
    }

    fun write(account: String, ids: Set<Int>) = synchronized(Lock) {
        runCatching { file.writeUtf8("$account\n${ids.sorted().joinToString(",")}\n") }
        Unit
    }

    fun clear() = synchronized(Lock) {
        runCatching { file.deleteUnderLock() }
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
): List<ChapterNotificationEntry> {
    AnnounceLock.withLock {
        val keys = historyAccountKeys(session)
        val account = keys.firstOrNull() ?: return emptyList()
        // A record under a former key of this account, or under the pre-#455 token key, is this
        // account's: read it once, and the write below moves it to the current key.
        val before = keys.firstNotNullOfOrNull { store.read(it) } ?: store.read(notificationSessionKey(session))
        val (fresh, seen) = newlyReady(notifications, before)
        if (!stillOwns()) return emptyList()
        store.write(account, seen)
        readyNotificationText(fresh)?.let { (title, body) ->
            // A group's Play starts its first playable chapter; the fiction queue follows from there.
            val target = fresh.singleOrNull() ?: fresh.firstOrNull { it.playable }
            notifier.notifyReady(title, body, target, session, if (fresh.size > 1) readySerialLines(fresh) else emptyList())
        }
        return fresh
    }
}

private val AnnounceLock = Mutex()

/**
 * "Download new chapters" (#497): queue what [announceNewlyReady] just found ready. Both pollers
 * call it with that result, so "what is new" keeps its one implementation.
 *
 * Through the ordinary download manager, so Wi-Fi only holds the audio until Wi-Fi. Tagged manual,
 * not auto: keep-ahead deletes its own downloads, and these are kept until the user deletes them.
 * Best effort — a failure here must not cost the poll its notification or its next run.
 */
internal suspend fun autoDownloadNewlyReady(
    context: Context,
    fresh: List<ChapterNotificationEntry>,
    session: SessionState,
    stillOwns: suspend () -> Boolean,
) {
    if (fresh.isEmpty()) return
    try {
        val enabled = ServiceLocator.downloadPreferences(context).current().autoDownloadNewChapters
        val targets = newChapterDownloadTargets(fresh, enabled)
        if (targets.isEmpty()) return
        val repository = ServiceLocator.repository(context)
        val downloads = ServiceLocator.offlineDownloads(context)
        for ((fictionId, wanted) in targets) {
            val chapters = repository.chapters(fictionId, playableOnly = true).chapters
            if (!stillOwns()) return
            // Media3's manager is used from the main thread everywhere else; the job runs on IO.
            withContext(Dispatchers.Main) {
                downloads.download(
                    newChaptersToDownload(chapters, wanted, downloads.downloads.value),
                    session.serverUrl,
                    queueWhenRefused = true,
                )
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // The chapter is still announced and still one tap away; the next new one tries again.
    }
}

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
            hasStoredToken: suspend () -> Boolean = { ServiceLocator.tokenStore(context).hasStoredToken() },
        ) {
            val session = currentSession()
            if (!session.isLoggedIn) {
                // A token that is stored but could not be opened is a Keystore failure, not a
                // sign-out: stay scheduled and let the next period try again (#448).
                if (!hasStoredToken()) cancel(context)
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
            val stillOwns: suspend () -> Boolean = {
                val live = currentSession()
                live.isLoggedIn && live.isSameSession(session)
            }
            val fresh = announceNewlyReady(response.notifications, session, store, notifier, stillOwns)
            autoDownloadNewlyReady(context, fresh, session, stillOwns)
        }
    }
}
