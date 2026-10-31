package dk.perspektiva.ttsroad.data

import android.content.Context
import androidx.core.util.AtomicFile
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dk.perspektiva.ttsroad.core.readUtf8OrNull
import dk.perspektiva.ttsroad.core.writeUtf8
import java.io.File

/**
 * One fiction's chapter list as it stood when it was saved (#486). In `data` for R8 (Moshi-reflected).
 */
data class StoredChapters(
    val accountKey: String = "",
    val savedAt: Long = 0L,
    val response: ChaptersResponse = ChaptersResponse(fiction = FictionSummary(id = 0, title = "")),
)

/**
 * Chapter lists on disk, one file per fiction, beside [LibrarySnapshotStore]'s library (#486).
 *
 * Chapter lists were memory-only, so after the process died a fiction could not be opened without
 * a network even with chapters downloaded, and its Android Auto folder came back empty. The app
 * stores the full list and the media service the playable-only one it asks for; they are separate
 * files because the second is a subset and must not hide chapters from the first. Read only for the
 * account that wrote them; [clear] on sign-out.
 */
class ChapterListStore internal constructor(private val dir: File) {
    constructor(context: Context) : this(File(context.filesDir, DirName))

    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        .adapter(StoredChapters::class.java)

    private fun file(fictionId: Int, playableOnly: Boolean) =
        AtomicFile(File(dir, if (playableOnly) "$fictionId-playable.json" else "$fictionId.json"))

    fun read(
        accountKey: String?,
        fictionId: Int,
        playableOnly: Boolean,
        formerKeys: Collection<String> = emptyList(),
    ): StoredChapters? {
        if (accountKey.isNullOrEmpty()) return null
        val json = synchronized(lock) { file(fictionId, playableOnly).readUtf8OrNull() } ?: return null
        val stored = runCatching { adapter.fromJson(json) }.getOrNull() ?: return null
        return stored.takeIf { it.accountKey == accountKey || it.accountKey in formerKeys }
    }

    fun write(
        accountKey: String?,
        fictionId: Int,
        playableOnly: Boolean,
        response: ChaptersResponse,
        savedAt: Long = System.currentTimeMillis(),
    ) {
        if (accountKey.isNullOrEmpty()) return
        val json = runCatching { adapter.toJson(StoredChapters(accountKey, savedAt, response)) }.getOrNull() ?: return
        synchronized(lock) {
            runCatching {
                dir.mkdirs()
                file(fictionId, playableOnly).writeUtf8(json)
            }
        }
    }

    fun clear() {
        synchronized(lock) { runCatching { dir.deleteRecursively() } }
    }

    companion object {
        const val DirName = "chapter_lists"
        private val lock = Any()
    }
}
