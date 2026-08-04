package dk.perspektiva.ttsroad.download

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.scheduler.Requirements

/**
 * What the network has to look like before the manager will run the queue.
 *
 * Both cases require *a* network, which is what makes a queued chapter wait for one instead of
 * failing while the phone is in a tunnel. Wi-Fi-only adds "and not a metered one" on top.
 *
 * Deliberately nothing about charging or battery: this app's downloads are started by hand, minutes
 * before someone gets in a car, and a chapter that refuses to download because the phone is at 14%
 * is worse than the battery it saves.
 *
 * Opted in because the scheduler package is still marked unstable in media3 1.10.0.
 */
@OptIn(UnstableApi::class)
fun downloadRequirements(wifiOnly: Boolean): Requirements = Requirements(
    if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK,
)
