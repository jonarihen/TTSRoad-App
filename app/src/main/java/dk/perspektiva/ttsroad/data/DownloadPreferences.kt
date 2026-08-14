package dk.perspektiva.ttsroad.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Download preferences, in their own DataStore for the same reason [PlaybackPreferences] is in one:
 * signing out clears the session, and it has no business forgetting that this phone is not supposed
 * to pull chapters over a metered link.
 */
private val Context.downloadDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ttsroad_downloads",
)

data class DownloadPrefs(
    val wifiOnly: Boolean = DefaultWifiOnly,
    val keepAheadChapters: Int = DefaultKeepAheadChapters,
)

/**
 * On by default, because the failure it prevents is expensive and silent.
 *
 * A chapter is tens of megabytes and "download next 20" is one tap away, so the way this goes wrong
 * without a default is a phone quietly spending a data plan on an audiobook while its owner is out.
 * Waiting for Wi-Fi is a delay; the other way round is a bill. Anyone who wants downloads on mobile
 * data is one switch away in Settings.
 */
const val DefaultWifiOnly: Boolean = true

/**
 * How many chapters ahead of the one playing are kept on disk automatically. Zero is off.
 *
 * **Off by default, deliberately.** Every other download in this app is something the user asked
 * for by name; this is the one that spends storage on its own. Turning it on is a single tap in
 * Settings → Offline, and an upgrade that quietly started filling a phone would be the kind of
 * surprise the Wi-Fi-only default exists to avoid.
 */
const val DefaultKeepAheadChapters: Int = 0

/**
 * The choices offered in Settings.
 *
 * Stops at 20 because the window is also what gets cleaned up behind you — see
 * [dk.perspektiva.ttsroad.download.autoDownloadPlan]. Someone who wants a whole book on the phone
 * for a flight wants "download all" on the fiction header, which keeps what it fetches.
 */
val KeepAheadChoices: List<Int> = listOf(0, 3, 5, 10, 20)

class DownloadPreferences(private val context: Context) {
    private object Keys {
        val WifiOnly = booleanPreferencesKey("download_wifi_only")
        val KeepAhead = intPreferencesKey("download_keep_ahead_chapters")
    }

    val prefs: Flow<DownloadPrefs> = context.downloadDataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { stored ->
            DownloadPrefs(
                wifiOnly = stored[Keys.WifiOnly] ?: DefaultWifiOnly,
                // Coerced rather than trusted: a negative value from a corrupted store would sail
                // through into the planner, and "off" is the safe reading of nonsense here.
                keepAheadChapters = (stored[Keys.KeepAhead] ?: DefaultKeepAheadChapters)
                    .coerceAtLeast(0),
            )
        }

    suspend fun current(): DownloadPrefs = prefs.first()

    suspend fun setWifiOnly(enabled: Boolean) {
        context.downloadDataStore.edit { it[Keys.WifiOnly] = enabled }
    }

    suspend fun setKeepAheadChapters(chapters: Int) {
        context.downloadDataStore.edit { it[Keys.KeepAhead] = chapters.coerceAtLeast(0) }
    }
}
