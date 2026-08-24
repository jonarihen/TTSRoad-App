package dk.perspektiva.ttsroad.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * A playback speed remembered per fiction, overriding the global one.
 *
 * Different voices and different narrators want different paces, and switching between two books
 * meant re-setting the speed every time. The global speed stays the default; a book with an
 * override uses it, and clearing the override hands the book back to the global rather than
 * freezing it at whatever the global happened to be.
 *
 * **Deliberately its own store, and deliberately device-local.** `/api/me/preferences` has a key for
 * the global speed and none for a per-fiction map, so keeping this out of [PlaybackPrefs] is what
 * stops [AccountPreferenceSync] from ever being handed something it has nowhere to put. #68 flags
 * that making it an account preference is a backend change; until that exists, this is honest about
 * being a property of the phone.
 */
private val Context.fictionSpeedDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ttsroad_fiction_speeds",
)

/**
 * The stored map as one string: `id=speed` pairs separated by commas.
 *
 * A single key rather than one per fiction because the whole map is read together — deciding what
 * speed to play at needs all of it — and because a preference store with an unbounded number of
 * dynamically named keys is a thing nobody can inspect or clear.
 *
 * Written in [Locale.US] so the decimal point is a point. A store written on a phone set to a
 * comma-decimal locale and read on the same phone after a locale change would otherwise parse back
 * as nothing, and the book would silently revert to the global speed.
 */
internal fun encodeFictionSpeeds(speeds: Map<Int, Float>): String =
    speeds.entries
        .filter { it.key > 0 }
        .sortedBy { it.key }
        .joinToString(",") { (id, speed) ->
            "$id=" + String.format(Locale.US, "%.2f", sanitizeSpeed(speed))
        }

/**
 * Read [raw] back, dropping anything unparseable.
 *
 * Nothing here throws. This runs while restoring preferences at startup and while deciding what
 * speed to play at, and a corrupted entry is worth ignoring rather than crashing over — the book
 * falls back to the global speed, which is the same thing that happens for a book with no override.
 */
internal fun decodeFictionSpeeds(raw: String?): Map<Int, Float> {
    if (raw.isNullOrBlank()) return emptyMap()
    val speeds = LinkedHashMap<Int, Float>()
    for (entry in raw.split(',')) {
        val id = entry.substringBefore('=', "").trim().toIntOrNull() ?: continue
        if (id <= 0) continue
        val speed = entry.substringAfter('=', "").trim().toFloatOrNull() ?: continue
        if (speed.isNaN()) continue
        speeds[id] = sanitizeSpeed(speed)
    }
    return speeds
}

/**
 * The speed to actually play at: the fiction's own, or [globalSpeed] when it has none.
 *
 * A null [fictionId] is "nothing is playing, or the item does not say which book it belongs to",
 * and takes the global — an item with no fiction id has no override to look up, and guessing would
 * apply one book's pace to another's.
 */
fun effectiveSpeed(globalSpeed: Float, overrides: Map<Int, Float>, fictionId: Int?): Float =
    sanitizeSpeed(fictionId?.let { overrides[it] } ?: globalSpeed)

class FictionSpeedPreferences(private val context: Context) {
    private object Keys {
        val Speeds = stringPreferencesKey("fiction_playback_speeds")
    }

    val overrides: Flow<Map<Int, Float>> = context.fictionSpeedDataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { decodeFictionSpeeds(it[Keys.Speeds]) }

    /** Pin [fictionId] to [speed], regardless of what the global speed is or later becomes. */
    suspend fun setSpeed(fictionId: Int, speed: Float) {
        if (fictionId <= 0) return
        context.fictionSpeedDataStore.edit { stored ->
            val current = decodeFictionSpeeds(stored[Keys.Speeds])
            stored[Keys.Speeds] = encodeFictionSpeeds(current + (fictionId to sanitizeSpeed(speed)))
        }
    }

    /** Hand [fictionId] back to the global speed, including changes to it from here on. */
    suspend fun clearSpeed(fictionId: Int) {
        context.fictionSpeedDataStore.edit { stored ->
            val current = decodeFictionSpeeds(stored[Keys.Speeds])
            if (fictionId !in current) return@edit
            stored[Keys.Speeds] = encodeFictionSpeeds(current - fictionId)
        }
    }
}
