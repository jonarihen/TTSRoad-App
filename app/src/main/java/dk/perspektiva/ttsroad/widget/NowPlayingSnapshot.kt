package dk.perspektiva.ttsroad.widget

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Player
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The last thing the player was doing, written where a dead process can still be asked about it.
 *
 * A home-screen widget cannot hold a [androidx.media3.session.MediaController]: it is rendered by
 * the launcher, on demand, long after this app's process has been reaped. So the service leaves a
 * note behind instead. Everything the widget draws comes from here, and nothing it draws requires
 * the player to be alive.
 *
 * Deliberately not [dk.perspektiva.ttsroad.player.PlaybackHistoryStore], which is a rolling 2000
 * entry trail sampled every fifteen seconds and knows nothing about whether audio is *currently*
 * playing. This is one record, overwritten, that answers a different question.
 *
 * [speed] is here so the widget can extrapolate honestly. Someone listening at 1.75x covers
 * twenty-six seconds of a chapter in fifteen seconds of wall clock, and a progress bar that assumed
 * 1.0x would fall behind by nearly half.
 */
data class NowPlayingSnapshot(
    val mediaId: String = "",
    val fictionId: Int = 0,
    val chapterId: Int = 0,
    val chapterTitle: String = "",
    val fictionTitle: String? = null,
    val coverUrl: String? = null,
    val positionMs: Long = 0L,
    /** Zero when the player has not resolved one yet, which is normal for the first second. */
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val speed: Float = 1f,
    /** Wall clock when this was written. The whole staleness argument rests on it. */
    val updatedAt: Long = 0L,
)

/**
 * Take the small, durable slice of the real player that a launcher is able to render later.
 *
 * Kept here rather than in the service so the mapping can be tested against a real Media3
 * [Player] contract. In particular, an unresolved duration is [C.TIME_UNSET], not zero, and a
 * position can briefly be negative around a discontinuity. Neither value belongs in the file the
 * widget reads.
 */
internal fun nowPlayingSnapshotOf(
    player: Player,
    isPlaying: Boolean = player.isPlaying,
    updatedAt: Long = System.currentTimeMillis(),
): NowPlayingSnapshot? {
    val item = player.currentMediaItem ?: return null
    val metadata = item.mediaMetadata
    val extras = metadata.extras
    val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
    return NowPlayingSnapshot(
        mediaId = item.mediaId,
        fictionId = extras?.getInt("fiction_id")?.takeIf { it > 0 } ?: 0,
        chapterId = extras?.getInt("chapter_id")?.takeIf { it > 0 } ?: 0,
        chapterTitle = metadata.title?.toString()?.takeIf { it.isNotBlank() } ?: "Chapter",
        fictionTitle = metadata.albumTitle?.toString()?.takeIf { it.isNotBlank() },
        coverUrl = metadata.artworkUri?.toString(),
        positionMs = player.currentPosition.coerceAtLeast(0L),
        durationMs = duration,
        isPlaying = isPlaying,
        speed = player.playbackParameters.speed.takeIf { it > 0f } ?: 1f,
        updatedAt = updatedAt,
    )
}

/**
 * Reads and writes the single [NowPlayingSnapshot], as JSON in `filesDir`.
 *
 * A plain file rather than DataStore because the reader is a Glance worker in whatever process the
 * launcher decided to wake, and the write is a fire-and-forget from the media service. There is one
 * record, it is small, and the newest note about what the player is doing supersedes an older one.
 *
 * "Newest" is the subtle part. The service captures a snapshot on the main thread — Media3 requires
 * that — and then persists it off it, from several paths at once: the 15s tick, playing changes,
 * item transitions, discontinuities and speed changes. Whichever coroutine reached the disk last
 * used to win, which is not the same as the newest capture, so a pause could be overwritten by a
 * tick captured before it and the widget would offer a pause button over stopped audio. Sign-out
 * had the same problem in reverse: its delete could be undone by a publish already in flight.
 *
 * So ordering is explicit. A caller takes a [nextGeneration] at capture time, on the thread it
 * captured on, and every persist applies only if no newer generation has already landed.
 *
 * Every failure is swallowed to a null or a no-op. A widget that cannot read its own note should
 * show the empty state; it must never take the media service down with it.
 */
class NowPlayingStore internal constructor(private val persistence: SnapshotPersistence) {
    constructor(context: Context) : this(
        FileSnapshotPersistence(File(context.applicationContext.filesDir, "widget_now_playing.json")),
    )

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(NowPlayingSnapshot::class.java)

    private val mutex = Mutex()
    private val generation = AtomicLong(0L)

    /** Guarded by [mutex]: the newest generation that has actually reached the disk. */
    private var appliedGeneration = 0L

    fun read(): NowPlayingSnapshot? =
        runCatching { persistence.read()?.let { adapter.fromJson(it) } }
            .getOrNull()
            ?.takeIf { it.mediaId.isNotBlank() }

    /**
     * Claim a place in the order.
     *
     * Called at the moment the player is read, so the sequence the widget ends up agreeing with is
     * the sequence things actually happened in — not the order the IO dispatcher got round to.
     */
    fun nextGeneration(): Long = generation.incrementAndGet()

    fun write(snapshot: NowPlayingSnapshot) {
        runCatching { persistence.write(adapter.toJson(snapshot)) }
    }

    /** Signing out must not leave the last book's title on the home screen. */
    fun clear() {
        runCatching { persistence.delete() }
    }

    /**
     * Persist [snapshot] as [generation], or do nothing because something newer already landed.
     *
     * A null [snapshot] means "the queue is empty": the previous record is kept but marked stopped,
     * so "last heard" survives rather than collapsing into "nothing played". That read-modify-write
     * happens under the same lock, or a concurrent publish could land between the two halves.
     */
    suspend fun publish(generation: Long, snapshot: NowPlayingSnapshot?, stoppedAt: Long) {
        mutex.withLock {
            if (generation <= appliedGeneration) return@withLock
            appliedGeneration = generation
            if (snapshot != null) {
                write(snapshot)
            } else {
                read()?.let { previous ->
                    write(previous.copy(isPlaying = false, updatedAt = stoppedAt))
                }
            }
        }
    }

    /** Remove the note as [generation]. Ordered against [publish], so neither can undo the other. */
    suspend fun clearAt(generation: Long) {
        mutex.withLock {
            if (generation <= appliedGeneration) return@withLock
            appliedGeneration = generation
            clear()
        }
    }
}

/**
 * Where the note physically lives.
 *
 * An interface so the ordering above can be tested without a filesystem and without depending on
 * which of two IO threads happens to win.
 */
internal interface SnapshotPersistence {
    fun read(): String?
    fun write(json: String)
    fun delete()
}

/**
 * The real one. Writes through a temp file and renames it over the target: the launcher may read
 * this file at any moment, and a half-written record is one the widget has to discard.
 */
internal class FileSnapshotPersistence(private val file: File) : SnapshotPersistence {
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
