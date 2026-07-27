package dk.perspektiva.ttsroad.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
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
 * for that to also forget how you like your audio.
 */
private val Context.playbackDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ttsroad_playback",
)

data class PlaybackPrefs(
    val skipSilence: Boolean = DefaultSkipSilence,
    val volumeBoost: VolumeBoost = VolumeBoost.Off,
)

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
 * On by default: synthesised speech carries pauses around headings, scene breaks and sentence
 * boundaries that are longer than a human narrator's, and over an eight-hour night that is a lot of
 * dead air. It stays switchable because it can clip a deliberate dramatic pause.
 */
const val DefaultSkipSilence: Boolean = true

/** Tolerate a stored name from a different build rather than failing to read the whole file. */
fun volumeBoostOf(storedName: String?): VolumeBoost =
    VolumeBoost.entries.firstOrNull { it.name == storedName } ?: VolumeBoost.Off

class PlaybackPreferences(private val context: Context) {
    private object Keys {
        val SkipSilence = booleanPreferencesKey("skip_silence")
        val VolumeBoost = stringPreferencesKey("volume_boost")
    }

    val prefs: Flow<PlaybackPrefs> = context.playbackDataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { stored ->
            PlaybackPrefs(
                skipSilence = stored[Keys.SkipSilence] ?: DefaultSkipSilence,
                volumeBoost = volumeBoostOf(stored[Keys.VolumeBoost]),
            )
        }

    suspend fun current(): PlaybackPrefs = prefs.first()

    suspend fun setSkipSilence(enabled: Boolean) {
        context.playbackDataStore.edit { it[Keys.SkipSilence] = enabled }
    }

    suspend fun setVolumeBoost(boost: VolumeBoost) {
        context.playbackDataStore.edit { it[Keys.VolumeBoost] = boost.name }
    }
}
