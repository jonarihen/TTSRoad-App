package dk.perspektiva.ttsroad.player

import android.content.Context
import androidx.core.util.AtomicFile
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dk.perspektiva.ttsroad.core.deleteUnderLock
import dk.perspektiva.ttsroad.core.readUtf8OrNull
import dk.perspektiva.ttsroad.core.writeUtf8
import dk.perspektiva.ttsroad.data.normalizeBaseUrl
import dk.perspektiva.ttsroad.data.SessionState
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A point on the playback timeline: where the audio was at a given wall-clock moment. */
data class HistorySnapshot(
    val timestamp: Long,       // wall-clock epoch millis
    val mediaId: String,       // e.g. "chapter:123" — matches the playback queue item ids
    val fictionId: Int = 0,    // lets us reload the fiction if the queue was cleared/stopped
    val chapterId: Int = 0,
    val title: String,         // chapter title
    val fictionTitle: String?,
    val positionMs: Long,      // position within the chapter when the snapshot was taken
    /**
     * Which server and account recorded this, as [historyAccountKey]. Fiction and chapter ids are
     * only unique per server, so a snapshot from another account would jump back into whatever book
     * happens to share the id there (#368). Null on snapshots written before the key existed.
     */
    val account: String? = null,
)

/**
 * The account a jump-back snapshot belongs to, or null when signed out.
 *
 * Server and username rather than the token: signing in again to the same account — after a token
 * expiry in the middle of the night, say — must keep the very history the sleeper needs. Hashed so
 * the file does not spell out a self-hosted address that is usually a home one.
 */
fun historyAccountKey(session: SessionState): String? {
    if (!session.isLoggedIn) return null
    val server = runCatching { normalizeBaseUrl(session.serverUrl) }.getOrDefault(session.serverUrl)
    val identity = "$server\u0000${session.username.orEmpty()}"
    return MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/**
 * Where the history lives between launches.
 *
 * An interface so the ordering of writes against deletes can be tested without a filesystem, and
 * without depending on which of two IO threads happens to win.
 */
internal interface HistoryPersistence {
    fun read(): String?
    fun write(json: String)
    fun delete()
}

/** The real one: an [AtomicFile], so a kill mid-write cannot truncate it. */
internal class FileHistoryPersistence(file: File) : HistoryPersistence {
    private val file = AtomicFile(file)

    override fun read(): String? = file.readUtf8OrNull()

    override fun write(json: String) {
        runCatching { file.writeUtf8(json) }
    }

    override fun delete() {
        runCatching { file.deleteUnderLock() }
    }
}

/**
 * What the next persist should make the file say, and which mutation asked for it.
 *
 * The generation is the whole point: every mutation orders itself against the others, so a persist
 * that starts late can tell that it has nothing left to say rather than writing what it captured.
 */
private sealed interface PendingPersist {
    val generation: Long

    data class Write(override val generation: Long, val snapshots: List<HistorySnapshot>) : PendingPersist
    data class Delete(override val generation: Long) : PendingPersist
}

/**
 * Records a rolling history of playback positions over real time so the user can "jump back to
 * where they fell asleep" — even though playback kept going. Snapshots are taken by the media
 * service while playing (so it keeps logging with the app backgrounded) and persisted to disk so
 * they survive process death. Inspired by Audiobookshelf's listening-history rewind.
 *
 * Mutations are ordered by a generation counter rather than by which IO job reaches the write lock
 * first. Two jobs launched in order can arrive in either order — they run on a multi-threaded
 * dispatcher — so a job that wrote the list it captured could put an older history back over a
 * newer one, and a `clear` could delete a file that a later `record` had just written. Each persist
 * instead performs the newest pending intent and skips entirely if a newer one has already landed.
 */
class PlaybackHistoryStore internal constructor(
    private val persistence: HistoryPersistence,
    private val scope: CoroutineScope,
) {
    constructor(context: Context) : this(
        FileHistoryPersistence(File(context.applicationContext.filesDir, "playback_history.json")),
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter<List<HistorySnapshot>>(
        Types.newParameterizedType(List::class.java, HistorySnapshot::class.java),
    )
    private val lock = Any()
    private val writeMutex = Mutex()

    /** Guarded by [lock]: the newest intent, and how many mutations have been ordered. */
    private var generation = 0L
    private var pending: PendingPersist? = null
    private var persistJob: Job? = null

    /** Guarded by [writeMutex]: the newest generation that has actually reached the disk. */
    private var persistedGeneration = 0L

    /** The account new snapshots are stamped with; see [bindAccount]. */
    @Volatile
    private var account: String? = null

    private val _snapshots = MutableStateFlow(load())
    val snapshots: StateFlow<List<HistorySnapshot>> = _snapshots.asStateFlow()

    fun record(
        timestamp: Long,
        mediaId: String,
        fictionId: Int,
        chapterId: Int,
        title: String,
        fictionTitle: String?,
        positionMs: Long,
    ) {
        if (mediaId.isBlank()) return
        val snap = HistorySnapshot(
            timestamp, mediaId, fictionId, chapterId, title, fictionTitle, positionMs.coerceAtLeast(0L), account,
        )
        synchronized(lock) {
            val current = _snapshots.value
            val last = current.lastOrNull()
            val updated = if (last != null && last.mediaId == mediaId && timestamp - last.timestamp < 5_000L) {
                current.dropLast(1) + snap
            } else {
                current + snap
            }
            val list = if (updated.size > MAX_SNAPSHOTS) updated.takeLast(MAX_SNAPSHOTS) else updated
            _snapshots.value = list
            pending = PendingPersist.Write(++generation, list)
            schedulePersist()
        }
    }

    /**
     * Scope the history to the signed-in account [key] (#368).
     *
     * Snapshots another account recorded are dropped — they name fiction ids that mean a different
     * book here, or none. Unstamped ones from before the key existed are adopted by the first account
     * bound, which on an upgrade is the account that recorded them.
     *
     * Signing out ([key] null) keeps everything: the next sign-in is usually the same account, and
     * only then is it known whether the history still applies.
     */
    fun bindAccount(key: String?) {
        account = key
        if (key == null) return
        synchronized(lock) {
            val current = _snapshots.value
            val scoped = current.mapNotNull { snapshot ->
                when (snapshot.account) {
                    key -> snapshot
                    null -> snapshot.copy(account = key)
                    else -> null
                }
            }
            if (scoped == current) return
            _snapshots.value = scoped
            pending = if (scoped.isEmpty()) {
                PendingPersist.Delete(++generation)
            } else {
                PendingPersist.Write(++generation, scoped)
            }
            persistJob?.cancel()
            persistJob = null
        }
        scope.launch { persistPending() }
    }

    fun clear() {
        synchronized(lock) {
            _snapshots.value = emptyList()
            pending = PendingPersist.Delete(++generation)
            persistJob?.cancel()
            persistJob = null
        }
        scope.launch { persistPending() }
    }

    suspend fun flush() {
        synchronized(lock) {
            persistJob?.cancel()
            persistJob = null
        }
        withContext(scope.coroutineContext.minusKey(Job)) { persistPending() }
    }

    private fun schedulePersist() {
        if (persistJob != null) return
        persistJob = scope.launch(start = CoroutineStart.LAZY) {
            delay(PERSIST_INTERVAL_MS)
            synchronized(lock) {
                if (persistJob !== coroutineContext[Job]) return@launch
                persistJob = null
            }
            persistPending()
        }.also { it.start() }
    }

    /**
     * Carry out the newest pending intent, or nothing at all.
     *
     * Deliberately reads [pending] *inside* the lock rather than being handed a list: that read is
     * what makes a late job write the current history instead of the one it was queued with.
     */
    internal suspend fun persistPending() {
        writeMutex.withLock {
            val action = synchronized(lock) { pending } ?: return@withLock
            if (action.generation <= persistedGeneration) return@withLock
            when (action) {
                is PendingPersist.Write -> persistence.write(adapter.toJson(action.snapshots))
                is PendingPersist.Delete -> persistence.delete()
            }
            persistedGeneration = action.generation
        }
    }

    private fun load(): List<HistorySnapshot> =
        runCatching { persistence.read()?.let { adapter.fromJson(it) } }
            .getOrNull()
            ?: emptyList()

    private companion object {
        // ~8 hours of overnight listening at the service's 15s tick granularity.
        const val MAX_SNAPSHOTS = 2000
        const val PERSIST_INTERVAL_MS = 300_000L
    }
}
