package dk.perspektiva.ttsroad.data

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
 * 1. its unauthenticated `/api/mobile/capabilities` reports the same server identity — the server's
 *    own configured `base_url`, the identity the download cache already keys on — as the session's;
 * 2. only then does the token go to it, on `/api/mobile/me`, and that must answer as the same
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
