package dk.perspektiva.ttsroad.player

import androidx.media3.common.PlaybackException

/**
 * What to do about a playback failure, and what to tell the user.
 *
 * Streaming from a self-hosted server fails in ways a commercial audiobook app does not: the VPN
 * drops on the drive, the home connection blips, a tunnel eats the signal for ten seconds. Most of
 * those heal on their own, so the interesting distinction is not "what broke" but "is retrying
 * worth anything".
 */
sealed interface PlaybackFailure {
    /** The message to show if this failure is surfaced to the user. */
    val message: String

    /** Heals on its own: retry with backoff before saying anything. */
    data class Transient(override val message: String) : PlaybackFailure

    /**
     * The bearer token is no longer accepted. Retrying can never succeed, and the fix is the same
     * as for a revoked token on an API call: drop the session and go back to the login screen.
     */
    data object Unauthorized : PlaybackFailure {
        override val message: String = "Session expired - sign in again"
    }

    /** Retrying will not help, but the user might: a missing file, a decoder problem. */
    data class Permanent(override val message: String) : PlaybackFailure
}

/**
 * Classify an ExoPlayer error.
 *
 * [httpStatus] is only available service-side: a [PlaybackException] handed to a controller across
 * the binder keeps its `errorCode` but loses its cause, so the UI calls this with null and gets a
 * usable message without the 401 special case. The service, which holds the real exception, gets
 * the full answer.
 */
fun classifyPlaybackError(errorCode: Int, httpStatus: Int? = null): PlaybackFailure = when {
    httpStatus == 401 || httpStatus == 403 -> PlaybackFailure.Unauthorized

    // 5xx is the server restarting or briefly overloaded; 408/429 are explicitly "try again".
    httpStatus != null && (httpStatus >= 500 || httpStatus == 408 || httpStatus == 429) ->
        PlaybackFailure.Transient("Server not responding - retrying")

    httpStatus != null ->
        PlaybackFailure.Permanent("Server rejected the audio request (HTTP $httpStatus)")

    errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
        PlaybackFailure.Transient("Lost the connection to the server - retrying")

    // Unspecified IO covers a socket dying mid-stream, which is exactly the tunnel case.
    errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ->
        PlaybackFailure.Transient("Playback was interrupted - retrying")

    // Reported without a cause when a controller relays it; treat as worth one look.
    errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
        PlaybackFailure.Transient("Server not responding - retrying")

    errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
        PlaybackFailure.Permanent("This chapter's audio is missing on the server")

    errorCode == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ->
        PlaybackFailure.Permanent("Plain HTTP is blocked in this build - use https://")

    errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
        PlaybackFailure.Permanent("Not allowed to read this chapter's audio")

    else -> PlaybackFailure.Permanent("Could not play this chapter")
}

/**
 * Backoff for automatic retries: 2s, 5s, 15s, then give up and leave it to the user.
 *
 * Returns null once [attempt] is past the end, which is the signal to stop retrying. Short first,
 * because the common case is a handover that is already over by the time we ask again.
 */
fun retryDelayMs(attempt: Int): Long? = RetryDelaysMs.getOrNull(attempt - 1)

private val RetryDelaysMs = listOf(2_000L, 5_000L, 15_000L)

/** How many automatic attempts happen before the error is left on screen for the user. */
val MaxAutomaticRetries: Int = RetryDelaysMs.size
