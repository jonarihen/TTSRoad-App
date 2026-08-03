package dk.perspektiva.ttsroad.data

import com.squareup.moshi.Moshi

/**
 * Why a stored bearer token stopped working.
 *
 * The server tells these apart in the 401 body, and the difference is the whole reason the login
 * screen can say something more useful than "signed out": a token that aged out after 90 days idle
 * is a shrug, while one revoked from another device is worth knowing about.
 */
enum class SessionEndReason {
    /** Unused for 90 days. Any authenticated request renews the expiry, so this means real disuse. */
    Expired,

    /** Signed out from the web console or from another device's session list. */
    Revoked,

    /** The server does not recognise the token at all — a reset database, or a mangled value. */
    Invalid,

    /** A 401 with no reason this build understands: an older server, a proxy, or an empty body. */
    Unknown,
}

/**
 * A finished session: why it ended, and the line to show on the login screen.
 *
 * [message] is the server's own wording when it sends one, because the backend is the only thing
 * that knows which of several sessions was revoked and when.
 */
data class SessionEnd(
    val reason: SessionEndReason,
    val message: String,
)

/** FastAPI's `{"detail": ...}` error body, which is either a string or an object. */
private data class ErrorDetail(val message: String?, val reason: String?)

// Plain Moshi: the error body is read as a generic map, so the Kotlin reflection factory the DTOs
// need is not involved here.
private val errorMoshi = Moshi.Builder().build()

private fun parseErrorDetail(body: String?): ErrorDetail {
    if (body.isNullOrBlank()) return ErrorDetail(message = null, reason = null)
    return try {
        val parsed = errorMoshi.adapter(Any::class.java).fromJson(body) as? Map<*, *>
        when (val detail = parsed?.get("detail")) {
            is String -> ErrorDetail(message = detail, reason = null)
            is Map<*, *> -> ErrorDetail(
                message = detail["message"] as? String,
                reason = detail["reason"] as? String,
            )

            else -> ErrorDetail(message = null, reason = null)
        }
    } catch (_: Exception) {
        ErrorDetail(message = null, reason = null)
    }
}

/** Pull a human-readable message out of FastAPI's `{"detail": ...}` error body. */
fun detailMessage(body: String?): String? = parseErrorDetail(body).message?.takeIf { it.isNotBlank() }

/**
 * Read the structured 401 the server sends when a bearer token can no longer be used:
 *
 * ```json
 * {"detail":{"message":"This device session expired. Sign in again.","reason":"token_expired"}}
 * ```
 *
 * Always returns a [SessionEnd] rather than null, because this is only called once a 401 has already
 * decided the session is over — an unreadable body costs the explanation, not the sign-out. The same
 * body shape comes back from bearer-authenticated `/audio/...` requests, so playback uses this too.
 */
fun parseSessionEnd(body: String?): SessionEnd {
    val detail = parseErrorDetail(body)
    val reason = when (detail.reason?.trim()?.lowercase()) {
        "token_expired" -> SessionEndReason.Expired
        "token_revoked" -> SessionEndReason.Revoked
        "invalid_token" -> SessionEndReason.Invalid
        else -> SessionEndReason.Unknown
    }
    return SessionEnd(
        reason = reason,
        message = detail.message?.takeIf { it.isNotBlank() } ?: defaultMessage(reason),
    )
}

private fun defaultMessage(reason: SessionEndReason): String = when (reason) {
    SessionEndReason.Expired -> "This device session expired - sign in again"
    SessionEndReason.Revoked -> "This device was signed out - sign in again"
    SessionEndReason.Invalid -> "The server no longer accepts this session - sign in again"
    SessionEndReason.Unknown -> "Session expired - sign in again"
}
