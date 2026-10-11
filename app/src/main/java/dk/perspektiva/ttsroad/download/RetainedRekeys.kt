package dk.perspektiva.ttsroad.download

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.DownloadRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
    /** Everything retained, by identity and then request id. Unreadable state reads as empty. */
    fun load(): Map<String, Map<String, DownloadRequest>>

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
 * [RetainedRekeyStore] as one small JSON file, written to a sibling and renamed into place so a file
 * that exists is always complete — the same pattern as the playback history and pending progress.
 *
 * Hand-encoded with `org.json` rather than Moshi on purpose: nothing here is reflected over, so there
 * is no model for R8 to rename and no keep rule to forget.
 */
@OptIn(UnstableApi::class)
internal class RetainedRekeyFile(private val file: File) : RetainedRekeyStore {
    override fun load(): Map<String, Map<String, DownloadRequest>> =
        runCatching { if (file.isFile) decodeRetainedRekeys(file.readText()) else emptyMap() }
            .getOrDefault(emptyMap())

    override fun save(retained: Map<String, Map<String, DownloadRequest>>): Boolean = runCatching {
        val nonEmpty = retained.filterValues { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) {
            if (file.exists() && !file.delete()) error("could not delete ${file.name}")
            return@runCatching
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(encodeRetainedRekeys(nonEmpty))
        if (!tmp.renameTo(file)) {
            if (file.exists()) file.delete()
            check(tmp.renameTo(file)) { "could not move ${tmp.name} into place" }
        }
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
