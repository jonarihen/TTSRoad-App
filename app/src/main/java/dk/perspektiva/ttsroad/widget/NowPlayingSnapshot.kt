package dk.perspektiva.ttsroad.widget

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Player
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

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
 * record, it is small, and the last writer winning is the correct behaviour — a newer note about
 * what the player is doing always supersedes an older one.
 *
 * Every failure is swallowed to a null or a no-op. A widget that cannot read its own note should
 * show the empty state; it must never take the media service down with it.
 */
class NowPlayingStore(context: Context) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(NowPlayingSnapshot::class.java)
    private val file = File(context.applicationContext.filesDir, "widget_now_playing.json")

    fun read(): NowPlayingSnapshot? =
        runCatching { if (file.isFile) adapter.fromJson(file.readText()) else null }
            .getOrNull()
            ?.takeIf { it.mediaId.isNotBlank() }

    fun write(snapshot: NowPlayingSnapshot) {
        runCatching { file.writeText(adapter.toJson(snapshot)) }
    }

    /** Signing out must not leave the last book's title on the home screen. */
    fun clear() {
        runCatching { if (file.exists()) file.delete() }
    }
}
