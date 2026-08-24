package dk.perspektiva.ttsroad.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
    val streamingCacheBytes: Long = DefaultStreamingCacheBytes,
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

/**
 * How much streamed-through audio is kept before the oldest of it is dropped.
 *
 * **This never touches a download.** Since 0.13.0 the two live in separate caches precisely so this
 * number can exist: everything it governs was merely played, so losing it costs a re-buffer and
 * nothing else, while chapters asked for by name are in a store with no evictor at all.
 *
 * One gigabyte is a few dozen chapters of TTS audio — enough that replaying last night's listening
 * is free, and small enough that an install does not quietly grow without limit for a year. Anyone
 * who wants the old unbounded behaviour has [StreamingCacheUnlimited] one tap away.
 */
const val DefaultStreamingCacheBytes: Long = 1024L * 1024L * 1024L

/** The cap meaning "keep everything", which is what every build before 0.13.0 did. */
const val StreamingCacheUnlimited: Long = Long.MAX_VALUE

/**
 * The caps offered in Settings, smallest first.
 *
 * Starts at 256 MB rather than lower because a cap below a handful of chapters spends its life
 * evicting the thing about to be replayed, which is worse than not caching at all.
 */
val StreamingCacheChoices: List<Long> = listOf(
    256L * 1024L * 1024L,
    512L * 1024L * 1024L,
    1024L * 1024L * 1024L,
    2048L * 1024L * 1024L,
    5120L * 1024L * 1024L,
    StreamingCacheUnlimited,
)

class DownloadPreferences(private val context: Context) {
    private object Keys {
        val WifiOnly = booleanPreferencesKey("download_wifi_only")
        val KeepAhead = intPreferencesKey("download_keep_ahead_chapters")
        val StreamingCacheBytes = longPreferencesKey("download_streaming_cache_bytes")
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
                // Coerced for the same reason: a zero or negative cap out of a corrupted store
                // would have the evictor drop every span the moment it was written, which looks
                // exactly like a cache that has stopped working.
                streamingCacheBytes = normalisedStreamingCacheBytes(stored[Keys.StreamingCacheBytes]),
            )
        }

    suspend fun current(): DownloadPrefs = prefs.first()

    suspend fun setWifiOnly(enabled: Boolean) {
        context.downloadDataStore.edit { it[Keys.WifiOnly] = enabled }
    }

    suspend fun setKeepAheadChapters(chapters: Int) {
        context.downloadDataStore.edit { it[Keys.KeepAhead] = chapters.coerceAtLeast(0) }
    }

    suspend fun setStreamingCacheBytes(bytes: Long) {
        context.downloadDataStore.edit {
            it[Keys.StreamingCacheBytes] = normalisedStreamingCacheBytes(bytes)
        }
    }
}

/**
 * The cap to actually use for [stored], which may be absent, nonsense, or from a build that offered
 * a choice this one no longer does.
 *
 * Absent means an install that predates the cap, and the default is the right answer for it. A
 * value at or below zero is a corrupted store, and reading it literally would evict every span as
 * soon as it was written — indistinguishable from a broken cache. Anything else is honoured as
 * typed, including a size no longer in [StreamingCacheChoices]: a cap someone deliberately chose
 * should not be quietly rounded to whatever this build happens to list.
 */
internal fun normalisedStreamingCacheBytes(stored: Long?): Long = when {
    stored == null -> DefaultStreamingCacheBytes
    stored <= 0L -> DefaultStreamingCacheBytes
    else -> stored
}
