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
 * How the chapter list is filtered, remembered across fictions and across restarts.
 *
 * In its own DataStore for the same reason [DownloadPreferences] is in one: this is a view setting
 * rather than session state, and signing out has no business handing back a list full of chapters
 * you have already listened to.
 *
 * Deliberately one setting for the whole library and not one per fiction. Someone working through
 * a series in order wants played chapters gone everywhere, and re-picking "Unplayed" on every book
 * in the library is the exact chore this exists to remove.
 */
private val Context.chapterListDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ttsroad_chapter_list",
)

/**
 * Reads a stored filter name back into a [ChapterFilter], falling back to showing everything.
 *
 * Hiding rows is the surprising direction, so a value this build does not recognise — a filter
 * removed in an upgrade, or a corrupt store — must not be able to leave someone staring at a list
 * that looks empty with nothing on screen explaining why.
 */
fun chapterFilterFromStored(stored: String?): ChapterFilter =
    ChapterFilter.entries.firstOrNull { it.name == stored } ?: ChapterFilter.All

/**
 * Reads the stored sort direction back, defaulting to oldest first — reading order, which is what
 * the list showed before the direction was remembered (#349).
 */
fun chapterSortAscendingFromStored(stored: Boolean?): Boolean = stored ?: true

class ChapterListPreferences(private val context: Context) {
    private object Keys {
        val Filter = stringPreferencesKey("chapter_filter")
        val SortAscending = booleanPreferencesKey("chapter_sort_ascending")
    }

    private val stored: Flow<Preferences> = context.chapterListDataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }

    val filter: Flow<ChapterFilter> = stored
        .map { prefs -> chapterFilterFromStored(prefs[Keys.Filter]) }

    /**
     * OLDEST (true) or NEWEST (false). Library-wide like the filter and for the same reason: it used
     * to reset to OLDEST on every visit, so someone who reads the latest release first flipped it on
     * every open. Local only — the account has no key for it.
     */
    val sortAscending: Flow<Boolean> = stored
        .map { prefs -> chapterSortAscendingFromStored(prefs[Keys.SortAscending]) }

    suspend fun current(): ChapterFilter = filter.first()

    suspend fun setFilter(filter: ChapterFilter) {
        context.chapterListDataStore.edit { it[Keys.Filter] = filter.name }
    }

    suspend fun setSortAscending(ascending: Boolean) {
        context.chapterListDataStore.edit { it[Keys.SortAscending] = ascending }
    }
}
