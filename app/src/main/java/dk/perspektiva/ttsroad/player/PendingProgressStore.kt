package dk.perspektiva.ttsroad.player

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * One recorded playback position, waiting to reach the server.
 *
 * [clientUpdatedAt] is the wall-clock moment the position was *recorded*, not the moment it is
 * sent. That distinction is the whole point: a phone that listened offline for two hours and
 * reconnects afterwards must be ordered against the browser by when the listening happened, not by
 * who happened to reconnect last.
 */
data class PendingProgress(
    val fictionId: Int,
    val chapterId: Int,
    val positionSeconds: Double,
    val isPlayed: Boolean,
    /** ISO-8601 UTC, e.g. `2026-08-11T09:41:07Z` — the format the backend parses. */
    val clientUpdatedAt: String,
    /** Epoch millis of the same moment, kept so entries can be ordered without reparsing. */
    val recordedAtMillis: Long,
)

/** The server's format for `client_updated_at`. Seconds precision, explicit Z, never a local zone. */
private val Iso8601Utc: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

fun iso8601Utc(epochMillis: Long): String = Iso8601Utc.format(Instant.ofEpochMilli(epochMillis))

/**
 * How many chapters can be waiting to sync before the oldest are dropped.
 *
 * Matches the server's own `MAX_PLAYBACK_SYNC_ITEMS`, so a full queue is still exactly one batch.
 * Entries are per chapter rather than per tick, so reaching this at all means listening to 500
 * distinct chapters without ever reconnecting.
 */
const val MaxPendingProgress: Int = 500

/**
 * Playback positions recorded on this device that the server has not accepted yet.
 *
 * Before this existed, a failed progress post was simply dropped — `runCatching { ... }` around the
 * call and nothing else. Offline listening therefore left no trace, and the next successful write
 * from the phone carried whatever position it happened to be at, with no timestamp, which the
 * server had no way to order against a newer position reached in the browser meanwhile.
 *
 * **Coalesced per chapter.** Only the newest position for a given chapter is worth sending; the
 * fifteen-second ticks in between are superseded the moment the next one lands. That is what keeps
 * an eight-hour offline night to a handful of entries rather than two thousand.
 *
 * Persisted to `filesDir/pending_progress.json`, because the case this exists for — the phone is
 * offline and stays offline — is also the case where the process is most likely to be killed before
 * it ever reconnects.
 */
class PendingProgressStore(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter<List<PendingProgress>>(
        Types.newParameterizedType(List::class.java, PendingProgress::class.java),
    )
    private val file = File(context.applicationContext.filesDir, "pending_progress.json")

    private val lock = Any()
    private var entries: MutableList<PendingProgress> = load().toMutableList()

    /**
     * Record a position, replacing any earlier one for the same chapter.
     *
     * Answers the entry as stored, so the caller can send exactly what was queued rather than
     * re-deriving a stamp that would then differ from the persisted copy.
     */
    fun record(
        fictionId: Int,
        chapterId: Int,
        positionSeconds: Double,
        isPlayed: Boolean,
    ): PendingProgress {
        val now = clock()
        val entry = PendingProgress(
            fictionId = fictionId,
            chapterId = chapterId,
            positionSeconds = positionSeconds.coerceAtLeast(0.0),
            isPlayed = isPlayed,
            clientUpdatedAt = iso8601Utc(now),
            recordedAtMillis = now,
        )
        synchronized(lock) {
            entries.removeAll { it.chapterId == chapterId }
            entries.add(entry)
            // Oldest first out. A position from last week matters less than one from this morning,
            // and the cap is only ever reached by a device that has been offline for a very long
            // time with a very large library.
            while (entries.size > MaxPendingProgress) {
                entries.removeAt(0)
            }
            persist()
        }
        return entry
    }

    /** Everything waiting, oldest first. */
    fun pending(): List<PendingProgress> = synchronized(lock) { entries.toList() }

    fun isEmpty(): Boolean = synchronized(lock) { entries.isEmpty() }

    /**
     * Drop entries the server has dealt with.
     *
     * Keyed on the recorded stamp as well as the chapter: a tick that landed *while* the flush was
     * in flight has already replaced the entry being acknowledged, and discarding it because an
     * older copy of the same chapter succeeded would lose the newer position.
     */
    fun resolve(resolved: Collection<PendingProgress>) {
        if (resolved.isEmpty()) return
        val keys = resolved.map { it.chapterId to it.recordedAtMillis }.toSet()
        synchronized(lock) {
            val removed = entries.removeAll { (it.chapterId to it.recordedAtMillis) in keys }
            if (removed) persist()
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            runCatching { if (file.exists()) file.delete() }
        }
    }

    private fun persist() {
        runCatching { file.writeText(adapter.toJson(entries)) }
    }

    private fun load(): List<PendingProgress> =
        runCatching { if (file.isFile) adapter.fromJson(file.readText()) else null }
            .getOrNull()
            .orEmpty()
}
