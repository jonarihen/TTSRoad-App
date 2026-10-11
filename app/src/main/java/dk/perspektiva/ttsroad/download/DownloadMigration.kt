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

    private fun persist(): Boolean = store.save(undispatched)

    /**
     * The user deleted [requestId]: never rekey it again, whether it is retained from a failed
     * dispatch or still standing in the index the next migration reads.
     *
     * Call before sending the remove command, so any add a running dispatch has already queued is
     * followed by that remove rather than overtaking it.
     */
    fun forget(requestId: String) {
        synchronized(pendingLock) {
            var removed = false
            undispatched.values.forEach { if (it.remove(requestId) != null) removed = true }
            if (removed) persist()
            withdrawn += requestId
            restored -= requestId
        }
    }

    /** The user deleted every download. Same contract as [forget], for all of them. */
    fun forgetAll() {
        synchronized(pendingLock) {
            val hadPending = undispatched.values.any { it.isNotEmpty() }
            undispatched.clear()
            if (hadPending) persist()
            withdrawn.clear()
            restored.clear()
            withdrewAll = true
        }
    }

    /**
     * [requestId] is being downloaded again on purpose, so a deletion recorded for it no longer
     * applies. Call before sending the add, so the new record is migrated like any other.
     */
    fun restore(requestId: String) {
        synchronized(pendingLock) {
            withdrawn -= requestId
            if (withdrewAll) restored += requestId
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
            val pending = undispatched.getOrPut(incoming) { mutableMapOf() }
            var added = false
            stale.filterNot { isWithdrawn(it.id) }.forEach {
                if (pending.put(it.id, it) != it) added = true
            }
            // Durable before the first dispatch, or a reaped process could lose a removed record.
            if (added && !persist()) return false
            pending.keys.toList()
        }
        try {
            for (id in ids) {
                synchronized(pendingLock) {
                    // Re-read under the lock: the user may have deleted it since the list was taken.
                    val pending = undispatched[incoming] ?: return@synchronized
                    val request = pending[id] ?: return@synchronized
                    if (isWithdrawn(id)) return@synchronized
                    if (!isCurrent()) return false
                    if (runCatching { rekey(request) }.isFailure) return false
                    pending.remove(id)
                }
            }
        } finally {
            // One write for the run rather than one per chapter. A crash before it re-dispatches
            // what already went, which costs a redundant re-download, never a lost one.
            synchronized(pendingLock) { persist() }
        }
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
}
