package dk.perspektiva.ttsroad.media

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
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
        return present(error, getCurrentMediaItem()?.mediaId)
    }

    private val presenters = HashMap<Player.Listener, Player.Listener>()

    override fun addListener(listener: Player.Listener) {
        super.addListener(presenters.getOrPut(listener) { presenting(listener) })
    }

    override fun removeListener(listener: Player.Listener) {
        presenters.remove(listener)?.let { super.removeListener(it) }
    }

    private fun present(error: PlaybackException, mediaId: String?): PlaybackException? {
        if (error !== rawError) {
            rawError = error
            failedMediaId = mediaId
            readableError = readablePlaybackException(error)
        }
        if (mediaId != failedMediaId) readableError = null
        return readableError
    }

    // A proxy, not Kotlin delegation: `by` skips Java default methods, and every Player.Listener
    // callback is one, so a delegating class would silently drop all but the two overridden here.
    private fun presenting(delegate: Player.Listener): Player.Listener = Proxy.newProxyInstance(
        Player.Listener::class.java.classLoader, arrayOf(Player.Listener::class.java),
    ) { _, method, args ->
        val arguments = args ?: emptyArray()
        when (method.name) {
            "onPlayerError" -> arguments[0] = present(arguments[0] as PlaybackException, currentMediaItem?.mediaId)
                ?: readablePlaybackException(arguments[0] as PlaybackException)
            "onPlayerErrorChanged" -> arguments[0] = (arguments[0] as PlaybackException?)?.let {
                present(it, currentMediaItem?.mediaId)
            }.also { if (it == null) { rawError = null; readableError = null; failedMediaId = null } }
        }
        try {
            method.invoke(delegate, *arguments)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    } as Player.Listener
}
