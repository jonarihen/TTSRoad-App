package dk.perspektiva.ttsroad.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reading and writing the listening-state backup file (#116).
 *
 * The document is treated as **opaque** throughout. Nothing here has an opinion about what is in
 * it — it is parsed to a plain map, written back from a plain map, and never projected onto a typed
 * model. That is deliberate: the whole value of a backup is that it survives, and a document from a
 * server newer than this app must round-trip through it whole rather than being silently trimmed
 * to the fields this build happens to know about.
 */
private val moshi = Moshi.Builder().build()

private val documentAdapter = moshi.adapter<Map<String, Any?>>(
    Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
)

/** The document as JSON, ready to write to the file the user picked. */
fun listeningStateJson(document: Map<String, Any?>): String = documentAdapter.toJson(document)

/**
 * A saved file as a document, or null when it is not one.
 *
 * Null rather than an exception, and null for anything that is not a JSON object: the file came
 * from a picker where any file on the device could have been chosen, so "that is not a backup" is
 * an ordinary outcome rather than a fault. It also accepts a file that still carries the `document`
 * wrapper the export was fetched inside, since that is what someone would get by saving the raw
 * API response by hand.
 */
@Suppress("UNCHECKED_CAST")
fun parseListeningStateJson(text: String): Map<String, Any?>? {
    val parsed = runCatching { documentAdapter.fromJson(text) }.getOrNull() ?: return null
    val inner = parsed["document"]
    if (inner is Map<*, *>) return inner as Map<String, Any?>
    // An empty object is valid JSON and not a backup; restoring it would report "nothing changed"
    // and leave the user unsure whether they picked the wrong file.
    return parsed.takeIf { it.isNotEmpty() }
}

/**
 * What a restore actually did, in the server's own numbers.
 *
 * The import is a merge, so "ok" alone cannot distinguish a backup that restored four hundred
 * positions from one that was already fully applied — and the second is exactly what someone
 * restoring the wrong file would see. Any integer the report carries is worth showing, including
 * keys this build has never heard of.
 */
fun listeningStateImportSummary(report: Map<String, Any?>?): String {
    val counts = report.orEmpty()
        .mapNotNull { (key, value) ->
            val number = (value as? Number)?.toInt() ?: return@mapNotNull null
            if (number <= 0) return@mapNotNull null
            "$number ${key.replace('_', ' ')}"
        }
        .sorted()
    return if (counts.isEmpty()) {
        "Restored. Nothing in that file was newer than what the account already had."
    } else {
        "Restored: ${counts.joinToString(", ")}."
    }
}

/** A dated default name, so two backups do not land on top of each other in the same folder. */
fun listeningStateFileName(now: Date = Date()): String =
    "ttsroad-listening-state-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)}.json"
