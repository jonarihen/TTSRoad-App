package dk.perspektiva.ttsroad.download

import android.content.Context
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which audio each downloaded chapter actually holds, by content hash (#109).
 *
 * Media3's download index keys on the media URL and stores nothing about the bytes, and the URL is
 * exactly what does *not* change when the server re-converts a chapter. So the identity of what is
 * on disk has to be kept beside it.
 *
 * A flat file rather than DataStore: this is written in one shot after a freshness check, read once
 * per check, and is worthless if it outlives the download store it describes — the same shape as
 * `PlaybackHistoryStore`, which lives next to it in `filesDir` for the same reasons.
 *
 * Every read failure degrades to "no hashes recorded", which reads downstream as *adopt*, never as
 * *stale*. A corrupt file therefore costs one missed re-convert, not a library-wide re-download on
 * a mobile connection.
 */
class AudioHashRecord(context: Context, moshi: Moshi = Moshi.Builder().build()) {
    private val file = File(context.filesDir, FileName)
    private val adapter: JsonAdapter<Map<String, String>> = moshi.adapter(
        Types.newParameterizedType(Map::class.java, String::class.java, String::class.java),
    )

    private val _hashes = MutableStateFlow(read())

    /** chapter id -> the sha256 of the audio this device downloaded. */
    val hashes: StateFlow<Map<Int, String>> = _hashes.asStateFlow()

    fun current(): Map<Int, String> = _hashes.value

    /** Record [update] over what is held. */
    fun merge(update: Map<Int, String>) {
        if (update.isEmpty()) return
        val next = _hashes.value + update
        if (next == _hashes.value) return
        _hashes.value = next
        write(next)
    }

    /**
     * Drop hashes for chapters that are no longer downloaded.
     *
     * Separate from [merge] because pruning is about the download store as a whole and a merge only
     * ever knows about one fiction — pruning inside a merge would have each fiction's scan delete
     * every other fiction's record.
     */
    fun prune(downloaded: Set<Int>) {
        val next = pruneRecordedHashes(_hashes.value, downloaded)
        if (next == _hashes.value) return
        _hashes.value = next
        write(next)
    }

    /** Forget one chapter — called when its download goes, so a later re-download starts clean. */
    fun forget(chapterId: Int) {
        val next = _hashes.value - chapterId
        if (next == _hashes.value) return
        _hashes.value = next
        write(next)
    }

    fun clear() {
        _hashes.value = emptyMap()
        runCatching { file.delete() }
    }

    private fun read(): Map<Int, String> = runCatching {
        if (!file.exists()) return@runCatching emptyMap()
        adapter.fromJson(file.readText())
            ?.mapNotNull { (key, value) -> key.toIntOrNull()?.let { it to value } }
            ?.toMap()
            .orEmpty()
    }.getOrDefault(emptyMap())

    private fun write(hashes: Map<Int, String>) {
        runCatching {
            file.writeText(adapter.toJson(hashes.mapKeys { it.key.toString() }))
        }
    }

    private companion object {
        const val FileName = "downloaded_audio_hashes.json"
    }
}
