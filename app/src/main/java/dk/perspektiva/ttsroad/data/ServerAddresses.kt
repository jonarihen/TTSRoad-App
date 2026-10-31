package dk.perspektiva.ttsroad.data

import dk.perspektiva.ttsroad.core.ServerUrls
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Other addresses the signed-in server answers on (#372) — its LAN address at home, its Tailscale
 * name away — so moving between them is a switch rather than a sign-out and sign-in.
 *
 * The bearer token still goes to exactly one origin at a time: [SessionState.serverUrl], the address
 * in use. Nothing here widens the per-origin rule the API client, the audio data source and the
 * cover loader enforce. What changes is that the address in use can be replaced without throwing
 * the token away, and only after the new address has been checked (see
 * [TtsRoadRepository.verifyServerAddress]):
 *
 * 1. its unauthenticated `/api/mobile/capabilities` reports the same server identity as the
 *    session's — the server's random, persisted `instance_id` when the session knows it (#437),
 *    otherwise its configured `base_url`, the identity the download cache already keys on;
 * 2. when it advertises `session_proof` and the session has a `device_id`, it proves it holds this
 *    session by answering a fresh challenge — still without the token (see `SessionProof.kt`);
 * 3. only then does the token go to it, on `/api/mobile/me`, and that must answer as the same
 *    account.
 *
 * A saved address is checked again every time it is switched to. A LAN address in particular is
 * not a stable name: `192.168.1.10` at home and `192.168.1.10` on a café's network are different
 * machines, and only the first should ever see the token.
 */

/** How many other addresses are kept. A home server has two or three; this is a bound, not a goal. */
internal const val MaxAlternateAddresses = 8

/**
 * Whether [first] and [second] are the same server address however they were spelled: host and
 * scheme case, an explicit default port and a trailing slash do not make a different address; a
 * different path prefix on the same origin does.
 */
internal fun sameServerAddress(first: String?, second: String?): Boolean {
    val a = canonicalAddress(first) ?: return false
    val b = canonicalAddress(second) ?: return false
    return a == b
}

private fun canonicalAddress(url: String?): okhttp3.HttpUrl? {
    val value = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return "${value.trimEnd('/')}/".toHttpUrlOrNull()
}

/**
 * Whether [requestUrl] is audio served by [serverUrl]: the same origin, and a path under the
 * server's own path prefix plus `audio/`. The one spelling of that check (#457).
 */
internal fun isAudioUnderServer(requestUrl: String, serverUrl: String?): Boolean {
    val request = requestUrl.toHttpUrlOrNull() ?: return false
    val server = canonicalAddress(serverUrl) ?: return false
    return ServerUrls.isSameOrigin(requestUrl, server.toString()) &&
        request.encodedPath.startsWith(server.encodedPath + "audio/")
}

/**
 * The other addresses once [next] is in use instead of [current]: the address being left joins the
 * front of the list, [next] leaves it, and the list stays within [MaxAlternateAddresses].
 */
internal fun alternatesAfterSwitch(current: String, alternates: List<String>, next: String): List<String> =
    distinctAddresses(listOf(current) + alternates)
        .filterNot { sameServerAddress(it, next) }
        .take(MaxAlternateAddresses)

/** The other addresses with [added] remembered, newest first. Never includes [current]. */
internal fun alternatesAfterRemember(current: String, alternates: List<String>, added: String): List<String> =
    distinctAddresses(listOf(added) + alternates)
        .filterNot { sameServerAddress(it, current) }
        .take(MaxAlternateAddresses)

/** The other addresses without [removed]. */
internal fun alternatesAfterForget(alternates: List<String>, removed: String): List<String> =
    alternates.filterNot { sameServerAddress(it, removed) }

private fun distinctAddresses(addresses: List<String>): List<String> {
    val kept = mutableListOf<String>()
    for (address in addresses) {
        if (address.isBlank()) continue
        if (kept.none { sameServerAddress(it, address) }) kept += address
    }
    return kept
}

/**
 * Whether [identity] (`DownloadCacheKeys.serverIdentity` form: authority plus path prefix) names
 * no particular machine: loopback or unspecified. The backend's default `BASE_URL` is
 * `http://localhost:8787`, so every instance left unconfigured reports the same identity, and
 * matching it would confirm nothing about which server answered.
 */
internal fun isPlaceholderIdentity(identity: String?): Boolean {
    val authority = identity?.trim()?.lowercase()?.substringBefore('/') ?: return true
    val host = if (authority.startsWith("[")) {
        authority.substringAfter('[').substringBefore(']')
    } else {
        authority.substringAfterLast('@').substringBefore(':')
    }
    return host.isEmpty() || host == "localhost" || host.endsWith(".localhost") ||
        host.startsWith("127.") || host == "0.0.0.0" || host == "::1" || host == "::" ||
        host == "0:0:0:0:0:0:0:1" || host == "0:0:0:0:0:0:0:0"
}

/** Stored as one string: addresses never contain a newline, and DataStore has no string-list type. */
internal fun encodeAlternates(alternates: List<String>): String = alternates.joinToString("\n")

internal fun decodeAlternates(stored: String?): List<String> =
    stored?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

/** The outcome of checking, and possibly adopting, another address for the signed-in server. */
sealed interface ServerAddressResult {
    /** The address passed both checks and was saved, or switched to. */
    data object Done : ServerAddressResult

    /**
     * The address was not adopted, and nothing about the session changed. [tokenSent] says whether
     * the check got as far as presenting the token there — false whenever the address failed the
     * unauthenticated identity check, which is the case the wording should reassure about.
     */
    data class Refused(val message: String, val tokenSent: Boolean = false) : ServerAddressResult
}

/** What to do with an address once it has passed. */
enum class ServerAddressAction { Remember, Use }

/** An automatic switch from [from] to [to] at [atMs] (#505), for the settings card to report. */
data class AddressFailover(val from: String, val to: String, val atMs: Long)

/** Minimum time between failover attempts, so a phone with no network at all does not keep probing. */
internal const val FailoverCooldownMs: Long = 60_000L

/**
 * Whether [failure] says the host could not be reached at all, as opposed to answering badly:
 * no DNS answer, refused, no route, or a connect (not read) timeout. These are the failures a
 * different network can fix; a TLS or HTTP error is the server's, and another address would not.
 */
internal fun isHostUnreachable(failure: java.io.IOException): Boolean = when (failure) {
    is java.net.UnknownHostException,
    is java.net.ConnectException,
    is java.net.NoRouteToHostException,
    -> true
    is java.net.SocketTimeoutException -> failure.message.orEmpty().contains("connect", ignoreCase = true)
    else -> false
}
