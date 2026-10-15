package dk.perspektiva.ttsroad.download

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.DownloadRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/**
 * Where [DownloadIdentityMigration] keeps the rekeys it has not finished dispatching.
 *
 * A rekey removes the old download record before it queues the replacement, so once the remove is
 * accepted and the add refused, this copy is the only place the chapter still exists. Held in memory
 * alone it would not survive the process being reaped before the next foreground retry — and the
 * chapter would be gone from the library for good.
 */
@OptIn(UnstableApi::class)
internal interface RetainedRekeyStore {
    /**
     * Everything retained, by identity and then request id — or null when retained state exists but
     * could not be read this time. Null is not "nothing retained": the unread file may be the only
     * record of a stranded chapter, so the caller retries rather than acting on, or saving over, an
     * empty map.
     */
    fun load(): Map<String, Map<String, DownloadRequest>>?

    /** Replace what is retained with [retained]. Answers whether it is now durable. */
    fun save(retained: Map<String, Map<String, DownloadRequest>>): Boolean

    /** For tests and for builds that must not touch the disk. */
    class InMemory : RetainedRekeyStore {
        @Volatile
        var saved: Map<String, Map<String, DownloadRequest>> = emptyMap()
            private set

        override fun load() = saved

        override fun save(retained: Map<String, Map<String, DownloadRequest>>): Boolean {
            saved = retained.mapValues { it.value.toMap() }.filterValues { it.isNotEmpty() }
            return true
        }
    }
}

/**
 * [RetainedRekeyStore] as one small JSON file, written to a sibling and moved into place with a single
 * atomic replace, so a file that exists is always complete and the previous snapshot stands until the
 * new one has taken its place. Never delete-then-rename: a failure between the two would leave no
 * snapshot at all while callers still hold the requests only in memory.
 *
 * A file that cannot be *read* loads as null, to be retried. One that reads but cannot be decoded will
 * not decode on a retry either; it is moved aside to `<name>.corrupt` — kept for inspection, never
 * overwritten — and loads as empty, so one bad write cannot block every later migration and delete.
 *
 * Hand-encoded with `org.json` rather than Moshi on purpose: nothing here is reflected over, so there
 * is no model for R8 to rename and no keep rule to forget.
 */
@OptIn(UnstableApi::class)
internal class RetainedRekeyFile(
    private val file: File,
    private val readFile: (File) -> String = File::readText,
    private val replaceFile: (File, File) -> Unit = { source, target ->
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    },
) : RetainedRekeyStore {
    override fun load(): Map<String, Map<String, DownloadRequest>>? {
        val text = try {
            if (!file.exists()) return emptyMap()
            readFile(file)
        } catch (e: Exception) {
            return null
        }
        return try {
            decodeRetainedRekeys(text)
        } catch (e: Exception) {
            val aside = File(file.parentFile, "${file.name}.corrupt")
            if (runCatching { replaceFile(file, aside) }.isSuccess) emptyMap() else null
        }
    }

    override fun save(retained: Map<String, Map<String, DownloadRequest>>): Boolean = runCatching {
        val nonEmpty = retained.filterValues { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) {
            if (file.exists() && !file.delete()) error("could not delete ${file.name}")
            return@runCatching
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(encodeRetainedRekeys(nonEmpty))
        // On failure the destination is untouched and the previous snapshot still stands.
        replaceFile(tmp, file)
    }.isSuccess
}

/**
 * Only the fields a TTSRoad download sets are carried. Stream keys and byte ranges are for adaptive
 * and partial media, which this app never requests; a request carrying them could not have come
 * from [OfflineDownloads].
 */
@OptIn(UnstableApi::class)
internal fun encodeRetainedRekeys(retained: Map<String, Map<String, DownloadRequest>>): String {
    val identities = JSONObject()
    for ((identity, requests) in retained) {
        val list = JSONArray()
        for (request in requests.values) {
            list.put(
                JSONObject()
                    .put("id", request.id)
                    .put("uri", request.uri.toString())
                    .putOpt("mimeType", request.mimeType)
                    .putOpt("customCacheKey", request.customCacheKey)
                    .put("data", Base64.getEncoder().encodeToString(request.data))
                    .putOpt("keySetId", request.keySetId?.let { Base64.getEncoder().encodeToString(it) }),
            )
        }
        identities.put(identity, list)
    }
    return JSONObject().put("version", 1).put("identities", identities).toString()
}

@OptIn(UnstableApi::class)
internal fun decodeRetainedRekeys(json: String): Map<String, Map<String, DownloadRequest>> {
    val identities = JSONObject(json).getJSONObject("identities")
    return identities.keys().asSequence().associateWith { identity ->
        val list = identities.getJSONArray(identity)
        (0 until list.length()).map { i ->
            val item = list.getJSONObject(i)
            DownloadRequest.Builder(item.getString("id"), Uri.parse(item.getString("uri")))
                .setMimeType(item.optStringOrNull("mimeType"))
                .setCustomCacheKey(item.optStringOrNull("customCacheKey"))
                .setData(Base64.getDecoder().decode(item.optString("data")))
                .setKeySetId(item.optStringOrNull("keySetId")?.let { Base64.getDecoder().decode(it) })
                .build()
        }.associateBy { it.id }
    }.filterValues { it.isNotEmpty() }
}

private fun JSONObject.optStringOrNull(name: String): String? =
    if (has(name) && !isNull(name)) getString(name) else null
