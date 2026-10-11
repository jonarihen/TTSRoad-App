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
internal class DownloadIdentityMigration {
    @Volatile
    var identity: String? = null
        private set

    /**
     * Rekeys whose dispatch failed, by identity and request id.
     *
     * A rekey removes the old record before it queues the replacement, so a request whose add was
     * refused may no longer be in the index at all — this map is then the only thing that remembers
     * it is still owed a download, and it is kept across retries for exactly that reason. That same
     * independence from the index is why a user's delete has to reach it through [forget] and
     * [forgetAll]: otherwise the next retry would queue the chapter they just removed.
     */
    private val undispatched = mutableMapOf<String, MutableMap<String, DownloadRequest>>()

    /**
     * Guards [undispatched] and the withdrawal marks, and is held across each single dispatch.
     *
     * Deliberately not the monitor [migrate] holds for its whole run: a user's delete waits for at
     * most one dispatch to finish, never for an index read, so its own remove command is always sent
     * after any add the migration queued for the same chapter.
     */
    private val pendingLock = Any()

    /** Whether a [migrate] run is between its index read and its last dispatch. */
    private var running = false

    /** Ids the user removed while a run was in flight; its index snapshot may predate the removal. */
    private val withdrawn = mutableSetOf<String>()
    private var withdrewAll = false

    /**
     * The user deleted [requestId]: never rekey it again, whether it is retained from a failed
     * dispatch or sits in the index snapshot of a run that is in flight right now.
     *
     * Call before sending the remove command, so any add a running dispatch has already queued is
     * followed by that remove rather than overtaking it.
     */
    fun forget(requestId: String) {
        synchronized(pendingLock) {
            undispatched.values.forEach { it.remove(requestId) }
            if (running) withdrawn += requestId
        }
    }

    /** The user deleted every download. Same contract as [forget], for all of them. */
    fun forgetAll() {
        synchronized(pendingLock) {
            undispatched.clear()
            if (running) withdrewAll = true
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
        synchronized(pendingLock) {
            running = true
            withdrawn.clear()
            withdrewAll = false
        }
        try {
            return migrateRunning(incoming, index, cache, isCurrent, ownsLegacyRequest, rekey)
        } finally {
            synchronized(pendingLock) {
                running = false
                withdrawn.clear()
                withdrewAll = false
            }
        }
    }

    private fun migrateRunning(
        incoming: String,
        index: DownloadIndex,
        cache: Cache,
        isCurrent: () -> Boolean,
        ownsLegacyRequest: (DownloadRequest) -> Boolean,
        rekey: (DownloadRequest) -> Unit,
    ): Boolean {
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
            val pending = undispatched.getOrPut(incoming) { mutableMapOf() }
            if (!withdrewAll) stale.filter { it.id !in withdrawn }.forEach { pending[it.id] = it }
            pending.keys.toList()
        }
        for (id in ids) {
            synchronized(pendingLock) {
                // Re-read under the lock: the user may have deleted it since the list was taken.
                val pending = undispatched[incoming] ?: return@synchronized
                val request = pending[id] ?: return@synchronized
                if (!isCurrent()) return false
                if (runCatching { rekey(request) }.isFailure) return false
                pending.remove(id)
            }
        }
        if (!isCurrent()) return false
        runCatching {
            orphanedCacheKeys(cache.keys, indexed).forEach(cache::removeResource)
        }
        synchronized(pendingLock) { undispatched.remove(incoming) }
        identity = incoming
        return true
    }
}
