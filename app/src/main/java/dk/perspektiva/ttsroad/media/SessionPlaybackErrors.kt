package dk.perspektiva.ttsroad.media

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import dk.perspektiva.ttsroad.player.readablePlaybackException

/**
 * Presents player errors to the session in readable words — the same classification the phone UI
 * and the retry policy use — in the underlying error's place, so [MediaController]s, the platform
 * session and the head unit all show it without any command restriction.
 *
 * The readable error is cached per underlying error, so controllers never receive a different
 * exception for the same failure, and nothing from the raw cause chain (server address, token,
 * URL) ever crosses a binder. It clears with the underlying one — or when a different item plays:
 * an error the raw player has not cleared yet must not follow the user into another chapter.
 */
internal class SessionPlaybackErrors(player: Player) : ForwardingPlayer(player) {

    private var rawError: PlaybackException? = null
    private var readableError: PlaybackException? = null
    private var failedMediaId: String? = null

    override fun getPlayerError(): PlaybackException? {
        val error = super.getPlayerError() ?: return null
        val mediaId = getCurrentMediaItem()?.mediaId
        return if (mediaId == failedMediaId) readableFor(error, mediaId) else null
    }

    override fun addListener(listener: Player.Listener) {
        super.addListener(ListenerPresenter(listener))
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(ListenerPresenter(listener))
    }

    private fun readableFor(error: PlaybackException, mediaId: String?): PlaybackException {
        if (error !== rawError || mediaId != failedMediaId) {
            rawError = error
            failedMediaId = mediaId
            readableError = readablePlaybackException(error)
        }
        return readableError!!
    }

    private inner class ListenerPresenter(
        private val delegate: Player.Listener,
    ) : Player.Listener by delegate {

        override fun onEvents(player: Player, events: Player.Events) {
            delegate.onEvents(this@SessionPlaybackErrors, events)
        }

        override fun onPlayerError(error: PlaybackException) {
            delegate.onPlayerError(readableFor(error, currentMediaItem?.mediaId))
        }

        override fun onPlayerErrorChanged(error: PlaybackException?) {
            if (error == null) {
                rawError = null
                readableError = null
                failedMediaId = null
                delegate.onPlayerErrorChanged(null)
            } else {
                delegate.onPlayerErrorChanged(readableFor(error, currentMediaItem?.mediaId))
            }
        }
    }
}
