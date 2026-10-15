package dk.perspektiva.ttsroad.player

import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import androidx.media3.common.Player

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
 * available.
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
    }

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
    }
}
