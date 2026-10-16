package dk.perspektiva.ttsroad.data

import android.content.Context

/**
 * Keeps the phone's copy of the shared settings in step with the account.
 *
 * Deliberately thin, and deliberately one-directional per call. [pull] reads the account and writes
 * back only the local values the server actually disagrees with; the `push*` calls send one changed
 * key each. Nothing here writes on a read, which is what keeps the lossy reader mappings in
 * `AccountPreferences.kt` from ping-ponging between two clients.
 *
 * The local stores stay the source of truth for the UI. That is not a fallback arrangement — it is
 * what lets every setting keep working with no network, on a server too old to hold them, and
 * before the first sync of a session has finished.
 */
class AccountPreferenceSync(
    private val repository: TtsRoadRepository,
    private val playbackPreferences: PlaybackPreferences,
    private val readerPreferences: ReaderPreferenceStore,
    private val chapterListPreferences: ChapterListPreferences,
    private val unsynced: UnsyncedPreferenceKeys,
) {
    /** What the phone currently shows, in the vocabulary the account shares. */
    private suspend fun localSnapshot(): SyncedPreferences {
        val playback = playbackPreferences.current()
        val reader = readerPreferences.current()
        return SyncedPreferences(
            chapterFilter = chapterListPreferences.current(),
            autoMarkPlayed = playback.autoMarkPlayed,
            sleepTimerDefaultMinutes = playback.sleepTimerDefaultMinutes,
            readerFontScale = reader.fontScale,
            readerLineHeight = reader.lineHeight,
            readerTheme = reader.theme,
            readerHighlight = reader.highlight,
            skipAdSegments = playback.skipAdSegments,
        )
    }

    /**
     * Read the account and adopt whatever it holds that this phone does not.
     *
     * Answers the reconciled snapshot, or null when there was nothing to read — an older server, a
     * failed call, or no session. Callers treat null as "carry on with what you have"; there is no
     * error to show, because every one of these settings already has a working local value.
     */
    suspend fun pull(): SyncedPreferences? {
        val server = repository.accountPreferences() ?: return null
        val local = localSnapshot()
        // A key changed while its PATCH could not go out is owed to the account, not taken from it
        // (#453): the server still holds the value the user changed away from.
        val owed = unsynced.current()
        if (owed.isNotEmpty()) {
            val patch = local.asPatch().filterKeys { it in owed }
            if (repository.updateAccountPreferences(patch) != null) unsynced.remove(patch.keys)
        }
        val reconciled = reconcileAccountPreferences(server - owed, local)

        // Only what actually moved. Writing an unchanged value would wake every collector on the
        // DataStore and recompose the screens reading it for no reason.
        if (reconciled.chapterFilter != local.chapterFilter) {
            chapterListPreferences.setFilter(reconciled.chapterFilter)
        }
        if (reconciled.autoMarkPlayed != local.autoMarkPlayed) {
            playbackPreferences.setAutoMarkPlayed(reconciled.autoMarkPlayed)
        }
        if (reconciled.sleepTimerDefaultMinutes != local.sleepTimerDefaultMinutes) {
            playbackPreferences.setSleepTimerDefaultMinutes(reconciled.sleepTimerDefaultMinutes)
        }
        if (reconciled.readerFontScale != local.readerFontScale) {
            readerPreferences.setFontScale(reconciled.readerFontScale)
        }
        if (reconciled.readerLineHeight != local.readerLineHeight) {
            readerPreferences.setLineHeight(reconciled.readerLineHeight)
        }
        if (reconciled.readerTheme != local.readerTheme) {
            readerPreferences.setTheme(reconciled.readerTheme)
        }
        if (reconciled.readerHighlight != local.readerHighlight) {
            readerPreferences.setHighlight(reconciled.readerHighlight)
        }
        if (reconciled.skipAdSegments != local.skipAdSegments) {
            playbackPreferences.setSkipAdSegments(reconciled.skipAdSegments)
        }
        return reconciled
    }

    // The push half. Each writes the local store first and then tells the account, so the setting
    // takes effect on this phone whether or not the server is reachable — and a failed PATCH is
    // silent for the same reason [pull] is: the local value is already correct. It is remembered,
    // though, so the next [pull] sends it rather than reverting it.

    /** Marked before the call, so a 401 or a process death mid-PATCH still leaves the key owed. */
    private suspend fun push(changes: Map<String, Any?>) {
        unsynced.add(changes.keys)
        if (repository.updateAccountPreferences(changes) != null) unsynced.remove(changes.keys)
    }

    suspend fun setChapterFilter(filter: ChapterFilter) {
        chapterListPreferences.setFilter(filter)
        push(chapterFilterPatch(filter))
    }

    suspend fun setAutoMarkPlayed(enabled: Boolean) {
        playbackPreferences.setAutoMarkPlayed(enabled)
        push(autoMarkPlayedPatch(enabled))
    }

    suspend fun setSleepTimerDefaultMinutes(minutes: Int) {
        playbackPreferences.setSleepTimerDefaultMinutes(minutes)
        push(sleepTimerDefaultPatch(minutes))
    }

    suspend fun setSkipAdSegments(enabled: Boolean) {
        playbackPreferences.setSkipAdSegments(enabled)
        push(skipAdSegmentsPatch(enabled))
    }

    suspend fun setReaderFontScale(scale: Float) {
        readerPreferences.setFontScale(scale)
        push(readerFontScalePatch(scale))
    }

    suspend fun setReaderLineHeight(height: Float) {
        readerPreferences.setLineHeight(height)
        push(readerLineHeightPatch(height))
    }

    suspend fun setReaderTheme(theme: ReaderTheme) {
        readerPreferences.setTheme(theme)
        push(readerThemePatch(theme))
    }

    suspend fun setReaderHighlight(granularity: HighlightGranularity) {
        readerPreferences.setHighlight(granularity)
        push(readerHighlightPatch(granularity))
    }
}

/** Every key this client syncs, as the account would store [this]. */
private fun SyncedPreferences.asPatch(): Map<String, Any?> =
    chapterFilterPatch(chapterFilter) +
        autoMarkPlayedPatch(autoMarkPlayed) +
        sleepTimerDefaultPatch(sleepTimerDefaultMinutes) +
        readerFontScalePatch(readerFontScale) +
        readerLineHeightPatch(readerLineHeight) +
        readerThemePatch(readerTheme) +
        readerHighlightPatch(readerHighlight) +
        skipAdSegmentsPatch(skipAdSegments)

/**
 * Account preference keys changed on this phone that the account has not accepted yet (#453).
 *
 * Device-wide like the local stores they shadow: the value is this phone's, whichever account the
 * next successful push reaches.
 */
class UnsyncedPreferenceKeys(context: Context) {
    private val prefs = context.getSharedPreferences("account_preferences_unsynced", Context.MODE_PRIVATE)

    @Synchronized
    fun current(): Set<String> = prefs.getStringSet(Key, null).orEmpty().toSet()

    @Synchronized
    fun add(keys: Set<String>) {
        prefs.edit().putStringSet(Key, current() + keys).apply()
    }

    @Synchronized
    fun remove(keys: Set<String>) {
        prefs.edit().putStringSet(Key, current() - keys).apply()
    }

    private companion object {
        const val Key = "keys"
    }
}
