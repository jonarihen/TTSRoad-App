package dk.perspektiva.ttsroad.player

import androidx.media3.common.C
import androidx.media3.common.Player

/**
 * The [Player] → [PlayerUiState] mapping, pulled out of [PlaybackController] so it can be tested.
 *
 * This runs once a second for the whole time anything is playing, and it is the only thing standing
 * between what the player reports and what the screen says — a wrong branch here is a wrong scrubber
 * for an eight-hour night. The controller around it is a [androidx.media3.session.MediaController]
 * connection and a coroutine scope, neither of which can be meaningfully faked; this part is pure
 * and is where the decisions actually are.
 */

/**
 * Identity of the queue's *shape*, used to skip rebuilding an unchanged list on every tick.
 *
 * Count plus the first and last media ids: a queue only changes here by being replaced wholesale
 * (`setMediaItems`), so those three together move whenever the list does. Position and index are
 * deliberately absent — they change every second, and including them would defeat the cache.
 */
internal fun queueKeyOf(player: Player): String {
    val count = player.mediaItemCount
    if (count <= 0) return "0"
    return "$count:${player.getMediaItemAt(0).mediaId}:${player.getMediaItemAt(count - 1).mediaId}"
}

/**
 * The queue as the UI shows it.
 *
 * A chapter whose metadata carries no usable title falls back to its 1-based position rather than
 * rendering an empty row — a blank line in the chapter sheet is indistinguishable from a bug.
 */
internal fun buildQueue(player: Player): List<QueueItem> {
    val count = player.mediaItemCount
    if (count <= 0) return emptyList()
    return (0 until count).map { i ->
        val item = player.getMediaItemAt(i)
        QueueItem(
            mediaId = item.mediaId,
            title = item.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }
                ?: "Chapter ${i + 1}",
        )
    }
}

/**
 * Map the player's current state onto the UI state, given an already-resolved [queue].
 *
 * [queue] is passed in rather than derived so the caller can reuse a cached list; see [queueKeyOf].
 */
internal fun playerUiStateOf(player: Player, queue: List<QueueItem>): PlayerUiState {
    val metadata = player.currentMediaItem?.mediaMetadata
    // An unknown duration arrives as C.TIME_UNSET, which is Long.MIN_VALUE. Letting that reach the
    // scrubber would render a negative-width bar, so an unknown or non-positive duration is 0.
    val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
    return PlayerUiState(
        title = metadata?.title?.toString()?.takeIf { it.isNotBlank() } ?: "Nothing playing",
        fictionTitle = metadata?.albumTitle?.toString(),
        coverImageUrl = metadata?.artworkUri?.toString(),
        isPlaying = player.isPlaying,
        hasMedia = player.currentMediaItem != null,
        // Media3 can briefly report a negative position around a seek or a discontinuity.
        positionMs = player.currentPosition.coerceAtLeast(0L),
        durationMs = duration,
        bufferedPercentage = player.bufferedPercentage,
        speed = player.playbackParameters.speed,
        queue = queue,
        currentIndex = player.currentMediaItemIndex.coerceAtLeast(0),
        hasNext = player.hasNextMediaItem(),
        hasPrevious = player.hasPreviousMediaItem(),
        // The cause does not survive the binder, so the HTTP status is unavailable here; the
        // service reads it from the real exception and handles the 401 case there.
        error = player.playerError?.let { classifyPlaybackError(it.errorCode).message },
    )
}
