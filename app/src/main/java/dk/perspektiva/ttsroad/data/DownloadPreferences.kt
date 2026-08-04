package dk.perspektiva.ttsroad.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
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

class DownloadPreferences(private val context: Context) {
    private object Keys {
        val WifiOnly = booleanPreferencesKey("download_wifi_only")
    }

    val prefs: Flow<DownloadPrefs> = context.downloadDataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { stored -> DownloadPrefs(wifiOnly = stored[Keys.WifiOnly] ?: DefaultWifiOnly) }

    suspend fun current(): DownloadPrefs = prefs.first()

    suspend fun setWifiOnly(enabled: Boolean) {
        context.downloadDataStore.edit { it[Keys.WifiOnly] = enabled }
    }
}
