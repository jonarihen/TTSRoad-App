package dk.perspektiva.ttsroad.download

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
