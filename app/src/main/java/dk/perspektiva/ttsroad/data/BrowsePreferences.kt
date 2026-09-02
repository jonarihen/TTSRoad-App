package dk.perspektiva.ttsroad.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * How the browse grid was last left: its order, its tag filter and which scope tab was open.
 *
 * These were `rememberSaveable` before, which survives rotation and a trip into a fiction and back
 * — and nothing else. A sort order picked on Monday was gone by Tuesday because Android had reaped
 * the process overnight, which is the one time you would notice, and the web console has persisted
 * exactly these three in `localStorage` all along. Somebody who browses by "Recently updated" in a
 * browser and by "Title A–Z" on their phone did not choose that.
 *
 * Its own DataStore, alongside [ChapterListPreferences] and [DownloadPreferences], for the reason
 * given there: this is a view setting rather than session state, so it has no business being
 * cleared by signing out — nor being carried to the account, where the server has no concept of it.
 */
private val Context.browseDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ttsroad_browse",
)

/**
 * Reads a stored sort name back into a [FictionSort], falling back to [FictionSort.Default].
 *
 * An order this build does not recognise — one renamed in an upgrade, or a corrupt store — must
 * land somewhere sensible rather than throw, because this is read while the first frame of the
 * browse grid is being composed.
 */
fun fictionSortFromStored(stored: String?): FictionSort =
    FictionSort.entries.firstOrNull { it.name == stored } ?: FictionSort.Default

/** The three browse settings, read and written together because they are set from one screen. */
data class BrowseSettings(
    val sort: FictionSort = FictionSort.Default,
    val tags: Set<String> = emptySet(),
    val scope: BrowseScope = BrowseScope.Default,
)

class BrowsePreferences(private val context: Context) {
    private object Keys {
        val Sort = stringPreferencesKey("browse_sort")
        val Tags = stringSetPreferencesKey("browse_tags")
        val Scope = stringPreferencesKey("browse_scope")
    }

    val settings: Flow<BrowseSettings> = context.browseDataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { stored ->
            BrowseSettings(
                sort = fictionSortFromStored(stored[Keys.Sort]),
                // Lower-cased on the way out as well as in: the set is compared against
                // `availableTags()`, which lower-cases, and a value written by an older build has
                // no guarantee of having been normalised.
                tags = stored[Keys.Tags].orEmpty().mapTo(mutableSetOf()) { it.lowercase() },
                scope = browseScopeFromStored(stored[Keys.Scope]),
            )
        }

    suspend fun current(): BrowseSettings = settings.first()

    suspend fun setSort(sort: FictionSort) {
        context.browseDataStore.edit { it[Keys.Sort] = sort.name }
    }

    suspend fun setTags(tags: Set<String>) {
        context.browseDataStore.edit { preferences ->
            val normalised = tags.mapTo(mutableSetOf()) { it.trim().lowercase() }
            // Removed rather than written empty, so a shelf that has never been filtered and one
            // that has just been cleared read back identically.
            if (normalised.isEmpty()) {
                preferences.remove(Keys.Tags)
            } else {
                preferences[Keys.Tags] = normalised
            }
        }
    }

    suspend fun setScope(scope: BrowseScope) {
        context.browseDataStore.edit { it[Keys.Scope] = scope.name }
    }
}
