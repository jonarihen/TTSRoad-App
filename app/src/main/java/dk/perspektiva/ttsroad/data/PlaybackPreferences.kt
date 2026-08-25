package dk.perspektiva.ttsroad.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
 * for that to also forget that you listen at 1.5x with silence skipping on.
 */
private val Context.playbackDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ttsroad_playback",
)

data class PlaybackPrefs(
    val speed: Float = DefaultSpeed,
    val skipIntervalMs: Long = DefaultSkipIntervalMs,
    val skipSilence: Boolean = DefaultSkipSilence,
    val volumeBoost: VolumeBoost = VolumeBoost.Off,
    /**
     * The duration the sleep timer offers first, or 0 for "ask me every time".
     *
     * Unlike its four neighbours here, this one follows the account — see `AccountPreferences.kt`
     * for why those stay on the device and this does not. It is stored locally all the same, so the
     * timer still has a default with no network and on a server too old to hold one.
     */
    val sleepTimerDefaultMinutes: Int = DefaultSleepTimerMinutes,
    /**
     * Whether reaching the end of a chapter marks it played on its own.
     *
     * Follows the account, like [sleepTimerDefaultMinutes] and unlike the four device keys above.
     * Kept locally all the same because the media service reads it on every progress save — often
     * with no UI running, sometimes with no network — and a chapter's played state is not something
     * to leave undecided until a sync lands.
     */
    val autoMarkPlayed: Boolean = DefaultAutoMarkPlayed,
)

/**
 * The speeds offered in the player's speed picker.
 *
 * The same nine steps as the desktop client, spanning the full range the backend's `playback_speed`
 * spec accepts (0.5–3.0). The list used to stop at 2.0x, which made that the effective ceiling even
 * though [sanitizeSpeed] has always allowed 3.0x — the picker offers presets and nothing else, so a
 * speed absent from this list could not be reached from the UI at all.
 *
 * 0.8x was in the old list and is not in this one. Rather than snap those listeners to 0.75x behind
 * their back, [speedOptions] keeps whatever speed is actually set selectable; see there.
 */
val SpeedPresets: List<Float> =
    listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

/**
 * [SpeedPresets] plus [current], if the speed in force is not one of the presets.
 *
 * Two cases reach this. A listener who settled on 0.8x under a build whose presets included it
 * would otherwise open the sheet, see no row marked "Current", and have no way back to the speed
 * they were already listening at. And once preferences follow the account (#62), a speed set by
 * another client can be any value the server's 0.5–3.0 range permits, not just one of these nine.
 *
 * Sorted, so the inserted row lands between its neighbours instead of at the end.
 */
fun speedOptions(current: Float): List<Float> {
    val sanitized = sanitizeSpeed(current)
    val alreadyOffered = SpeedPresets.any { kotlin.math.abs(it - sanitized) < SpeedEpsilon }
    return if (alreadyOffered) SpeedPresets else (SpeedPresets + sanitized).sorted()
}

/**
 * Float comparison tolerance for "is this the same speed". Presets are a tenth apart at the closest,
 * so anything this small only ever collapses genuine rounding drift.
 */
const val SpeedEpsilon: Float = 0.001f

/**
 * Skip amounts offered in Settings. 30s suits a dozed-off rewind, 10-15s suits "what did that
 * sentence say" — both are wanted, at different times of day.
 */
val SkipIntervalOptionsMs: List<Long> = listOf(10_000L, 15_000L, 30_000L, 45_000L, 60_000L)

const val DefaultSpeed: Float = 1.0f
const val DefaultSkipIntervalMs: Long = 30_000L

/**
 * Off by default, so the app sounds like the web console on the same chapter.
 *
 * Silence skipping is genuinely useful for synthesised speech — edge-tts leaves pauses around
 * headings, scene breaks and sentence boundaries that are longer than a human narrator's, and over
 * an eight-hour night that is a lot of dead air. But `HTMLMediaElement` has no equivalent, so the
 * web player always plays those pauses in full. Having it on here by default made the phone
 * audibly quicker than the browser at a nominal 1.0x, which reads as a bug rather than a feature
 * you were given (#43). It is one switch away in the player's speed sheet for anyone who wants it.
 */
const val DefaultSkipSilence: Boolean = false

/**
 * How much to lift quiet chapters.
 *
 * Chapters converted at different times, or with different voices, come out at different levels.
 * In the car that means reaching for the volume knob; in bed it means a loud chapter after a quiet
 * one wakes you up.
 *
 * Gains are in millibels, which is what `LoudnessEnhancer.setTargetGain` takes. They stop at 10 dB
 * because beyond that a chapter that was already near full scale starts to clip audibly, which is a
 * worse problem than the one being fixed.
 */
enum class VolumeBoost(val gainMillibels: Int, val label: String) {
    Off(gainMillibels = 0, label = "Off"),
    Low(gainMillibels = 300, label = "Low"),
    Medium(gainMillibels = 600, label = "Medium"),
    High(gainMillibels = 1_000, label = "High"),
}

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

/** Tolerate a stored name from a different build rather than failing to read the whole file. */
fun volumeBoostOf(storedName: String?): VolumeBoost =
    VolumeBoost.entries.firstOrNull { it.name == storedName } ?: VolumeBoost.Off

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
        val SkipSilence = booleanPreferencesKey("skip_silence")
        val VolumeBoost = stringPreferencesKey("volume_boost")
        val SleepTimerDefaultMinutes = intPreferencesKey("sleep_timer_default_minutes")
        val AutoMarkPlayed = booleanPreferencesKey("auto_mark_played")
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
                skipSilence = stored[Keys.SkipSilence] ?: DefaultSkipSilence,
                volumeBoost = volumeBoostOf(stored[Keys.VolumeBoost]),
                sleepTimerDefaultMinutes = sanitizeSleepTimerMinutes(
                    stored[Keys.SleepTimerDefaultMinutes] ?: DefaultSleepTimerMinutes,
                ),
                autoMarkPlayed = stored[Keys.AutoMarkPlayed] ?: DefaultAutoMarkPlayed,
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

    suspend fun setSkipSilence(enabled: Boolean) {
        context.playbackDataStore.edit { it[Keys.SkipSilence] = enabled }
    }

    suspend fun setVolumeBoost(boost: VolumeBoost) {
        context.playbackDataStore.edit { it[Keys.VolumeBoost] = boost.name }
    }

    suspend fun setSleepTimerDefaultMinutes(minutes: Int) {
        context.playbackDataStore.edit {
            it[Keys.SleepTimerDefaultMinutes] = sanitizeSleepTimerMinutes(minutes)
        }
    }

    suspend fun setAutoMarkPlayed(enabled: Boolean) {
        context.playbackDataStore.edit { it[Keys.AutoMarkPlayed] = enabled }
    }
}
