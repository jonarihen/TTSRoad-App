package dk.perspektiva.ttsroad.player

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Whether a network coming back should re-prepare [player] (#326).
 *
 * The backoff in [retryDelayMs] covers a handover or a short tunnel and then stops, which left a
 * three-minute dead zone ending in silence: the signal returned, nothing asked again, and the car
 * stayed quiet until someone reached for it. A returning network is the one moment a retry is
 * known to be worth something, so it gets one regardless of how many the backoff already spent.
 *
 * Only for a failure that was [PlaybackFailure.Transient]: a missing file or a revoked session does
 * not heal because the phone found Wi-Fi. And only while [Player.getPlayWhenReady] still holds —
 * a stream that died while paused stays paused, the same rule the backoff follows.
 */
fun shouldRecoverOnNetworkReturn(player: Player, lastFailureTransient: Boolean): Boolean =
    lastFailureTransient &&
        player.playerError != null &&
        player.playbackState == Player.STATE_IDLE &&
        player.playWhenReady

/**
 * Calls [onNetworkReturned] on [mainHandler]'s thread whenever a default network becomes
 * available, or an existing one gains [NetworkCapabilities.NET_CAPABILITY_VALIDATED].
 *
 * The second case is the one `onAvailable` misses (#482): cellular that stayed attached through a
 * dead patch loses validation and regains it without the network ever going away. Capabilities
 * change constantly (signal strength, bandwidth estimates), so only the transition into validated
 * counts, not every callback that happens to report it.
 *
 * The callback itself arrives on a connectivity thread, and every player call has to be made on the
 * application thread, so it only ever posts. Registration also reports the network already up,
 * which is harmless: [shouldRecoverOnNetworkReturn] is false for a player with no error.
 */
class NetworkReturnWatcher(
    private val connectivity: ConnectivityManager?,
    private val mainHandler: Handler,
    private val onNetworkReturned: () -> Unit,
) {
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            mainHandler.post(onNetworkReturned)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val gained = synchronized(validatedNetworks) {
                if (validated) validatedNetworks.add(network) else validatedNetworks.remove(network).let { false }
            }
            if (gained) mainHandler.post(onNetworkReturned)
        }

        override fun onLost(network: Network) {
            synchronized(validatedNetworks) { validatedNetworks.remove(network) }
        }
    }

    // Networks last seen validated, so a capabilities callback only reports the transition.
    private val validatedNetworks = mutableSetOf<Network>()

    private var registered = false

    fun start() {
        val manager = connectivity ?: return
        if (registered) return
        // Some ROMs throw on callback registration (a per-app callback cap, a missing service in a
        // work profile). Losing the recovery is better than losing the media service with it.
        registered = runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
        synchronized(validatedNetworks) { validatedNetworks.clear() }
    }
}

/**
 * Automatic re-prepares after a [PlaybackFailure.Transient] failure, and the one timer they run on.
 *
 * The fast backoff (2/5/15 s) covers a handover; past it, [transientRetryDelayMs] keeps asking
 * every 30-60 s while playback is still wanted (#482), because a network that stays "connected"
 * while every request times out never calls [NetworkReturnWatcher] back. A returning network
 * still short-circuits the wait and starts a fresh backoff (#326) — through this class, so the two
 * paths share a timer instead of each re-preparing on their own.
 *
 * There is only ever one pending retry, and it re-checks before preparing that the same error is
 * still on the same player for the same session. Everything that means "stop trying" ends the loop
 * outright: a non-retryable failure, a pause during the slow phase, a different chapter, a stop or
 * recovery, a signed-out session ([sessionActive]), or [scope] being cancelled with the service.
 */
internal class TransientPlaybackRetry(
    private val player: Player,
    private val scope: CoroutineScope,
    private val sessionActive: () -> Boolean,
) : Player.Listener {
    private var retry: Job? = null
    private var attempt = 0
    private var failure: PlaybackException? = null
    private var failedMediaId: String? = null

    /** A player error, already classified; only a [retryable] one is retried. */
    fun onFailure(error: PlaybackException, retryable: Boolean) {
        retry?.cancel()
        retry = null
        if (!retryable) {
            reset()
            return
        }
        failure = error
        failedMediaId = player.currentMediaItem?.mediaId
        if (!scope.isActive || !sessionActive()) return
        val next = attempt + 1
        val wait = transientRetryDelayMs(next, player.playWhenReady) ?: return
        attempt = next.coerceAtMost(MaxAutomaticRetries + 2)
        retry = scope.launch {
            delay(wait)
            if (stillFailing(error) && (next <= MaxAutomaticRetries || player.playWhenReady)) {
                player.prepare()
            }
        }
    }

    /** A network came back: retry now with a fresh backoff, instead of waiting out the timer. */
    fun onNetworkReturned() {
        val error = failure ?: return
        if (!stillFailing(error) || !shouldRecoverOnNetworkReturn(player, lastFailureTransient = true)) return
        retry?.cancel()
        retry = null
        attempt = 0
        player.prepare()
    }

    fun reset() {
        retry?.cancel()
        retry = null
        attempt = 0
        failure = null
        failedMediaId = null
    }

    private fun stillFailing(error: PlaybackException): Boolean =
        scope.isActive &&
            sessionActive() &&
            failure === error &&
            player.playerError === error &&
            player.playbackState == Player.STATE_IDLE &&
            player.currentMediaItem?.mediaId == failedMediaId

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        // The fast backoff re-prepares a paused player too, which stays paused; only the open-ended
        // slow loop depends on playback being wanted. Pressing play again is a fresh start.
        if (playWhenReady) {
            attempt = 0
            // A pause in the slow phase dropped the timer but kept the failure; nobody else will prepare.
            val error = failure
            if (error != null && retry?.isActive != true && stillFailing(error)) player.prepare()
        } else if (attempt > MaxAutomaticRetries) {
            retry?.cancel()
            retry = null
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (mediaItem?.mediaId != failedMediaId) reset()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) reset()
    }

    // stop() clears the error without leaving STATE_IDLE, so no state change reports it.
    override fun onPlayerErrorChanged(error: PlaybackException?) {
        if (error == null && player.playbackState == Player.STATE_IDLE) reset()
    }
}
