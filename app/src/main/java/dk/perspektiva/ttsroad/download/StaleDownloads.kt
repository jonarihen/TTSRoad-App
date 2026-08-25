package dk.perspektiva.ttsroad.download

import dk.perspektiva.ttsroad.data.AudioHash

/**
 * What a freshness check concluded about one fiction's downloads (#109).
 *
 * @property stale Chapter ids whose audio on disk is provably not the audio the server has now.
 * @property adopt Hashes to record for downloads this device has never seen a hash for.
 */
data class StaleDownloadCheck(
    val stale: Set<Int> = emptySet(),
    val adopt: Map<Int, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = stale.isEmpty() && adopt.isEmpty()
}

/**
 * Compare what is on disk against what the server says the bytes hash to.
 *
 * Chapter audio is not immutable. `retry`, `reconvert-all`, `retag` and `stale-text/reconvert` all
 * rewrite an MP3 in place, and **the URL does not change when they do** — so a chapter already in
 * the download store keeps playing the old narration, indefinitely, and nothing tells anyone. That
 * is the failure worth catching: it produces wrong content rather than an error.
 *
 * Three rules, and each of the last two exists to stop this making things worse than it found them:
 *
 * 1. **A recorded hash that differs from the server's is stale.** The only positive case.
 * 2. **A null server hash is never stale.** The backend sends null for chapters converted before
 *    hashing shipped and for rows its backfill has not reached, and its own docstring says a null
 *    means *unknown*. Reading it as *changed* would mark an entire library stale the first time it
 *    met a server mid-backfill.
 * 3. **A download with no recorded hash is adopted, not re-fetched.** Every download made before
 *    this shipped is in that state. Guessing "stale" there would re-download everything on upgrade,
 *    over mobile data, to replace files that are almost certainly correct — the exact opposite of
 *    what an offline feature is for. Adopting means the *next* re-convert is caught, which is the
 *    point.
 *
 * [downloaded] is the set of chapter ids actually on disk. A chapter that is not downloaded cannot
 * be stale, and recording a hash for one would leave a stale entry behind after a delete.
 */
fun staleDownloadCheck(
    downloaded: Set<Int>,
    recorded: Map<Int, String>,
    server: List<AudioHash>,
): StaleDownloadCheck {
    val stale = mutableSetOf<Int>()
    val adopt = mutableMapOf<Int, String>()
    for (entry in server) {
        val chapterId = entry.chapterId
        if (chapterId !in downloaded) continue
        val serverHash = entry.audioSha256?.takeIf { it.isNotBlank() } ?: continue
        val recordedHash = recorded[chapterId]
        when {
            recordedHash == null -> adopt[chapterId] = serverHash
            recordedHash != serverHash -> stale += chapterId
        }
    }
    return StaleDownloadCheck(stale = stale, adopt = adopt)
}

/**
 * Drop recorded hashes for chapters that are no longer downloaded.
 *
 * Without this the record grows for the life of the install and, worse, a chapter deleted and later
 * downloaded again would be compared against a hash from the previous copy — which is a hash for
 * bytes this device no longer has, and would report stale or fresh on no evidence.
 */
fun pruneRecordedHashes(recorded: Map<Int, String>, downloaded: Set<Int>): Map<Int, String> =
    recorded.filterKeys { it in downloaded }
