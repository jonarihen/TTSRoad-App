package dk.perspektiva.ttsroad.player

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * After the backoff has given up, a returning network is what restarts playback (#326).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkRecoveryTest {
    private val networkError = PlaybackException(
        "tunnel",
        null,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    )
    private val queue = listOf(FakePlayer.item("chapter:1"))

    @Test
    fun `a player that gave up on a network error while playing is recovered`() {
        val player = FakePlayer(playlist = queue, playing = true, error = networkError)
        assertTrue(shouldRecoverOnNetworkReturn(player, lastFailureTransient = true))
    }

    @Test
    fun `a stream that died while paused stays paused`() {
        val player = FakePlayer(playlist = queue, playing = false, error = networkError)
        assertFalse(shouldRecoverOnNetworkReturn(player, lastFailureTransient = true))
    }

    @Test
    fun `a permanent failure is not retried because Wi-Fi came back`() {
        val player = FakePlayer(playlist = queue, playing = true, error = networkError)
        assertFalse(shouldRecoverOnNetworkReturn(player, lastFailureTransient = false))
    }

    @Test
    fun `a healthy player is left alone`() {
        val player = FakePlayer(playlist = queue, playing = true)
        assertFalse(shouldRecoverOnNetworkReturn(player, lastFailureTransient = true))
    }

    @Test
    fun `the watcher reports a returning network on the main thread, and stops when told`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(connectivity)
        var returned = 0
        val watcher = NetworkReturnWatcher(connectivity, Handler(Looper.getMainLooper())) {
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            returned++
        }

        watcher.start()
        assertEquals(1, shadow.networkCallbacks.size)
        shadow.networkCallbacks.single().onAvailable(connectivity.activeNetwork!!)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, returned)

        watcher.stop()
        assertTrue(shadow.networkCallbacks.isEmpty())
    }
}
