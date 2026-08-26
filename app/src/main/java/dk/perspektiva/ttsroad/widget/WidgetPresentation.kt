package dk.perspektiva.ttsroad.widget

import kotlin.math.roundToLong

/**
 * What the home-screen widget should say, worked out from the note the service left behind (#150).
 *
 * Kept apart from the Glance composable on purpose: everything interesting here is arithmetic and
 * judgement about a possibly-stale record, and none of it needs a launcher to be tested. The
 * composable is the thin part.
 */
sealed interface WidgetView {
    /** No account. The widget must not show the previous user's book. */
    data object SignedOut : WidgetView

    /** Signed in, but this device has not played anything yet. */
    data object NothingPlayed : WidgetView

    data class Playback(
        val chapterTitle: String,
        val fictionTitle: String?,
        /**
         * Whether audio is believed to be playing *now* — not merely what the snapshot recorded.
         * See [StalePlayingThresholdMs].
         */
        val isPlaying: Boolean,
        /** 0..100, or null when the player never reported a duration. */
        val progressPercent: Int?,
        /** "1h 12m left", or null when there is no duration to subtract from. */
        val remainingLabel: String?,
        val coverUrl: String?,
        /**
         * True when the snapshot claimed to be playing but is too old to believe, i.e. the process
         * died mid-chapter. The widget says "last heard" rather than drawing a pause button for
         * audio that stopped hours ago.
         */
        val wentQuiet: Boolean,
    ) : WidgetView
}

/**
 * How long a snapshot claiming `isPlaying` stays believable.
 *
 * The service rewrites the note on every fifteen-second progress tick while playing, so six missed
 * ticks means nobody has been updating it — the process was reaped, or was killed mid-chapter.
 * Believing it anyway is the specific bug worth designing against: a widget that shows a pause
 * button and a moving progress bar for audio that stopped at 2am is worse than one that shows
 * nothing, because it invites a tap that does the opposite of what it looks like.
 */
const val StalePlayingThresholdMs: Long = 90_000L

/**
 * Turn the stored note into a view.
 *
 * [now] is passed in rather than read, so the staleness rule is testable at the boundary rather
 * than approximately.
 */
fun widgetView(
    snapshot: NowPlayingSnapshot?,
    signedIn: Boolean,
    now: Long,
    staleAfterMs: Long = StalePlayingThresholdMs,
): WidgetView {
    if (!signedIn) return WidgetView.SignedOut
    if (snapshot == null || snapshot.chapterTitle.isBlank()) return WidgetView.NothingPlayed

    val age = (now - snapshot.updatedAt).coerceAtLeast(0L)
    val stillPlaying = snapshot.isPlaying && age <= staleAfterMs
    val position = estimatedPositionMs(snapshot, now, staleAfterMs)
    val duration = snapshot.durationMs.takeIf { it > 0L }

    return WidgetView.Playback(
        chapterTitle = snapshot.chapterTitle,
        fictionTitle = snapshot.fictionTitle?.takeIf { it.isNotBlank() },
        isPlaying = stillPlaying,
        progressPercent = duration?.let {
            ((position.toDouble() / it) * 100).roundToLong().coerceIn(0L, 100L).toInt()
        },
        remainingLabel = duration?.let { remainingLabel(it - position) },
        coverUrl = snapshot.coverUrl?.takeIf { it.isNotBlank() },
        wentQuiet = snapshot.isPlaying && age > staleAfterMs,
    )
}

/**
 * Where the audio has got to by [now].
 *
 * Only extrapolated while the snapshot is both playing and fresh; a paused player has not moved,
 * and a stale one stopped at an unknown moment, so in both cases the recorded position is the last
 * thing actually known to be true. Scaled by [NowPlayingSnapshot.speed], and never past the end.
 */
fun estimatedPositionMs(
    snapshot: NowPlayingSnapshot,
    now: Long,
    staleAfterMs: Long = StalePlayingThresholdMs,
): Long {
    val elapsed = (now - snapshot.updatedAt).coerceAtLeast(0L)
    val advancing = snapshot.isPlaying && elapsed <= staleAfterMs
    val speed = snapshot.speed.takeIf { it > 0f } ?: 1f
    val advanced = if (advancing) {
        snapshot.positionMs + (elapsed * speed).roundToLong()
    } else {
        snapshot.positionMs
    }
    val ceiling = snapshot.durationMs.takeIf { it > 0L } ?: return advanced.coerceAtLeast(0L)
    return advanced.coerceIn(0L, ceiling)
}

/**
 * "1h 12m left" / "4m left" / "under a minute left".
 *
 * Coarse by choice: this is a glance at a home screen, and a second-accurate countdown on something
 * redrawn only when the player changes state would be precise and wrong within a minute.
 */
fun remainingLabel(remainingMs: Long): String {
    if (remainingMs <= 0L) return "finished"
    val totalMinutes = remainingMs / 60_000L
    if (totalMinutes < 1L) return "under a minute left"
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0L && minutes > 0L -> "${hours}h ${minutes}m left"
        hours > 0L -> "${hours}h left"
        else -> "${minutes}m left"
    }
}
