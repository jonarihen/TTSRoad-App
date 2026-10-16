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
     *
     * Empty, and not to be trusted as empty, until [loaded]: see [ensureLoaded].
     */
    private val undispatched: MutableMap<String, MutableMap<String, DownloadRequest>> = mutableMapOf()

    /**
     * Whether [undispatched] holds what the file held. A file that exists but could not be read may
     * hold the only copy of a stranded chapter, so until a load succeeds nothing is migrated,
     * dispatched or deleted against an assumed-empty map, and the file is never written over.
     */
    private var loaded = false

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

    init {
        synchronized(pendingLock) { ensureLoaded() }
    }

    /**
     * Load the retained-rekey file if that has not succeeded yet, retrying on every call that needs
     * it. Answers whether [undispatched] is now authoritative. Called under [pendingLock].
     */
    private fun ensureLoaded(): Boolean {
        if (loaded) return true
        val disk = store.load() ?: return false
        // Nothing reaches memory before the load: every path that adds or removes waits for it.
        disk.forEach { (identity, requests) -> undispatched[identity] = requests.toMutableMap() }
        loaded = true
        return true
    }

    /*
     * How memory and the retained-rekey file are kept consistent, so that a failed write, a refused
     * service start or the process dying at any point never loses a stranded chapter and never
     * resurrects a deleted one:
     *
     * 1. A retained entry is never dispatched until it is on disk ([unsavedAdditions] blocks every
     *    dispatch until a write succeeds). A rekey sends the remove first, so an entry dispatched
     *    before it was durable is a chapter a reaped process can lose (#285).
     * 2. Dispatch reconciles every entry against the live index first ([reconcile]). An entry whose
     *    id now has a row filed under this identity — a fresh download, or the rekey's own
     *    replacement — is superseded and dropped, not dispatched. So an entry the disk still holds
     *    after its removal failed to be written is harmless after a restart, and nothing has to be
     *    deleted from disk *before* the command that makes it obsolete.
     * 3. A re-download ([restore]) therefore sends its add first and only then drops the retained
     *    copy. A refused add changes nothing; a failed write afterwards leaves an entry rule 2 drops.
     * 4. A deletion ([forget], [forgetAll]) is the one case rule 2 cannot settle — the user's remove
     *    leaves no row, which is exactly what a stranded entry looks like — so its removal is written
     *    *before* the command, and nothing happens if that write fails. A refused remove puts the
     *    entry back in memory; if the process dies before that is written, what is lost is the
     *    chapter the user was deleting.
     * 5. Ids whose removal may not be on disk yet ([unsavedRemovals]) make a deletion of that id
     *    write first too, so a stale entry is never left for rule 2 to misread as stranded.
     */

    /** Memory holds entries the file may not: nothing is dispatched until a write succeeds. */
    private var unsavedAdditions = false

    /** Ids dropped from memory whose removal may not be on disk yet. */
    private val unsavedRemovals = mutableSetOf<String>()

    private fun persist(): Boolean = loaded && store.save(undispatched).also { saved ->
        if (saved) {
            unsavedAdditions = false
            unsavedRemovals.clear()
        }
    }

    /**
     * Whether a rekey retained for a server other than [identity] still owes [requestId] its row —
     * in which case a fresh download on [identity]'s server must not take that id (#316). Answers
     * false while the file cannot be read: the bare id is then the one downloads used before.
     */
    fun isRetainedElsewhere(requestId: String, identity: String?): Boolean =
        synchronized(pendingLock) {
            ensureLoaded() && undispatched.any { (owner, requests) -> owner != identity && requestId in requests }
        }

    /** Whether rekeys retained for [identity] are still waiting to be dispatched. */
    fun hasRetained(identity: String): Boolean =
        synchronized(pendingLock) {
            // Unknown counts as retained, so the caller's retry path keeps trying the load.
            !ensureLoaded() || undispatched[identity]?.isNotEmpty() == true
        }

    /**
     * The user deletes [requestId] while signed in to the server filed under [identity]: never
     * rekey it again, whether it is retained from a failed dispatch or still standing in the index
     * the next migration reads. [remove] sends the actual remove command.
     *
     * Answers false — having changed nothing and not called [remove] — when the removal of a
     * retained copy cannot be written first (rule 4). If [remove] throws, the retained copy and the
     * marks are put back and the exception propagates.
     *
     * Only [identity]'s retained copy is dropped. Request ids are `chapter:<id>`, which another
     * server reuses for a different chapter, and a retained rekey may be the only copy of that one —
     * deleting a chapter on one server must not erase another server's download. A null [identity]
     * (a server that never named itself) owns no retained rekeys, so none are touched.
     *
     * Also answers false, having done nothing, while the retained-rekey file exists but cannot be
     * read: it may hold this very chapter, and a delete that left it there would be undone later.
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
            if (identity != null && !ensureLoaded()) return false
            val bucket = identity?.let { undispatched[it] }
            val previous = bucket?.remove(requestId)
            if ((previous != null || requestId in unsavedRemovals) && !persist()) {
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
                if (previous != null) {
                    bucket[requestId] = previous
                    unsavedAdditions = true
                    persist()
                }
                if (!wasWithdrawn) withdrawn -= requestId
                if (wasRestored) restored += requestId
                throw e
            }
            true
        }

    /**
     * The user deletes every download. Same contract as [forget], for all of them — but not scoped:
     * the remove-all command and the cache sweep that follow it take every server's downloads, so
     * every server's retained rekeys go too. One left behind for a server the user is not signed in
     * to would re-download, on the next visit there, a chapter whose data was just deleted.
     */
    fun forgetAll(removeAll: () -> Unit = {}): Boolean =
        synchronized(pendingLock) {
            if (!ensureLoaded()) return false
            val previous = undispatched.toMap()
            undispatched.clear()
            if ((previous.values.any { it.isNotEmpty() } || unsavedRemovals.isNotEmpty()) && !persist()) {
                undispatched += previous
                return false
            }
            val marks = Triple(withdrawn.toSet(), restored.toSet(), withdrewAll)
            withdrawn.clear()
            restored.clear()
            withdrewAll = true
            try {
                removeAll()
            } catch (e: Throwable) {
                if (previous.isNotEmpty()) {
                    undispatched += previous
                    unsavedAdditions = true
                    persist()
                }
                withdrawn += marks.first
                restored += marks.second
                withdrewAll = marks.third
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
     * The add goes first and the retained copy is dropped only once it was accepted (rule 3): a
     * refused add, such as a background start the system turns down, changes nothing at all. If
     * the write afterwards fails, the entry left on disk is superseded by the fresh row and dropped
     * when it is next reconciled.
     */
    fun restore(requestId: String, identity: String?, add: () -> Unit = {}) {
        synchronized(pendingLock) {
            add()
            withdrawn -= requestId
            if (withdrewAll) restored += requestId
            if (identity != null && undispatched[identity]?.remove(requestId) != null) {
                unsavedRemovals += requestId
                persist()
            }
        }
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
            // Rekeying against a journal that could not be read could strand a chapter whose only
            // record is in that file, and the write below would then overwrite it.
            if (!ensureLoaded()) return false
            val pending = undispatched.getOrPut(incoming) { mutableMapOf() }
            var added = false
            stale.filterNot { isWithdrawn(it.id) }.forEach {
                if (pending.put(it.id, it) != it) added = true
            }
            // Durable before the first dispatch, or a reaped process could lose a removed record.
            // A write that failed last time is retried here even when nothing new was added.
            if (added) unsavedAdditions = true
            if (unsavedAdditions && !persist()) return false
            pending.keys.toList()
        }
        if (!dispatch(incoming, index, ids, isCurrent, rekey)) return false
        if (!isCurrent()) return false
        runCatching {
            orphanedCacheKeys(cache.keys, indexed).forEach(cache::removeResource)
        }
        synchronized(pendingLock) {
            // Anything still here is held behind another server's row; see [reconcile].
            if (undispatched[incoming]?.isEmpty() == true) undispatched.remove(incoming)
            persist()
        }
        identity = incoming
        return true
    }

    /**
     * Dispatch only what is retained for [incoming], without a full index read, cleaning the cache
     * or adopting the identity. Each entry is still reconciled against its own index row.
     *
     * For a process that loaded retained rekeys from disk but has no fresh discovery to start a full
     * migration with: [incoming] is then the identity this address was verified under before, and
     * these requests already passed their ownership check when they were retained. Nothing filed
     * under any other identity is touched.
     */
    @Synchronized
    fun dispatchRetained(
        incoming: String,
        index: DownloadIndex,
        isCurrent: () -> Boolean = { true },
        rekey: (DownloadRequest) -> Unit,
    ): Boolean {
        val ids = synchronized(pendingLock) {
            if (!ensureLoaded()) return false
            undispatched[incoming]?.keys?.toList()
        } ?: return true
        if (!dispatch(incoming, index, ids, isCurrent, rekey)) return false
        synchronized(pendingLock) {
            if (undispatched[incoming]?.isEmpty() == true) undispatched.remove(incoming)
            persist()
        }
        return true
    }

    private enum class Reconciled { Dispatch, Superseded, Held }

    /**
     * What the live index says about retained [request] (rule 2):
     * - no row, or the very row the request was retained from — still owed its rekey;
     * - a row filed under [incoming] that is not that request — a fresh download or the rekey's
     *   own replacement already stands there, and dispatching would overwrite it: drop the entry;
     * - any other row — another server's download shares the `chapter:<id>`. Dispatching would
     *   overwrite it and dropping would lose this server's only copy, so the entry is held.
     */
    private fun reconcile(incoming: String, request: DownloadRequest, row: DownloadRequest?): Reconciled = when {
        row == null || row == request -> Reconciled.Dispatch
        row.customCacheKey?.startsWith("$incoming ") == true -> Reconciled.Superseded
        else -> Reconciled.Held
    }

    private fun dispatch(
        incoming: String,
        index: DownloadIndex,
        ids: List<String>,
        isCurrent: () -> Boolean,
        rekey: (DownloadRequest) -> Unit,
    ): Boolean {
        // Nothing destructive until what is retained is on disk (rule 1).
        if (synchronized(pendingLock) { unsavedAdditions && !persist() }) return false
        try {
            for (id in ids) {
                synchronized(pendingLock) {
                    // Re-read under the lock: the user may have deleted it since the list was taken.
                    val pending = undispatched[incoming] ?: return@synchronized
                    val request = pending[id] ?: return@synchronized
                    if (!isCurrent()) return false
                    val row = runCatching { index.getDownload(id)?.request }.getOrElse { return false }
                    when (reconcile(incoming, request, row)) {
                        Reconciled.Superseded -> {
                            pending.remove(id)
                            unsavedRemovals += id
                            return@synchronized
                        }
                        Reconciled.Held -> return@synchronized
                        Reconciled.Dispatch -> Unit
                    }
                    if (runCatching { rekey(request) }.isFailure) return false
                    pending.remove(id)
                    unsavedRemovals += id
                }
            }
            return true
        } finally {
            // One write for the run rather than one per chapter. A restart before it lands finds
            // each dispatched entry superseded by its replacement row, or re-sends it if the
            // replacement never landed — never a lost chapter, never a duplicate over a fresh one.
            synchronized(pendingLock) { persist() }
        }
    }
}
