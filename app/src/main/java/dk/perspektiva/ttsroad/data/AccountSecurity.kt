package dk.perspektiva.ttsroad.data

/**
 * The outcomes of the account-security actions (#118).
 *
 * A sealed result rather than an exception, and for the same reason `FictionAddResult` is one: the
 * server's `400` here is not a fault, it is an *answer* — a wrong current password, a new one the
 * strength policy refuses, a TOTP code that did not match — and its `detail` is written to be shown
 * to the person who typed the wrong thing. Turning that into a thrown exception would either lose
 * the sentence or make every call site unwrap one.
 *
 * [Unsupported] is the third state and is not a failure either: it means the server predates these
 * routes. The UI is gated on the capability, so seeing it means the session outlived a downgrade.
 */
sealed interface AccountActionResult<out T> {
    data class Done<T>(val value: T) : AccountActionResult<T>

    /** The server said no, and said why. [message] is meant to be shown verbatim. */
    data class Refused(val message: String) : AccountActionResult<Nothing>

    data object Unsupported : AccountActionResult<Nothing>
}

/**
 * Whether [codes] should still be on screen.
 *
 * Recovery codes are shown **once**: the server hashes them before storing them, so a client that
 * lets this list go without the user writing it down has destroyed it. That is why the sheet
 * showing them has no dismiss-on-tap-outside and asks for an explicit acknowledgement.
 */
fun hasUnsavedRecoveryCodes(codes: List<String>?): Boolean = !codes.isNullOrEmpty()
