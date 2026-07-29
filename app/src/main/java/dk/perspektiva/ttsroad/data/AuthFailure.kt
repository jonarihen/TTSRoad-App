package dk.perspektiva.ttsroad.data

import com.squareup.moshi.Moshi

/**
 * Why the server stopped accepting a stored bearer token.
 *
 * Since the device-session work on the backend, a token is not simply valid or unknown: it carries
 * a 90-day sliding expiry and can be revoked from another device or from the web account page. The
 * server distinguishes the three cases in the `401` body so the app can say which one happened
 * instead of logging the user out without explanation.
 */
enum class AuthFailureReason(val wireName: String) {
    Expired("token_expired"),
    Revoked("token_revoked"),
    Invalid("invalid_token"),

    /**
     * A `401` with no reason we recognise — a server older than the device-session work, or a
     * proxy answering on its behalf. Treated exactly like the others: the credential is dead.
     */
    Unknown(""),
    ;

    companion object {
        fun fromWireName(value: String?): AuthFailureReason =
            entries.firstOrNull { it.wireName.isNotEmpty() && it.wireName == value } ?: Unknown
    }
}

/**
 * A dead session and the sentence explaining it.
 *
 * Whatever the reason, retrying cannot help — so every path that produces one of these drops the
 * token and falls back to the login screen. Only the message differs.
 */
data class SessionEndedNotice(
    val reason: AuthFailureReason,
    val message: String,
) {
    companion object {
        /** Used when the server said nothing usable; still true for all three reasons. */
        val Fallback = SessionEndedNotice(
            reason = AuthFailureReason.Unknown,
            message = "Session expired - sign in again",
        )
    }
}

/**
 * Read a `401` body into a notice.
 *
 * The backend answers an unusable token with
 * `{"detail": {"message": "...", "reason": "token_expired"}}`. Older builds — and FastAPI's own
 * default handlers — answer with a plain `{"detail": "..."}` string, which still gives a usable
 * message. Anything unparseable falls back rather than throwing: this runs on the sign-out path,
 * where failing to explain is much better than failing to sign out.
 */
fun parseSessionEndedNotice(body: String?): SessionEndedNotice {
    val detail = parseDetail(body)
    return when (detail) {
        is String -> detail.takeIf { it.isNotBlank() }
            ?.let { SessionEndedNotice(AuthFailureReason.Unknown, it) }
            ?: SessionEndedNotice.Fallback

        is Map<*, *> -> {
            val reason = AuthFailureReason.fromWireName(detail["reason"] as? String)
            val message = (detail["message"] as? String)?.takeIf { it.isNotBlank() }
            SessionEndedNotice(reason, message ?: SessionEndedNotice.Fallback.message)
        }

        else -> SessionEndedNotice.Fallback
    }
}

/** Pull a human-readable message out of FastAPI's `{"detail": ...}` error body. */
fun parseDetailMessage(body: String?): String? = when (val detail = parseDetail(body)) {
    is String -> detail
    is Map<*, *> -> detail["message"] as? String
    else -> null
}

private fun parseDetail(body: String?): Any? {
    if (body.isNullOrBlank()) return null
    return try {
        val parsed = errorBodyAdapter.fromJson(body) as? Map<*, *>
        parsed?.get("detail")
    } catch (_: Exception) {
        null
    }
}

// Error bodies are read on failure paths only, so one plain adapter is enough — no Kotlin
// reflection factory, because nothing here decodes into a data class.
private val errorBodyAdapter by lazy { Moshi.Builder().build().adapter(Any::class.java) }
