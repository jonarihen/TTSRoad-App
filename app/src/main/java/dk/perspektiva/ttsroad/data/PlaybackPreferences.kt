package dk.perspektiva.ttsroad.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Playback preferences, in their own DataStore rather than the session one.
 *
 * Deliberately a sibling of [TokenStore]: signing out clears the session, and there is no reason
 * for that to also forget that you listen at 1.5x.
 */
private val Context.playbackDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ttsroad_playback",
)

data class PlaybackPrefs(
    val speed: Float = DefaultSpeed,
    val skipIntervalMs: Long = DefaultSkipIntervalMs,
)

/** The speeds offered in the player's speed picker. */
val SpeedPresets: List<Float> = listOf(0.8f, 1.0f, 1.2f, 1.5f, 1.75f, 2.0f)

/**
 * Skip amounts offered in Settings. 30s suits a dozed-off rewind, 10-15s suits "what did that
 * sentence say" — both are wanted, at different times of day.
 */
val SkipIntervalOptionsMs: List<Long> = listOf(10_000L, 15_000L, 30_000L, 45_000L, 60_000L)

const val DefaultSpeed: Float = 1.0f
const val DefaultSkipIntervalMs: Long = 30_000L

/**
 * Keep a stored value usable even if it was written by a build with a different preset list, or
 * corrupted. Speed is clamped to a sane playback range rather than snapped to a preset, so a value
 * set by a future build with finer steps still survives a downgrade.
 */
fun sanitizeSpeed(speed: Float): Float =
    if (speed.isNaN()) DefaultSpeed else speed.coerceIn(MinSpeed, MaxSpeed)

/** Skips only ever come from the fixed option list, so an unknown value falls back to the default. */
fun sanitizeSkipIntervalMs(skipIntervalMs: Long): Long =
    if (skipIntervalMs in SkipIntervalOptionsMs) skipIntervalMs else DefaultSkipIntervalMs

/** "30s" / "1m" — the label used on the Settings row and in the picker. */
fun formatSkipInterval(skipIntervalMs: Long): String {
    val seconds = skipIntervalMs / 1000
    return if (seconds % 60 == 0L && seconds >= 60) "${seconds / 60}m" else "${seconds}s"
}

private const val MinSpeed = 0.5f
private const val MaxSpeed = 3.0f

class PlaybackPreferences(private val context: Context) {
    private object Keys {
        val Speed = floatPreferencesKey("playback_speed")
        val SkipIntervalMs = longPreferencesKey("skip_interval_ms")
    }

    val prefs: Flow<PlaybackPrefs> = context.playbackDataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { stored ->
            PlaybackPrefs(
                speed = sanitizeSpeed(stored[Keys.Speed] ?: DefaultSpeed),
                skipIntervalMs = sanitizeSkipIntervalMs(
                    stored[Keys.SkipIntervalMs] ?: DefaultSkipIntervalMs,
                ),
            )
        }

    suspend fun current(): PlaybackPrefs = prefs.first()

    suspend fun setSpeed(speed: Float) {
        context.playbackDataStore.edit { it[Keys.Speed] = sanitizeSpeed(speed) }
    }

    suspend fun setSkipIntervalMs(skipIntervalMs: Long) {
        context.playbackDataStore.edit {
            it[Keys.SkipIntervalMs] = sanitizeSkipIntervalMs(skipIntervalMs)
        }
    }
}
