package dk.perspektiva.ttsroad.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignedOutPlaybackTest {
    @Test
    fun `signing out stops the service player and removes cached queue`() {
        val player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build()
        try {
            player.setMediaItem(MediaItem.fromUri("https://example.com/audio/chapter.mp3"))
            player.playWhenReady = true

            stopSignedOutPlayback(player)

            assertFalse(player.playWhenReady)
            assertEquals(Player.STATE_IDLE, player.playbackState)
            assertEquals(0, player.mediaItemCount)
            stopSignedOutPlayback(player)
            assertEquals(0, player.mediaItemCount)
        } finally {
            player.release()
        }
    }
}
