package dk.perspektiva.ttsroad.core

import androidx.core.util.AtomicFile
import java.util.concurrent.ConcurrentHashMap

/*
 * The small JSON stores in `filesDir` used to hand-roll a temp-file-and-rename, each with a "delete
 * the target, then rename" fallback that briefly left no file at all (#317). [AtomicFile] writes to a
 * sibling `.new`, syncs it and renames it over the target, so a reader — or the next process after a
 * kill mid-write — always finds either the old document or the new one, whole.
 *
 * [AtomicFile] is not safe against itself, though: a read that overlaps a write deletes the in-flight
 * `.new` as debris from a crash, and the write is then silently lost. The widget's note is read by the
 * launcher's render on one thread while the service writes it on another, so every access below
 * holds one lock per path — per path rather than per instance, because nothing stops two stores
 * being built over the same file.
 */

private val pathLocks = ConcurrentHashMap<String, Any>()

private val AtomicFile.lock: Any get() = pathLocks.getOrPut(baseFile.absolutePath) { Any() }

/**
 * Replace the file's contents with [text], or leave the previous contents in place.
 *
 * Throws when the write itself fails. [AtomicFile.finishWrite] only logs a failed sync, close or
 * rename, so a write that fails at that last step is lost silently; callers already treat a lost
 * write as survivable.
 */
internal fun AtomicFile.writeUtf8(text: String) {
    synchronized(lock) {
        val stream = startWrite()
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            finishWrite(stream)
        } catch (t: Throwable) {
            failWrite(stream)
            throw t
        }
    }
}

/** The file's contents, or null when there is none or it cannot be read. */
internal fun AtomicFile.readUtf8OrNull(): String? =
    synchronized(lock) {
        runCatching { if (baseFile.isFile) readFully().toString(Charsets.UTF_8) else null }.getOrNull()
    }

/**
 * Delete the file and any write debris next to it — including the `.tmp` sibling the hand-rolled
 * writes before #317 used, which a kill mid-write could leave holding the previous account's data.
 */
internal fun AtomicFile.deleteUnderLock() {
    synchronized(lock) {
        delete()
        java.io.File(baseFile.parentFile, "${baseFile.name}.tmp").delete()
    }
}
