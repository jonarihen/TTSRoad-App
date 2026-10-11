package dk.perspektiva.ttsroad.download

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.offline.DownloadIndex
import androidx.media3.exoplayer.offline.DownloadRequest

/**
 * The decisions behind re-filing 0.8.0's cache entries under a server identity.
 *
 * Pulled out of [OfflineDownloads] because this is the highest-consequence code in the download
 * stack and none of it was reachable by a test: the migration deletes cache spans and re-queues
 * every download a phone holds. Getting it wrong either wipes a library or hands one server's audio
 * to another — and it runs once, silently, on first contact with a server that names itself.
 *
 * The parts that actually touch `DownloadManager` and `SimpleCache` stay in [OfflineDownloads];
 * what lives here is which entries move, which are dropped, and whether to move at all.
 */

/**
 * Whether [incoming] should replace [current] as the identity downloads are filed under.
 *
 * Only ever moves *towards* an identity. A capabilities refresh that comes back without one — an
 * unreachable server answering as `ServerCapabilities.Baseline` — must not undo a completed
 * migration, because that would re-key everything back and start the whole library downloading a
 * second time. Re-adopting the identity already held is likewise a no-op rather than a repeat.
 */
internal fun shouldAdoptIdentity(current: String?, incoming: String?): Boolean =
    incoming != null && incoming != current

/**
 * Cache keys to delete outright when adopting an identity.
 *
 * An unscoped span with no download record behind it is what plain streaming left in the cache.
 * Nothing will read it again once the keyspace moves, so it is dropped rather than re-fetched; the
 * chapter simply streams once more if it is ever played.
 *
 * Two kinds are deliberately kept:
 * - **unscoped *and* indexed** — a real download, which gets re-keyed instead of deleted;
 * - **already scoped** — it belongs to a server the user also downloaded from, and deleting or
 *   re-keying it here is precisely the cross-server collision the identity exists to prevent.
 */
internal fun orphanedCacheKeys(
    cacheKeys: Collection<String>,
    indexedKeys: Set<String?>,
): List<String> = cacheKeys.filter { !DownloadCacheKeys.isScoped(it) && it !in indexedKeys }

/**
 * Cache keys left in the download cache by streaming, from before the two stores were split.
 *
 * Until 0.13.0 one cache held both kinds of audio, so an upgraded install arrives with a mix and no
 * record of which span is which. The download index is the record: a key it claims is a chapter
 * somebody asked for by name and must survive untouched, and everything else is what playback wrote
 * there on its way past. Those bytes are now unreachable — the streaming cache is a different
 * directory, and nothing will look for them in this one again — so they are dropped, and the chapter
 * simply streams once more if it is ever played.
 *
 * Unlike [orphanedCacheKeys] this does not care whether a key is scoped. Both keyspaces can be
 * present at once on a phone mid-way through the 0.8.0 identity migration, and a stranded stream
 * span is stranded in either.
 *
 * **Only ever call this with an index read that actually succeeded.** An empty [indexedKeys] because
 * the database could not be opened is indistinguishable here from an empty one because nothing is
 * downloaded, and acting on the first would delete every download on the phone.
 */
internal fun strandedStreamKeys(
    cacheKeys: Collection<String>,
    indexedKeys: Set<String?>,
): List<String> = cacheKeys.filter { it !in indexedKeys }

@OptIn(UnstableApi::class)
internal class DownloadIdentityMigration(
    private val store: RetainedRekeyStore = RetainedRekeyStore.InMemory(),
) {
    @Volatile
    var identity: String? = null
        private set

    /**
     * Rekeys not yet dispatched, by identity and request id.
     *
     * A rekey removes the old record before it queues the replacement, so a request whose add was
     * refused may no longer be in the index at all — this map is then the only thing that remembers
     * it is still owed a download. It is therefore kept across retries and persisted through [store]
     * before anything is dispatched, so the process being reaped in between cannot lose the chapter.
     * That same independence from the index is why a user's delete has to reach it through [forget]
     * and [forgetAll]: otherwise the next retry would queue the chapter they just removed.
     */
    private val undispatched: MutableMap<String, MutableMap<String, DownloadRequest>> =
        store.load().mapValuesTo(mutableMapOf()) { (_, requests) -> requests.toMutableMap() }

    /**
     * Guards [undispatched] and the withdrawal marks, and is held across each single dispatch.
     *
     * Deliberately not the monitor [migrate] holds for its whole run: a user's delete waits for at
     * most one dispatch to finish, never for an index read, so its own remove command is always sent
     * after any add the migration queued for the same chapter.
     */
    private val pendingLock = Any()

    /**
     * Downloads the user deleted whose index rows may still be standing.
     *
     * The remove goes through the download service asynchronously, so for a while after [forget] the
     * index still lists the record — and a migration that reads it then, whether it was already
     * running, merely scheduled, or not even asked for yet, would rekey it straight back. A mark is
     * kept until an index read no longer contains the row (the remove has landed) or the chapter is
     * downloaded again ([restore]). Not on a completed migration: a later one for another identity
     * could still read a row whose remove is queued. One short id per deletion is the whole cost.
     */
    private val withdrawn = mutableSetOf<String>()

    /** Every row the index held when the user deleted all downloads, except those [restored] since. */
    private var withdrewAll = false
    private val restored = mutableSetOf<String>()

    private fun isWithdrawn(id: String) = id in withdrawn || (withdrewAll && id !in restored)

    /**
     * Whether [undispatched] may differ from what [store] holds.
     *
     * The rules every path below keeps, so that a failed write, a refused service start or the
     * process dying at any point never loses a stranded chapter and never resurrects a deleted one:
     * - Nothing is dispatched while this is set. A rekey that is not on disk before its remove is
     *   sent is a chapter a reaped process can lose (#285).
     * - A user's delete or re-download commits the removal of its retained copy to disk *before*
     *   the service is told, and does nothing at all if that write fails. Memory can be ahead of the
     *   disk, and a stale entry on disk would be dispatched over the user's choice after a restart.
     * - The one direction the disk may lag on purpose is a rekey dispatched mid-run, whose removal
     *   is written once at the end: a restart before that write re-sends an identical request, a
     *   redundant re-download, never a lost or resurrected one. It still sets this flag, so a user
     *   action on that chapter in the meantime writes the disk first.
     */
    private var dirty = false

    private fun persist(): Boolean = store.save(undispatched).also { dirty = !it }

    /** Whether rekeys retained for [identity] are still waiting to be dispatched. */
    fun hasRetained(identity: String): Boolean =
        synchronized(pendingLock) { undispatched[identity]?.isNotEmpty() == true }

    /**
     * The user deletes [requestId] while signed in to the server filed under [identity]: never
     * rekey it again, whether it is retained from a failed dispatch or still standing in the index
     * the next migration reads. [remove] sends the actual remove command.
     *
     * Transactional with [remove]. Answers false — and neither changes anything nor calls [remove] —
     * when dropping the retained copy cannot be made durable; if [remove] throws, the retained copy
     * and the marks are put back and the exception propagates.
     *
     * Only [identity]'s retained copy is dropped. Request ids are `chapter:<id>`, which another
     * server reuses for a different chapter, and a retained rekey may be the only copy of that one —
     * deleting a chapter on one server must not erase another server's download. A null [identity]
     * (a server that never named itself) owns no retained rekeys, so none are touched.
     *
     * The withdrawal mark is not scoped: it guards a row in the one download index every server
     * shares, and the user's remove takes that row whichever server queued it. It is not persisted:
     * if the process dies before the service carries out the remove, the row survives and is
     * migrated like any other — the outcome of the delete never having happened, not a resurrection.
     *
     * [remove] runs under the dispatch lock, so a remove is never overtaken by an add a running
     * dispatch queues for the same chapter.
     */
    fun forget(requestId: String, identity: String?, remove: () -> Unit = {}): Boolean =
        synchronized(pendingLock) {
            val bucket = identity?.let { undispatched[it] }
            val previous = bucket?.remove(requestId)
            if ((previous != null || dirty) && !persist()) {
                if (previous != null) bucket[requestId] = previous
                return false
            }
            val wasWithdrawn = requestId in withdrawn
            val wasRestored = requestId in restored
            withdrawn += requestId
            restored -= requestId
            try {
                remove()
            } catch (e: Throwable) {
                if (previous != null) bucket[requestId] = previous
                if (!wasWithdrawn) withdrawn -= requestId
                if (wasRestored) restored += requestId
                if (previous != null) persist()
                throw e
            }
            true
        }

    /** The user deletes every download. Same contract and scoping as [forget], for all of them. */
    fun forgetAll(identity: String?, removeAll: () -> Unit = {}): Boolean =
        synchronized(pendingLock) {
            val previous = identity?.let { undispatched.remove(it) }
            if ((previous?.isNotEmpty() == true || dirty) && !persist()) {
                if (previous != null && identity != null) undispatched[identity] = previous
                return false
            }
            val marks = Triple(withdrawn.toSet(), restored.toSet(), withdrewAll)
            withdrawn.clear()
            restored.clear()
            withdrewAll = true
            try {
                removeAll()
            } catch (e: Throwable) {
                if (previous != null && identity != null) undispatched[identity] = previous
                withdrawn += marks.first
                restored += marks.second
                withdrewAll = marks.third
                if (previous?.isNotEmpty() == true) persist()
                throw e
            }
            true
        }

    /**
     * [requestId] is being downloaded again on purpose from the server filed under [identity]; [add]
     * sends the fresh request. A deletion recorded for it no longer applies, and neither does a rekey
     * retained for it there: dispatched later, that would remove the fresh download and queue the
     * old request's data in its place — a manual download turned automatic, or a replacement
     * stripped of its hash.
     *
     * Transactional with [add], like [forget]: false and no [add] when the removal cannot be made
     * durable; the retained copy back in place if [add] throws — a refused background start must not
     * leave the chapter with neither its fresh request nor its retained one.
     */
    fun restore(requestId: String, identity: String?, add: () -> Unit = {}): Boolean =
        synchronized(pendingLock) {
            val bucket = identity?.let { undispatched[it] }
            val previous = bucket?.remove(requestId)
            if ((previous != null || dirty) && !persist()) {
                if (previous != null) bucket[requestId] = previous
                return false
            }
            val wasWithdrawn = requestId in withdrawn
            val wasRestored = requestId in restored
            withdrawn -= requestId
            if (withdrewAll) restored += requestId
            try {
                add()
            } catch (e: Throwable) {
                if (previous != null) bucket[requestId] = previous
                if (wasWithdrawn) withdrawn += requestId
                if (!wasRestored) restored -= requestId
                if (previous != null) persist()
                throw e
            }
            true
        }

    @Synchronized
    fun migrate(
        incoming: String,
        index: DownloadIndex,
        cache: Cache,
        isCurrent: () -> Boolean = { true },
        ownsLegacyRequest: (DownloadRequest) -> Boolean = { true },
        rekey: (DownloadRequest) -> Unit,
    ): Boolean {
        if (!shouldAdoptIdentity(identity, incoming)) return true
        val requests = runCatching {
            index.getDownloads().use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.download.request)
                }
            }
        }.getOrNull() ?: return false
        if (!isCurrent()) return false

        val indexed = requests.mapTo(mutableSetOf()) { it.customCacheKey ?: it.uri.toString() }
        val stale = requests.filter { request ->
            val key = request.customCacheKey ?: return@filter false
            !DownloadCacheKeys.isScoped(key) && requests.filter { it.customCacheKey == key }.all(ownsLegacyRequest)
        }
        if (!isCurrent()) return false
        val ids = synchronized(pendingLock) {
            val standing = requests.mapTo(mutableSetOf()) { it.id }
            // A mark whose row this read no longer contains has done its job: the remove landed.
            // Only a mark made before the read can be judged by it, and everything here was.
            withdrawn.retainAll(standing)
            if (withdrewAll && standing.all { it in restored }) {
                withdrewAll = false
                restored.clear()
            }
            val pending = undispatched.getOrPut(incoming) { mutableMapOf() }
            var added = false
            stale.filterNot { isWithdrawn(it.id) }.forEach {
                if (pending.put(it.id, it) != it) added = true
            }
            // Durable before the first dispatch, or a reaped process could lose a removed record.
            // A write that failed last time is retried here even when nothing new was added.
            if ((added || dirty) && !persist()) return false
            pending.keys.toList()
        }
        if (!dispatch(incoming, ids, isCurrent, rekey)) return false
        if (!isCurrent()) return false
        runCatching {
            orphanedCacheKeys(cache.keys, indexed).forEach(cache::removeResource)
        }
        synchronized(pendingLock) {
            undispatched.remove(incoming)
            persist()
        }
        identity = incoming
        return true
    }

    /**
     * Dispatch only what is retained for [incoming], without reading the index, cleaning the cache
     * or adopting the identity.
     *
     * For a process that loaded retained rekeys from disk but has no fresh discovery to start a full
     * migration with: [incoming] is then the identity this address was verified under before, and
     * these requests already passed their ownership check when they were retained. Nothing filed
     * under any other identity is touched.
     */
    @Synchronized
    fun dispatchRetained(
        incoming: String,
        isCurrent: () -> Boolean = { true },
        rekey: (DownloadRequest) -> Unit,
    ): Boolean {
        val ids = synchronized(pendingLock) { undispatched[incoming]?.keys?.toList() } ?: return true
        if (!dispatch(incoming, ids, isCurrent, rekey)) return false
        synchronized(pendingLock) {
            if (undispatched[incoming]?.isEmpty() == true) undispatched.remove(incoming)
            persist()
        }
        return true
    }

    private fun dispatch(
        incoming: String,
        ids: List<String>,
        isCurrent: () -> Boolean,
        rekey: (DownloadRequest) -> Unit,
    ): Boolean {
        // Nothing destructive until what is retained is on disk.
        if (synchronized(pendingLock) { dirty && !persist() }) return false
        try {
            for (id in ids) {
                synchronized(pendingLock) {
                    // Re-read under the lock: the user may have deleted it since the list was taken.
                    val pending = undispatched[incoming] ?: return@synchronized
                    val request = pending[id] ?: return@synchronized
                    if (!isCurrent()) return false
                    if (runCatching { rekey(request) }.isFailure) return false
                    pending.remove(id)
                    dirty = true
                }
            }
            return true
        } finally {
            // One write for the run rather than one per chapter. A crash before it re-dispatches
            // what already went, which costs a redundant re-download, never a lost one.
            synchronized(pendingLock) { persist() }
        }
    }
}
