package dk.perspektiva.ttsroad.download

import android.content.Context
import androidx.core.util.AtomicFile
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dk.perspektiva.ttsroad.core.deleteUnderLock
import dk.perspektiva.ttsroad.core.readUtf8OrNull
import dk.perspektiva.ttsroad.core.writeUtf8
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow

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
 *
 * **Scoped by server (#316).** Chapter ids are only unique per server, so every operation names the
 * server identity it is about — the same identity the download's cache key is filed under — and one
 * server's scan can neither read nor prune another's hashes. A null scope is the legacy bucket.
 *
 * Hashes recorded before scoping carry no server, and are claimed only on evidence: a legacy hash
 * for chapter N belongs to the server that owns the bare `chapter:N` download, because before
 * scoping every download had a bare id. A scope passes those chapter ids as `legacyOwned`; it sees
 * and claims only those, and every other legacy hash stays where it is for its own server. Claiming
 * by "whoever scans first" would let server B's prune delete server A's hashes, and A's next scan
 * would then adopt the server's current hash — never flagging a re-converted chapter as stale.
 */
class AudioHashRecord(context: Context, moshi: Moshi = Moshi.Builder().build()) {
    private val file = AtomicFile(File(context.filesDir, FileName))
    private val adapter: JsonAdapter<Map<String, String>> = moshi.adapter(
        Types.newParameterizedType(Map::class.java, String::class.java, String::class.java),
    )

    /** Stored key -> hash. A key is `<chapter id>`, or `<scope>#<chapter id>` once scoped. */
    private val _hashes = MutableStateFlow(read())

    /**
     * Serialises every read-modify-write of [_hashes] together with the file write that follows it.
     *
     * Today's callers all happen to run on the main thread, but nothing about this class says so:
     * two scans finishing at once on different threads would each read the same map, add their own
     * fiction's hashes and write — and the later write would silently drop the earlier one's (#325).
     * Holding the lock across the write as well keeps the file in the same order as the flow.
     */
    private val lock = Any()

    /** chapter id -> the sha256 of the audio this device downloaded from the server [scope] names. */
    fun current(scope: String? = null, legacyOwned: Set<Int> = emptySet()): Map<Int, String> =
        view(_hashes.value, scope, legacyOwned)

    /** Record [update] over what is held for [scope]. */
    fun merge(update: Map<Int, String>, scope: String? = null, legacyOwned: Set<Int> = emptySet()) {
        if (update.isEmpty()) return
        mutate(scope, legacyOwned) { it + update }
    }

    /**
     * Drop [scope]'s hashes for chapters that are no longer downloaded.
     *
     * Separate from [merge] because pruning is about the download store as a whole and a merge only
     * ever knows about one fiction — pruning inside a merge would have each fiction's scan delete
     * every other fiction's record.
     */
    fun prune(downloaded: Set<Int>, scope: String? = null, legacyOwned: Set<Int> = emptySet()) {
        mutate(scope, legacyOwned) { pruneRecordedHashes(it, downloaded) }
    }

    /** Forget one chapter — called when its download goes, so a later re-download starts clean. */
    fun forget(chapterId: Int, scope: String? = null, legacyOwned: Set<Int> = emptySet()) {
        mutate(scope, legacyOwned) { it - chapterId }
    }

    /** Every server's hashes: "delete all downloads" takes every server's downloads. */
    fun clear() {
        synchronized(lock) {
            _hashes.value = emptyMap()
            runCatching { file.deleteUnderLock() }
        }
    }

    private inline fun mutate(
        scope: String?,
        legacyOwned: Set<Int>,
        transform: (Map<Int, String>) -> Map<Int, String>,
    ) {
        synchronized(lock) {
            val current = _hashes.value
            val claimed = claimLegacy(current, scope, legacyOwned)
            val next = replaceScope(claimed, scope, transform(view(claimed, scope, legacyOwned)))
            if (next != current) {
                _hashes.value = next
                write(next)
            }
        }
    }

    private fun read(): Map<String, String> = runCatching {
        val json = file.readUtf8OrNull() ?: return@runCatching emptyMap()
        adapter.fromJson(json)
            ?.filterKeys { parseKey(it) != null }
            .orEmpty()
    }.getOrDefault(emptyMap())

    private fun write(hashes: Map<String, String>) {
        runCatching {
            file.writeUtf8(adapter.toJson(hashes))
        }
    }

    private companion object {
        const val FileName = "downloaded_audio_hashes.json"

        /** Cannot occur in a server identity: both kinds are built with the fragment dropped. */
        const val ScopeSeparator = '#'

        fun key(scope: String?, chapterId: Int): String =
            if (scope == null) chapterId.toString() else "$scope$ScopeSeparator$chapterId"

        /** The scope and chapter id a stored key names, or null for one that names no chapter. */
        fun parseKey(key: String): Pair<String?, Int>? {
            val separator = key.lastIndexOf(ScopeSeparator)
            if (separator < 0) return key.toIntOrNull()?.let { null to it }
            val chapterId = key.substring(separator + 1).toIntOrNull() ?: return null
            return key.substring(0, separator).takeIf { it.isNotEmpty() }?.let { it to chapterId }
        }

        /** [scope]'s hashes, over the unclaimed legacy ones it owns ([legacyOwned]). */
        fun view(stored: Map<String, String>, scope: String?, legacyOwned: Set<Int>): Map<Int, String> {
            val legacy = mutableMapOf<Int, String>()
            val scoped = mutableMapOf<Int, String>()
            for ((key, hash) in stored) {
                val (owner, chapterId) = parseKey(key) ?: continue
                when {
                    owner == scope -> scoped[chapterId] = hash
                    owner == null && chapterId in legacyOwned -> legacy[chapterId] = hash
                }
            }
            return legacy + scoped
        }

        /**
         * File the legacy hashes of [legacyOwned] chapters under [scope]. One [scope] already holds
         * is newer, and the legacy copy is dropped. Every other legacy hash is left alone.
         */
        fun claimLegacy(stored: Map<String, String>, scope: String?, legacyOwned: Set<Int>): Map<String, String> {
            if (scope == null || legacyOwned.isEmpty()) return stored
            val legacy = stored.filterKeys { key -> parseKey(key)?.let { it.first == null && it.second in legacyOwned } == true }
            if (legacy.isEmpty()) return stored
            val claimed = stored - legacy.keys
            return claimed + legacy.mapKeys { key(scope, it.key.toInt()) }.filterKeys { it !in claimed }
        }

        fun replaceScope(stored: Map<String, String>, scope: String?, hashes: Map<Int, String>): Map<String, String> =
            stored.filterKeys { parseKey(it)?.first != scope } +
                hashes.mapKeys { key(scope, it.key) }
    }
}
