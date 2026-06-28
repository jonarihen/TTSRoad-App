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
 * Records a rolling history of playback positions over real time so the user can "jump back to
 * where they fell asleep" — even though playback kept going. Snapshots are taken by the media
 * service while playing (so it keeps logging with the app backgrounded) and persisted to disk so
 * they survive process death. Inspired by Audiobookshelf's listening-history rewind.
 */
class PlaybackHistoryStore(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter<List<HistorySnapshot>>(
        Types.newParameterizedType(List::class.java, HistorySnapshot::class.java),
    )
    private val file = File(context.applicationContext.filesDir, "playback_history.json")

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
        val current = _snapshots.value
        val last = current.lastOrNull()
        // Collapse rapid repeats on the same chapter (e.g. pause right after a tick) into one point.
        val updated = if (last != null && last.mediaId == mediaId && timestamp - last.timestamp < 5_000L) {
            current.dropLast(1) + snap
        } else {
            current + snap
        }
        val capped = if (updated.size > MAX_SNAPSHOTS) updated.takeLast(MAX_SNAPSHOTS) else updated
        _snapshots.value = capped
        scope.launch { runCatching { file.writeText(adapter.toJson(capped)) } }
    }

    fun clear() {
        _snapshots.value = emptyList()
        scope.launch { runCatching { if (file.exists()) file.delete() } }
    }

    private fun load(): List<HistorySnapshot> =
        runCatching { if (file.isFile) adapter.fromJson(file.readText()) else null }
            .getOrNull()
            ?: emptyList()

    private companion object {
        // ~8 hours of overnight listening at the service's 15s tick granularity.
        const val MAX_SNAPSHOTS = 2000
    }
}
