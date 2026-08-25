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

    /**
     * Keep [chapterId]'s document until it is explicitly released, exempt from eviction.
     *
     * For a chapter the user downloaded on purpose. The browse cache is bounded and evicts by age,
     * which is right for chapters merely visited and wrong for a flight's worth of downloads: the
     * app already learned this with audio, where a single LRU-evicted store meant any cap would
     * eventually delete something someone had asked for by name.
     */
    fun pin(chapterId: Int, entry: CachedReadAlong)

    /** Release a pinned document. The browse cache's copy, if any, is left alone. */
    fun unpin(chapterId: Int)

    /** Whether [chapterId] is pinned — so a caller can skip re-fetching what it already holds. */
    fun isPinned(chapterId: Int): Boolean

    /** Persists nothing. The default, so a repository built without a files directory still works. */
    object None : ReadAlongStore {
        override fun read(chapterId: Int): CachedReadAlong? = null
        override fun write(chapterId: Int, entry: CachedReadAlong) = Unit
        override fun clear() = Unit
        override fun pin(chapterId: Int, entry: CachedReadAlong) = Unit
        override fun unpin(chapterId: Int) = Unit
        override fun isPinned(chapterId: Int): Boolean = false
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

    /**
     * The pinned copy first, then the browse cache — the same order playback reads its two audio
     * caches in, and for the same reason: the pinned one is the copy somebody asked for.
     */
    override fun read(chapterId: Int): CachedReadAlong? =
        runCatching {
            val file = listOf(pinnedFileFor(chapterId), fileFor(chapterId)).firstOrNull { it.isFile }
            file?.let { adapter.fromJson(it.readText()) }
        }.getOrNull()

    override fun write(chapterId: Int, entry: CachedReadAlong) {
        runCatching {
            if (!directory.isDirectory && !directory.mkdirs()) return
            // A pinned chapter is revalidated in place rather than gaining a second copy: writing
            // to the browse cache instead would leave the pinned file stale and double the bytes.
            if (pinnedFileFor(chapterId).isFile) {
                pinnedFileFor(chapterId).writeText(adapter.toJson(entry))
                return
            }
            fileFor(chapterId).writeText(adapter.toJson(entry))
            evictOldest()
        }
    }

    override fun pin(chapterId: Int, entry: CachedReadAlong) {
        runCatching {
            if (!directory.isDirectory && !directory.mkdirs()) return
            pinnedFileFor(chapterId).writeText(adapter.toJson(entry))
            // The browse copy is now redundant, and leaving it would keep occupying a slot in a
            // bound meant for chapters that have no pinned copy.
            runCatching { fileFor(chapterId).delete() }
        }
    }

    override fun unpin(chapterId: Int) {
        runCatching { pinnedFileFor(chapterId).delete() }
    }

    override fun isPinned(chapterId: Int): Boolean = pinnedFileFor(chapterId).isFile

    /** Both halves: "free the space" is one intent, as it is for the two audio caches. */
    override fun clear() {
        runCatching { (cachedFiles() + pinnedFiles()).forEach(File::delete) }
    }

    /**
     * Cached chapter count — the bound is what keeps a long series off the phone's storage.
     *
     * Counts the evictable half only, because that is what the bound applies to. Pinned documents
     * are not "cache" in the sense this number is about.
     */
    fun size(): Int = cachedFiles().size

    /** How many documents are held for downloaded chapters, and so exempt from the bound. */
    fun pinnedSize(): Int = pinnedFiles().size

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

    /**
     * The evictable half only.
     *
     * The pinned prefix deliberately does not start with [Prefix], so this filter cannot see a
     * pinned file and eviction cannot reach one. That is the whole mechanism — there is no index to
     * fall out of step with the files on disk.
     */
    private fun cachedFiles(): List<File> =
        directory.listFiles { file: File -> file.isFile && file.name.startsWith(Prefix) }
            ?.toList()
            ?: emptyList()

    private fun pinnedFiles(): List<File> =
        directory.listFiles { file: File -> file.isFile && file.name.startsWith(PinnedPrefix) }
            ?.toList()
            ?: emptyList()

    private fun fileFor(chapterId: Int) = File(directory, "$Prefix$chapterId.json")

    private fun pinnedFileFor(chapterId: Int) = File(directory, "$PinnedPrefix$chapterId.json")

    private companion object {
        const val Prefix = "readalong_"

        /**
         * Must not begin with [Prefix], or eviction would list pinned files and delete them — the
         * exact failure this split exists to prevent.
         */
        const val PinnedPrefix = "pinned-readalong_"

        /** Roughly a fortnight of reading before anything is dropped, at a few hundred kB each. */
        const val DefaultMaxEntries = 40
    }
}
