package dk.perspektiva.ttsroad.data

/** Where a sign-out attempt stopped (#480). */
sealed interface SignOutStep {
    /**
     * Not signed out: [positions] listening positions recorded on this phone could not be sent, and
     * signing out discards them. The user decides whether to go ahead anyway.
     */
    data class Unsynced(val positions: Int) : SignOutStep

    /** Signed out here. [revoked] is false when the server could not be told. */
    data class Done(val revoked: Boolean) : SignOutStep
}

/**
 * Sign out, sending the offline backlog first.
 *
 * The backlog is still cleared at sign-out, never kept for later: entries are owner-stamped, but a
 * sign-out is also how a phone is handed to someone else, and the cleanest guarantee that one
 * account's positions never flush into another's is that none survive it. So the flush comes
 * first, and [force] — "Sign out anyway" — is the explicit choice to lose what it could not send.
 *
 * [flush] answers how many positions are still unsent; [logout] whether the server revoked the token.
 */
suspend fun signOut(
    force: Boolean,
    flush: suspend () -> Int,
    logout: suspend () -> Boolean,
): SignOutStep {
    if (!force) {
        val unsent = flush()
        if (unsent > 0) return SignOutStep.Unsynced(unsent)
    }
    return SignOutStep.Done(revoked = logout())
}

/** The warning shown before discarding [positions] unsent listening positions. */
fun unsyncedSignOutMessage(positions: Int): String {
    val what = if (positions == 1) "1 listening position" else "$positions listening positions"
    val them = if (positions == 1) "it" else "them"
    return "$what recorded on this phone could not be sent to the server. Signing out now discards " +
        "$them. Cancel and sign out once the server can be reached to keep $them."
}
