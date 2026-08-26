package dk.perspektiva.ttsroad.player

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer

/**
 * A real [Player] for tests, without a real player.
 *
 * Built on [SimpleBasePlayer] rather than hand-implementing the ~90-method [Player] interface,
 * which matters for more than convenience: the properties the UI mapping reads —
 * `hasNextMediaItem`, `bufferedPercentage`, `currentMediaItem` — are *derived* by Media3 from the
 * state below. A hand-rolled stub would let a test assert whatever the stub was told to say, which
 * would pass while the real player disagreed.
 */
class FakePlayer(
    private var playlist: List<MediaItemData> = emptyList(),
    private var currentIndex: Int = 0,
    private var positionMs: Long = 0L,
    private var bufferedMs: Long = 0L,
    private var playing: Boolean = false,
    private var speed: Float = 1f,
    private var error: PlaybackException? = null,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    override fun getState(): State = State.Builder()
        .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
        .setPlaylist(playlist)
        .setCurrentMediaItemIndex(currentIndex)
        .setContentPositionMs(positionMs)
        .setContentBufferedPositionMs(PositionSupplier.getConstant(bufferedMs))
        .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        // Media3 enforces that a player error is only legal in STATE_IDLE — a stopped player is
        // exactly what an error means. Deriving the state rather than hardcoding STATE_READY keeps
        // the fake inside the contract the real player obeys.
        .setPlaybackState(
            if (playlist.isEmpty() || error != null) Player.STATE_IDLE else Player.STATE_READY,
        )
        .setPlaybackParameters(PlaybackParameters(speed))
        .setPlayerError(error)
        .build()

    companion object {
        /**
         * One queue entry. The title goes on the [MediaItem]'s own metadata because that is what
         * `player.currentMediaItem.mediaMetadata` returns — [MediaItemData]'s metadata is a
         * different field and would leave the mapping reading nulls.
         */
        fun item(
            mediaId: String,
            title: String? = null,
            fictionTitle: String? = null,
            artworkUri: String? = null,
            durationMs: Long = 60_000L,
            /**
             * The `fiction_id` / `chapter_id` extras every real queue entry carries. Null builds an
             * item without them, which is what a fiction-level or malformed entry looks like — the
             * case the capture actions have to refuse rather than file against chapter 0.
             */
            extras: android.os.Bundle? = null,
        ): MediaItemData {
            val metadata = MediaMetadata.Builder()
                .setTitle(title)
                .setAlbumTitle(fictionTitle)
                .apply { artworkUri?.let { setArtworkUri(android.net.Uri.parse(it)) } }
                .apply { extras?.let { setExtras(it) } }
                .build()
            return MediaItemData.Builder(mediaId)
                .setMediaItem(
                    MediaItem.Builder()
                        .setMediaId(mediaId)
                        .setMediaMetadata(metadata)
                        .build(),
                )
                .setDurationUs(if (durationMs > 0) durationMs * 1_000L else androidx.media3.common.C.TIME_UNSET)
                .build()
        }
    }
}
