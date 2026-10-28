package dk.perspektiva.ttsroad.media

import android.content.Context
import android.media.session.MediaController as PlatformController
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dk.perspektiva.ttsroad.player.FakePlayer
import dk.perspektiva.ttsroad.player.classifyPlaybackError
import dk.perspektiva.ttsroad.player.playbackErrorDetails
import dk.perspektiva.ttsroad.player.playerUiStateOf
import dk.perspektiva.ttsroad.player.readablePlaybackException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionPlaybackErrorsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun httpError(status: Int): PlaybackException = PlaybackException(
        "Source error with private URL and token",
        IllegalStateException(HttpDataSource.InvalidResponseCodeException(
            status, "HTTP $status", null, emptyMap(),
            DataSpec.Builder().setUri("https://private.example/audio/42").build(), byteArrayOf(),
        )),
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    )

    @Test fun `classification and HTTP status survive the controller bundle without private diagnostics`() {
        for (status in listOf(401, 403, 404, 408, 429, 503)) {
            val original = httpError(status)
            val relayed = PlaybackException.fromBundle(readablePlaybackException(original).toBundle())
            assertEquals(classifyPlaybackError(original.errorCode, status).message, relayed.message)
            assertEquals(original.errorCode, relayed.errorCode)
            assertEquals("ERROR_CODE_IO_BAD_HTTP_STATUS\nHTTP $status", playbackErrorDetails(relayed))
            assertNull(relayed.cause)
            assertFalse(relayed.toString().contains("private.example"))
        }
    }

    @Test fun `a catch-all retains the error code name for Details and no raw exception message`() {
        val error = readablePlaybackException(PlaybackException(
            "secret diagnostic", null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        ))
        assertEquals("Could not play this chapter", error.message)
        assertEquals("ERROR_CODE_DECODER_INIT_FAILED", playbackErrorDetails(error))
    }

    private class ErrorPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        var error: PlaybackException? = null
        var state = Player.STATE_READY
        var wanted = true
        var mediaId = "chapter:42"
        var prepares = 0
        fun publish() = invalidateState()
        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(listOf(FakePlayer.item(mediaId)))
            .setPlayWhenReady(wanted, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(state).setPlayerError(error).build()
        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            wanted = playWhenReady
            return Futures.immediateVoidFuture()
        }
        override fun handlePrepare(): ListenableFuture<*> {
            prepares++
            error = null
            state = Player.STATE_BUFFERING
            return Futures.immediateVoidFuture()
        }
        fun fail(failure: PlaybackException) {
            error = failure
            state = Player.STATE_IDLE
            publish()
        }
    }

    @Test fun `readable error reaches both Media3 and platform session without disabling pause or retry`() {
        val player = ErrorPlayer()
        val session = MediaSession.Builder(context, SessionPlaybackErrors(player)).build()
        val controller = MediaController.Builder(context, session.token).buildAsync().get()
        val platform = PlatformController(context, session.platformToken)
        try {
            player.fail(httpError(404))
            shadowOf(Looper.getMainLooper()).idle()
            val message = "Server rejected the audio request (HTTP 404)"
            assertEquals(message, controller.playerError?.message)
            assertEquals(message, platform.playbackState?.errorMessage?.toString())
            val ui = playerUiStateOf(controller, emptyList())
            assertEquals(message, ui.error)
            assertEquals("ERROR_CODE_IO_BAD_HTTP_STATUS\nHTTP 404", ui.errorDetails)
            assertTrue(controller.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
            assertTrue(controller.isCommandAvailable(Player.COMMAND_PREPARE))
            controller.pause()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(player.playWhenReady)
            controller.prepare()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, player.prepares)
            assertNull(controller.playerError)
            assertNull(playerUiStateOf(controller, emptyList()).errorDetails)
            assertNull(platform.playbackState?.errorMessage)
        } finally {
            controller.release()
            session.release()
        }
    }

    @Test fun `another item clears the error even if the underlying player has not cleared it yet`() {
        val player = ErrorPlayer()
        val presented = SessionPlaybackErrors(player)
        player.fail(httpError(503))
        assertNotNull(presented.playerError)
        player.mediaId = "chapter:43"
        player.publish()
        assertNull(presented.playerError)
        player.mediaId = "chapter:42"
        player.publish()
        assertNull(presented.playerError)
    }

    @Test fun `resumption error uses SessionError and reaches the platform session in readable words`() {
        val player = ErrorPlayer()
        val session = MediaSession.Builder(context, player).build()
        val platform = PlatformController(context, session.platformToken)
        val unavailable = OfflineResume.Unavailable("Can't reach the server, and \"Chapter 212\" is not downloaded")
        val failure = unavailable.asRequestFailure()
        try {
            assertEquals(SessionError.ERROR_IO, failure.sessionError.code)
            assertEquals(unavailable.message, failure.message)
            session.sendError(failure.sessionError)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(unavailable.message, platform.playbackState?.errorMessage?.toString())
        } finally {
            session.release()
        }
    }
}
