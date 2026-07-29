package dk.perspektiva.ttsroad.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How long a cached answer is trusted before the next call goes back to the server. */
private const val RefreshIntervalMillis = 6L * 60 * 60 * 1000

/**
 * Capabilities per server, cached for the life of the process.
 *
 * Keyed by normalized base URL because the login screen probes whatever the user is typing while
 * a different server may already be signed in — the two must not overwrite each other's answer.
 *
 * A version change is only observable by asking again, so the [RefreshIntervalMillis] re-ask is
 * also what picks up a server that was upgraded under a long-lived session.
 */
class ServerCapabilityStore(
    private val repository: TtsRoadRepository,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val capabilities: ServerCapabilities, val fetchedAtMillis: Long)

    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Entry>()

    private val _current = MutableStateFlow(ServerCapabilities.Unknown)

    /** The signed-in server's capabilities, for screens that gate a feature on one. */
    val current: StateFlow<ServerCapabilities> = _current.asStateFlow()

    /**
     * Resolve the signed-in server and publish the result on [current].
     *
     * Throws whatever the request threw when the server could not be reached, so the caller can
     * decide whether that is worth showing; [current] keeps its previous value either way, which
     * is what stops a flaky network from switching features off mid-session.
     */
    suspend fun activate(baseUrl: String): ServerCapabilities =
        resolve(baseUrl).also { _current.value = it }

    /**
     * One-off lookup used while validating a URL. It fills the same cache but leaves [current]
     * alone — the URL in the login field is a candidate, not the session.
     */
    suspend fun probe(baseUrl: String): ServerCapabilities = resolve(baseUrl)

    /** Force the next lookup for every server to go back to the network. */
    suspend fun invalidate() = mutex.withLock { entries.clear() }

    /** Drop everything on sign-out, so the next server starts from the baseline. */
    suspend fun clear() {
        mutex.withLock { entries.clear() }
        _current.value = ServerCapabilities.Unknown
    }

    private suspend fun resolve(baseUrl: String): ServerCapabilities {
        val key = runCatching { normalizeBaseUrl(baseUrl) }.getOrElse {
            // Not a URL yet (no scheme). Nothing to ask, and nothing worth reporting as an error.
            return ServerCapabilities.Unknown
        }
        mutex.withLock { entries[key] }
            ?.takeIf { nowMillis() - it.fetchedAtMillis < RefreshIntervalMillis }
            ?.let { return it.capabilities }

        val fetched = repository.capabilities(key)
        mutex.withLock { entries[key] = Entry(fetched, nowMillis()) }
        return fetched
    }
}
