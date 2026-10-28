package dk.perspektiva.ttsroad.player

import android.os.Bundle
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource

private const val PlaybackMessageExtra = "ttsroad.playback_message"
private const val PlaybackHttpStatusExtra = "ttsroad.playback_http_status"

@androidx.annotation.OptIn(UnstableApi::class)
private fun playbackHttpStatus(error: PlaybackException): Int? =
    error.extras.getInt(PlaybackHttpStatusExtra).takeIf { it > 0 }
        ?: generateSequence(error.cause) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()?.responseCode

internal fun readablePlaybackException(error: PlaybackException): PlaybackException {
    val status = playbackHttpStatus(error)
    val message = classifyPlaybackError(error.errorCode, status).message
    return PlaybackException(message, null, error.errorCode, Bundle().apply {
        putString(PlaybackMessageExtra, message)
        status?.let { putInt(PlaybackHttpStatusExtra, it) }
    })
}

internal fun playbackErrorMessage(error: PlaybackException): String =
    error.extras.getString(PlaybackMessageExtra)
        ?: classifyPlaybackError(error.errorCode, playbackHttpStatus(error)).message

internal fun playbackErrorDetails(error: PlaybackException): String = buildString {
    append(error.errorCodeName)
    playbackHttpStatus(error)?.let { append("\nHTTP $it") }
}
