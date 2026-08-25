package dk.perspektiva.ttsroad.data

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
            readerTheme = reader.theme,
            readerHighlight = reader.highlight,
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
        val reconciled = reconcileAccountPreferences(server, local)

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
        if (reconciled.readerTheme != local.readerTheme) {
            readerPreferences.setTheme(reconciled.readerTheme)
        }
        if (reconciled.readerHighlight != local.readerHighlight) {
            readerPreferences.setHighlight(reconciled.readerHighlight)
        }
        return reconciled
    }

    // The push half. Each writes the local store first and then tells the account, so the setting
    // takes effect on this phone whether or not the server is reachable — and a failed PATCH is
    // silent for the same reason [pull] is: the local value is already correct.

    suspend fun setChapterFilter(filter: ChapterFilter) {
        chapterListPreferences.setFilter(filter)
        repository.updateAccountPreferences(chapterFilterPatch(filter))
    }

    suspend fun setAutoMarkPlayed(enabled: Boolean) {
        playbackPreferences.setAutoMarkPlayed(enabled)
        repository.updateAccountPreferences(autoMarkPlayedPatch(enabled))
    }

    suspend fun setSleepTimerDefaultMinutes(minutes: Int) {
        playbackPreferences.setSleepTimerDefaultMinutes(minutes)
        repository.updateAccountPreferences(sleepTimerDefaultPatch(minutes))
    }

    suspend fun setReaderFontScale(scale: Float) {
        readerPreferences.setFontScale(scale)
        repository.updateAccountPreferences(readerFontScalePatch(scale))
    }

    suspend fun setReaderTheme(theme: ReaderTheme) {
        readerPreferences.setTheme(theme)
        repository.updateAccountPreferences(readerThemePatch(theme))
    }

    suspend fun setReaderHighlight(granularity: HighlightGranularity) {
        readerPreferences.setHighlight(granularity)
        repository.updateAccountPreferences(readerHighlightPatch(granularity))
    }
}
