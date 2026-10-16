package dk.perspektiva.ttsroad.data

import android.content.Context
import androidx.core.util.AtomicFile
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dk.perspektiva.ttsroad.core.deleteUnderLock
import dk.perspektiva.ttsroad.core.readUtf8OrNull
import dk.perspektiva.ttsroad.core.writeUtf8
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The last library this account was sent, as it stood when it was saved (#327).
 *
 * In the `data` package on purpose: it is Moshi-reflected, and R8 keeps `data.**` wholesale.
 */
data class StoredLibrary(
    /** [dk.perspektiva.ttsroad.player.historyAccountKey] of the account it belongs to. */
    val accountKey: String = "",
    val savedAt: Long = 0L,
    val library: LibraryResponse = LibraryResponse(),
)

/**
 * The library on disk, for a cold start with no network (#327).
 *
 * The library used to live in memory only, so a fresh process in an underground car park had
 * nothing at all: the car said "Nothing to resume" and the home screen was a full-screen error.
 * Every successful load is written here, and a load that fails with nothing in memory falls back
 * to it. It is read only for the account that wrote it, and deleted on sign-out.
 *
 * One file shared by the app and the media service (same process). Writes go through an
 * [AtomicFile], so a reader never sees half a library.
 */
class LibrarySnapshotStore internal constructor(file: File) {
    private val file = AtomicFile(file)

    constructor(context: Context) : this(File(context.filesDir, FileName))

    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        .adapter(StoredLibrary::class.java)

    /** The stored library if it belongs to [accountKey]; null for no account, a mismatch, or a bad file. */
    fun read(accountKey: String?): StoredLibrary? {
        if (accountKey.isNullOrEmpty()) return null
        val json = synchronized(lock) {
            file.readUtf8OrNull()
        } ?: return null
        val stored = runCatching { adapter.fromJson(json) }.getOrNull() ?: return null
        return stored.takeIf { it.accountKey == accountKey }
    }

    fun write(accountKey: String?, library: LibraryResponse, savedAt: Long = System.currentTimeMillis()) {
        if (accountKey.isNullOrEmpty()) return
        val json = runCatching { adapter.toJson(StoredLibrary(accountKey, savedAt, library)) }.getOrNull() ?: return
        synchronized(lock) {
            runCatching { file.writeUtf8(json) }
        }
    }

    fun clear() {
        synchronized(lock) {
            runCatching { file.deleteUnderLock() }
        }
    }

    companion object {
        const val FileName = "library_snapshot.json"
        private val lock = Any()
    }
}

/**
 * The line shown above a library read from disk: what it is, how old it is, and why it is not
 * fresh. "Saved copy from 14:32 — Could not reach the server." Today shows the time; any other day
 * the date as well.
 */
fun storedLibraryNotice(
    savedAt: Long,
    reason: String?,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val saved = Instant.ofEpochMilli(savedAt).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val pattern = if (saved.toLocalDate() == today) "HH:mm" else "d MMM, HH:mm"
    val stamp = saved.format(DateTimeFormatter.ofPattern(pattern, locale))
    val head = "Saved copy from $stamp"
    return if (reason.isNullOrBlank()) head else "$head — $reason"
}
