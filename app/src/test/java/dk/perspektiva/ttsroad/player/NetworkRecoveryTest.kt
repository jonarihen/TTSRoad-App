package dk.perspektiva.ttsroad.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * After the backoff has given up, a returning network is what restarts playback (#326) — and when
 * the network never "returns" because it never left, the slow retries are (#482).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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

    /**
     * #482: cellular that stayed attached through a dead patch never calls onAvailable again; it
     * only regains VALIDATED. That transition counts, the stream of other capability updates
     * does not.
     */
    @Test
    fun `the watcher reports a network regaining validation, once per transition`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(connectivity)
        var returned = 0
        val watcher = NetworkReturnWatcher(connectivity, Handler(Looper.getMainLooper())) { returned++ }
        watcher.start()
        val callback = shadow.networkCallbacks.single()
        val network = connectivity.activeNetwork!!
        val validated = ShadowNetworkCapabilities.newInstance().also {
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        val unvalidated = ShadowNetworkCapabilities.newInstance()

        callback.onCapabilitiesChanged(network, validated)
        callback.onCapabilitiesChanged(network, validated)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, returned)

        callback.onCapabilitiesChanged(network, unvalidated)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, returned)

        callback.onCapabilitiesChanged(network, validated)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, returned)
        watcher.stop()
    }

    /**
     * Stands in for ExoPlayer against a server that times out while the phone keeps reporting a
     * network: every prepare() goes back to buffering, and the test then fails it again, which is
     * what the real player reports when the connection times out.
     */
    private class TimingOutPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        var error: PlaybackException? = null
        var wanted = true
        var state = Player.STATE_IDLE
        var prepares = 0
        var mediaId = "chapter:1"

        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(listOf(FakePlayer.item(mediaId)))
            .setContentPositionMs(123_000L)
            .setPlayWhenReady(wanted, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(state)
            .setPlayerError(error)
            .build()

        override fun handlePrepare(): ListenableFuture<*> {
            prepares++
            error = null
            state = Player.STATE_BUFFERING
            return Futures.immediateVoidFuture()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            wanted = playWhenReady
            return Futures.immediateVoidFuture()
        }

        override fun handleStop(): ListenableFuture<*> {
            error = null
            state = Player.STATE_IDLE
            return Futures.immediateVoidFuture()
        }

        fun timeOut(): PlaybackException = failWith(
            PlaybackException("timeout", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT),
        )

        fun failWith(failure: PlaybackException): PlaybackException {
            error = failure
            state = Player.STATE_IDLE
            invalidateState()
            return failure
        }

        fun moveTo(id: String) {
            mediaId = id
            invalidateState()
        }

        fun recover() {
            state = Player.STATE_READY
            invalidateState()
        }
    }

    /** Fails [player] the way the service would see it and hands the error to [retry]. */
    private fun TestScope.fail(player: TimingOutPlayer, retry: TransientPlaybackRetry) {
        retry.onFailure(player.timeOut(), retryable = true)
        testScheduler.runCurrent()
    }

    /** Runs the backoff dry: three fast attempts, each timing out again, then the first slow wait. */
    private fun TestScope.exhaustFastBackoff(player: TimingOutPlayer, retry: TransientPlaybackRetry) {
        for (attempt in 1..MaxAutomaticRetries) {
            fail(player, retry)
            testScheduler.advanceTimeBy(transientRetryDelayMs(attempt, playWhenReady = true)!!)
            testScheduler.runCurrent()
        }
        fail(player, retry)
    }

    /**
     * The #482 scenario end to end, on a virtual clock: no network callback ever fires, every
     * request times out for over an hour, and the retries keep coming — on time, one at a time, at
     * no more than one a minute — until the server answers.
     */
    @Test
    fun `a server timing out on an available network is retried slowly until it answers`() = runTest {
        val player = TimingOutPlayer()
        val retry = TransientPlaybackRetry(player, backgroundScope) { true }
        player.addListener(retry)
        var waitedMs = 0L

        for (attempt in 1..70) {
            fail(player, retry)
            val wait = transientRetryDelayMs(attempt, playWhenReady = true)!!
            assertTrue("never faster than once a minute once slow", attempt <= 4 || wait == 60_000L)
            testScheduler.advanceTimeBy(wait - 1)
            testScheduler.runCurrent()
            assertEquals("attempt $attempt fired early", attempt - 1, player.prepares)
            testScheduler.advanceTimeBy(1)
            testScheduler.runCurrent()
            assertEquals("attempt $attempt did not fire", attempt, player.prepares)
            waitedMs += wait
        }
        assertTrue("well past an hour of dead zone", waitedMs > 60 * 60_000L)
        assertEquals("resumes where it stopped", 123_000L, player.currentPosition)

        player.recover()
        testScheduler.advanceTimeBy(10 * 60_000L)
        testScheduler.runCurrent()
        assertEquals("a healthy player is not retried", 70, player.prepares)
    }

    @Test
    fun `a returning network replaces the pending slow retry and does not stack a second one`() = runTest {
        val player = TimingOutPlayer()
        val retry = TransientPlaybackRetry(player, backgroundScope) { true }
        player.addListener(retry)
        exhaustFastBackoff(player, retry)
        assertEquals(MaxAutomaticRetries, player.prepares)

        retry.onNetworkReturned()
        retry.onNetworkReturned()
        assertEquals("recovered once, at once", MaxAutomaticRetries + 1, player.prepares)
        testScheduler.advanceTimeBy(10 * 60_000L)
        testScheduler.runCurrent()
        assertEquals("the slow timer was cancelled", MaxAutomaticRetries + 1, player.prepares)

        // It starts the backoff over: the next failure waits 2 s again, not a minute.
        fail(player, retry)
        testScheduler.advanceTimeBy(2_000L)
        testScheduler.runCurrent()
        assertEquals(MaxAutomaticRetries + 2, player.prepares)
    }

    @Test
    fun `the same failure reported repeatedly keeps one timer`() = runTest {
        val player = TimingOutPlayer()
        val retry = TransientPlaybackRetry(player, backgroundScope) { true }
        val error = player.timeOut()
        repeat(5) { retry.onFailure(error, retryable = true) }
        testScheduler.advanceTimeBy(10 * 60_000L)
        testScheduler.runCurrent()
        assertEquals(1, player.prepares)
    }

    @Test
    fun `a pause during the slow phase ends it, and nothing retries the paused player`() = runTest {
        val player = TimingOutPlayer()
        val retry = TransientPlaybackRetry(player, backgroundScope) { true }
        player.addListener(retry)
        exhaustFastBackoff(player, retry)

        player.pause()
        testScheduler.advanceTimeBy(10 * 60_000L)
        testScheduler.runCurrent()
        retry.onNetworkReturned()
        assertEquals(MaxAutomaticRetries, player.prepares)
    }

    @Test
    fun `the fast backoff still re-prepares a stream that died while paused, and then stops`() = runTest {
        val player = TimingOutPlayer().apply { wanted = false }
        val retry = TransientPlaybackRetry(player, backgroundScope) { true }
        player.addListener(retry)
        exhaustFastBackoff(player, retry)
        testScheduler.advanceTimeBy(10 * 60_000L)
        testScheduler.runCurrent()
        assertEquals(MaxAutomaticRetries, player.prepares)
        assertFalse("prepare never starts a paused player", player.playWhenReady)

        // Pressing play is a fresh start rather than a jump into the slow phase.
        player.play()
        fail(player, retry)
        testScheduler.advanceTimeBy(2_000L)
        testScheduler.runCurrent()
        assertEquals(MaxAutomaticRetries + 1, player.prepares)
    }

    @Test
    fun `a stop, another chapter, a sign-out or a destroyed service ends the slow phase`() = runTest {
        for (ending in listOf("stop", "next chapter", "signed out", "destroyed")) {
            val player = TimingOutPlayer()
            var signedIn = true
            val scope = CoroutineScope(backgroundScope.coroutineContext + Job())
            val retry = TransientPlaybackRetry(player, scope) { signedIn }
            player.addListener(retry)
            exhaustFastBackoff(player, retry)

            when (ending) {
                "stop" -> player.stop()
                "next chapter" -> player.moveTo("chapter:2")
                "signed out" -> signedIn = false
                "destroyed" -> scope.cancel()
            }
            testScheduler.advanceTimeBy(10 * 60_000L)
            testScheduler.runCurrent()
            retry.onNetworkReturned()
            assertEquals(ending, MaxAutomaticRetries, player.prepares)
            scope.cancel()
        }
    }

    /**
     * 401/403 go to the session, 404 and "not downloaded" are permanent: none of them may keep a
     * slow retry from an earlier transient failure alive, or start one of their own.
     */
    @Test
    fun `a non-retryable failure cancels the slow phase and is never retried`() = runTest {
        val player = TimingOutPlayer()
        val retry = TransientPlaybackRetry(player, backgroundScope) { true }
        player.addListener(retry)
        exhaustFastBackoff(player, retry)

        val notFound = player.failWith(
            PlaybackException("404", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND),
        )
        retry.onFailure(notFound, retryable = false)
        testScheduler.advanceTimeBy(10 * 60_000L)
        testScheduler.runCurrent()
        retry.onNetworkReturned()
        assertEquals(MaxAutomaticRetries, player.prepares)
    }
}
