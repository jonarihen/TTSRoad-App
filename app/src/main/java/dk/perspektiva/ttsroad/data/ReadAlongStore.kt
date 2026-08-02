package dk.perspektiva.ttsroad.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

/**
 * Where read-along documents live between launches.
 *
 * An interface rather than a concrete file store so [TtsRoadRepository] can be tested without a
 * filesystem, and so a chapter that is downloaded rather than merely visited can be pinned by a
 * different implementation later without touching the fetch logic.
 */
interface ReadAlongStore {
    fun read(chapterId: Int): CachedReadAlong?
    fun write(chapterId: Int, entry: CachedReadAlong)
    fun clear()

    /** Persists nothing. The default, so a repository built without a files directory still works. */
    object None : ReadAlongStore {
        override fun read(chapterId: Int): CachedReadAlong? = null
        override fun write(chapterId: Int, entry: CachedReadAlong) = Unit
        override fun clear() = Unit
    }
}

/**
 * Read-along documents as one JSON file per chapter under [directory].
 *
 * One file per chapter rather than a single index: a chapter is a few hundred kilobytes of cues, and
 * rewriting every cached chapter to record that one more was opened is the kind of thing that shows
 * up as a stutter on the way into the reader.
 *
 * Every operation swallows its I/O failures. A cache that cannot be written is a chapter that needs
 * the network — an inconvenience — whereas a throw here would take down the screen the user was
 * trying to open.
 */
class ReadAlongFileStore(
    private val directory: File,
    private val maxEntries: Int = DefaultMaxEntries,
) : ReadAlongStore {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(CachedReadAlong::class.java)

    override fun read(chapterId: Int): CachedReadAlong? =
        runCatching {
            val file = fileFor(chapterId)
            if (file.isFile) adapter.fromJson(file.readText()) else null
        }.getOrNull()

    override fun write(chapterId: Int, entry: CachedReadAlong) {
        runCatching {
            if (!directory.isDirectory && !directory.mkdirs()) return
            fileFor(chapterId).writeText(adapter.toJson(entry))
            evictOldest()
        }
    }

    override fun clear() {
        runCatching { cachedFiles().forEach(File::delete) }
    }

    /** Cached chapter count — the bound is what keeps a long series off the phone's storage. */
    fun size(): Int = cachedFiles().size

    /**
     * Drop the least recently written chapters. Last-modified is a good enough recency signal here:
     * re-reading a chapter revalidates it, which rewrites the file.
     */
    private fun evictOldest() {
        val files = cachedFiles()
        if (files.size <= maxEntries) return
        files.sortedBy { it.lastModified() }
            .take(files.size - maxEntries)
            .forEach { runCatching { it.delete() } }
    }

    private fun cachedFiles(): List<File> =
        directory.listFiles { file: File -> file.isFile && file.name.startsWith(Prefix) }
            ?.toList()
            ?: emptyList()

    private fun fileFor(chapterId: Int) = File(directory, "$Prefix$chapterId.json")

    private companion object {
        const val Prefix = "readalong_"

        /** Roughly a fortnight of reading before anything is dropped, at a few hundred kB each. */
        const val DefaultMaxEntries = 40
    }
}
