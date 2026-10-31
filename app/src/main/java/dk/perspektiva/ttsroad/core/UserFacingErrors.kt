package dk.perspektiva.ttsroad.core

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import retrofit2.HttpException
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * A failure worded for the person holding the phone, plus the raw text for whoever helps them.
 *
 * [details] is null when the message already *is* the raw text — a server's own `detail` wording,
 * or a validation message this app wrote — because a "Details" link that repeats the sentence above
 * it is noise.
 */
data class UserFacingError(val message: String, val details: String? = null)

/**
 * Plain-language wording for a failure while talking to the server (#360).
 *
 * Exception text such as *Unable to resolve host "https"* or *Trust anchor for certification path
 * not found* is accurate and useless to most people; it says what the platform saw, not what to do.
 * This maps the failures a self-hosted server actually produces to a sentence that suggests a fix,
 * and keeps the original under [UserFacingError.details].
 *
 * Anything it does not recognise keeps its own message: the repository already turns a server's
 * JSON `detail` into an exception message, and that is better wording than any guess made here.
 * [fallback] is for a failure with no message at all.
 */
fun Throwable.toUserFacingError(fallback: String): UserFacingError {
    val raw = rawDescription()
    val plain = plainLanguageFor(this)
    return when {
        plain != null -> UserFacingError(plain, listOfNotNull(AdminHints[plain], raw).joinToString("\n\n"))
        !message.isNullOrBlank() -> UserFacingError(message!!.trim())
        else -> UserFacingError(fallback, raw)
    }
}

/** [toUserFacingError] for call sites that only have room for one line of text. */
fun Throwable.userMessage(fallback: String): String = toUserFacingError(fallback).message

internal const val CantFindServerMessage =
    "Can't find the server - check the address and that your phone is online."
internal const val CantReachServerMessage =
    "Can't reach the server - check your Wi-Fi or the address, and that the server is running."
internal const val TimeoutMessage =
    "The server took too long to answer - try again in a moment."
internal const val ConnectionLostMessage =
    "The connection to the server was lost - try again."
internal const val CleartextBlockedMessage =
    "This app only connects securely, and that address isn't. Ask whoever set up your server " +
        "for its https:// address."
internal const val UntrustedCertificateMessage =
    "This phone doesn't trust the server's security certificate. Ask whoever set up your server to fix it."
internal const val HostnameMismatchMessage =
    "The server's security certificate was issued for a different address than the one you entered. " +
        "Check the address, or ask whoever set up your server."
internal const val SecureConnectionMessage =
    "A secure connection to the server couldn't be set up - check the address, or ask whoever set up your server."
internal const val SessionExpiredMessage = "Your session has expired - sign in again."
internal const val ForbiddenMessage =
    "Your account isn't allowed to do that. Ask whoever set up your server if you should be."
internal const val NotFoundMessage =
    "The server doesn't have that. Check the address, or ask whoever set up your server."
internal const val ServerProblemMessage =
    "The server ran into a problem - try again in a moment."
internal const val UnreadableAnswerMessage =
    "The server sent an answer this app doesn't understand - ask whoever set up your server " +
        "whether it needs updating."

/** The technical advice for the person running the server, kept under Details (#489). */
private val AdminHints = mapOf(
    CleartextBlockedMessage to
        "For the admin: plain http:// is blocked. Put the server behind HTTPS (for example with " +
            "`tailscale cert`) and use its https:// address.",
    UntrustedCertificateMessage to
        "For the admin: a self-signed or private-CA certificate is not accepted. Use a certificate " +
            "from a public CA, such as Let's Encrypt or `tailscale cert`.",
    HostnameMismatchMessage to
        "For the admin: the certificate is valid but does not list this host name. Connect with a " +
            "name the certificate covers (its subject alternative names), or issue one that covers " +
            "this address.",
    SecureConnectionMessage to "For the admin: check the address and the server's HTTPS setup.",
    NotFoundMessage to
        "For the admin: the server may be running an older TTSRoad version, or the address may point " +
            "somewhere else.",
    UnreadableAnswerMessage to "For the admin: the app or the server may be out of date.",
)

private fun plainLanguageFor(failure: Throwable): String? {
    // A Retrofit failure carries the status; nothing beneath it is more specific.
    (failure as? HttpException)?.let { return plainLanguageForStatus(it.code()) }
    // TLS and cleartext failures are often wrapped (an SSLHandshakeException around the
    // CertPathValidatorException that says "Trust anchor"), so look at the whole chain before
    // falling back to the outermost type.
    val chain = generateSequence(failure) { it.cause }.take(8).toList()
    chain.forEach { cause ->
        when {
            cause is UnknownServiceException && cause.message.orEmpty().contains("CLEARTEXT", ignoreCase = true) ->
                return CleartextBlockedMessage

            cause is CertPathValidatorException ||
                cause is CertificateException ||
                cause.message.orEmpty().contains("Trust anchor", ignoreCase = true) ->
                return UntrustedCertificateMessage

            // OkHttp's "Hostname x not verified": the chain is fine, the name is not.
            cause is SSLPeerUnverifiedException -> return HostnameMismatchMessage
        }
    }
    chain.forEach { cause ->
        when (cause) {
            is UnknownHostException -> return CantFindServerMessage
            is ConnectException, is NoRouteToHostException -> return CantReachServerMessage
            is SocketTimeoutException -> return TimeoutMessage
            is SSLHandshakeException, is SSLException -> return SecureConnectionMessage
            is JsonDataException, is JsonEncodingException -> return UnreadableAnswerMessage
        }
    }
    return when {
        // OkHttp reports a call timeout as InterruptedIOException("timeout").
        failure is InterruptedIOException -> TimeoutMessage
        failure is SocketException || failure is EOFException -> ConnectionLostMessage
        // Only an IOException that reads like a dropped connection: this app also throws
        // IOException with wording of its own ("Could not open that book."), which must survive.
        failure is IOException && ConnectionDropped.containsMatchIn(failure.message.orEmpty()) ->
            ConnectionLostMessage
        else -> null
    }
}

private val ConnectionDropped = Regex(
    "unexpected end of stream|connection (reset|closed|abort)|stream was reset|broken pipe",
    RegexOption.IGNORE_CASE,
)

/** Wording for an HTTP status the server answered with, when it gave no `detail` of its own. */
fun plainLanguageForStatus(status: Int): String = when {
    status == 401 -> SessionExpiredMessage
    status == 403 -> ForbiddenMessage
    status == 404 -> NotFoundMessage
    status == 408 || status == 429 -> TimeoutMessage
    status in 500..599 -> ServerProblemMessage
    else -> "The server turned that request down - try again, or ask whoever set up your server."
}

private fun Throwable.rawDescription(): String {
    val chain = generateSequence(this) { it.cause }.take(4).toList()
    return chain.joinToString(separator = "\ncaused by ") { cause ->
        val name = cause.javaClass.simpleName.ifBlank { cause.javaClass.name }
        cause.message?.takeIf { it.isNotBlank() }?.let { "$name: $it" } ?: name
    }
}
