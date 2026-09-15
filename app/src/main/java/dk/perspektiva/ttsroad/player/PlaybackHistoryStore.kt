package dk.perspektiva.ttsroad.player

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A point on the playback timeline: where the audio was at a given wall-clock moment. */
data class HistorySnapshot(
    val timestamp: Long,       // wall-clock epoch millis
    val mediaId: String,       // e.g. "chapter:123" — matches the playback queue item ids
    val fictionId: Int = 0,    // lets us reload the fiction if the queue was cleared/stopped
    val chapterId: Int = 0,
    val title: String,         // chapter title
    val fictionTitle: String?,
    val positionMs: Long,      // position within the chapter when the snapshot was taken
)

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

/** The real one: a temp file renamed over the target, so a kill mid-write cannot truncate it. */
internal class FileHistoryPersistence(private val file: File) : HistoryPersistence {
    override fun read(): String? = runCatching { if (file.isFile) file.readText() else null }.getOrNull()

    override fun write(json: String) {
        runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                if (file.exists()) file.delete()
                tmp.renameTo(file)
            }
        }
    }

    override fun delete() {
        runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            if (tmp.exists()) tmp.delete()
            if (file.exists()) file.delete()
        }
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

    /** Guarded by [writeMutex]: the newest generation that has actually reached the disk. */
    private var persistedGeneration = 0L

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
        val snap = HistorySnapshot(timestamp, mediaId, fictionId, chapterId, title, fictionTitle, positionMs.coerceAtLeast(0L))
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
        }
        schedulePersist()
    }

    fun clear() {
        synchronized(lock) {
            _snapshots.value = emptyList()
            pending = PendingPersist.Delete(++generation)
        }
        schedulePersist()
    }

    private fun schedulePersist() {
        scope.launch { persistPending() }
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
    }
}
