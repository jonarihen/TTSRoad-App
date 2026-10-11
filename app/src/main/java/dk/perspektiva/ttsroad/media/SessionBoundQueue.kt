package dk.perspektiva.ttsroad.media

import androidx.media3.common.MediaItem
import android.os.Bundle
import dk.perspektiva.ttsroad.notificationSessionKey
import androidx.media3.common.Player
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.TokenStore
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object SessionBoundQueue {
    internal class Request(
        val owner: SessionState,
        val items: List<MediaItem>,
        val index: Int,
        val positionMs: Long,
    )

    private val pending = mutableMapOf<String, Request>()

    @Synchronized
    fun issue(owner: SessionState, items: List<MediaItem>, index: Int, positionMs: Long): String =
        UUID.randomUUID().toString().also { key ->
            val tagged = items.map { item ->
                val extras = Bundle(item.mediaMetadata.extras ?: Bundle.EMPTY).apply {
                    putString("queue_session_key", notificationSessionKey(owner))
                }
                item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
            }
            pending[key] = Request(owner, tagged, index, positionMs)
        }

    @Synchronized
    fun discard(ticket: String) {
        pending.remove(ticket)
    }

    @Synchronized
    private fun held(ticket: String): Request? = pending[ticket]

    @Synchronized
    private fun consume(ticket: String, request: Request): Boolean = pending.remove(ticket, request)

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    suspend fun install(ticket: String?, player: Player, tokens: TokenStore, installed: (SessionState) -> Unit): SessionResult {
        val key = ticket ?: return SessionResult(SessionError.ERROR_BAD_VALUE)
        val request = held(key) ?: return SessionResult(SessionError.ERROR_BAD_VALUE)
        currentCoroutineContext().ensureActive()
        return tokens.withCurrentSession(request.owner) {
            if (!consume(key, request)) return@withCurrentSession SessionResult(SessionError.ERROR_BAD_VALUE)
            installed(request.owner)
            player.setMediaItems(request.items, request.index, request.positionMs)
            player.prepare()
            player.play()
            val matches = request.items.size == player.mediaItemCount && request.items.indices.all {
                player.getMediaItemAt(it).mediaId == request.items[it].mediaId
            } && player.currentMediaItem?.mediaId == request.items[request.index].mediaId && player.playWhenReady
            SessionResult(if (matches) SessionResult.RESULT_SUCCESS else SessionError.ERROR_INVALID_STATE)
        } ?: SessionResult(SessionError.ERROR_PERMISSION_DENIED)
    }
}
