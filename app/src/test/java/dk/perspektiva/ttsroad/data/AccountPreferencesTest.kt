package dk.perspektiva.ttsroad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping between the server's preference vocabulary and this app's.
 *
 * Two of the mappings are lossy — four highlight modes against three, three themes that do not line
 * up — so the cases that matter are the ones where a naive "adopt whatever the server says" would
 * quietly rewrite a setting the user chose.
 */
class AccountPreferenceMappingTest {

    @Test
    fun `the default scale and the server default describe the same reading`() {
        // The two vocabularies convert through this fixed point. If either default moves without
        // the other, every existing account silently resizes its reader on the next sync.
        assertEquals(ServerReaderFontSizeDefault, readerFontSizeOf(DefaultReaderFontScale))
        assertEquals(DefaultReaderFontScale, readerFontScaleOf(ServerReaderFontSizeDefault), 0.0001f)
    }

    @Test
    fun `font size stays inside the range the server declares`() {
        for (scale in ReaderFontScales) {
            val size = readerFontSizeOf(scale)
            assertTrue("$scale -> $size", size in MinServerReaderFontSize..MaxServerReaderFontSize)
        }
        // The app's scale range is wider than the server's at the top end, so the largest scales
        // clamp rather than overflowing the spec.
        assertEquals(MaxServerReaderFontSize, readerFontSizeOf(4.0f))
    }

    @Test
    fun `the smallest server size is below what this app can express`() {
        // The app's scale floor of 0.8 is 15pt, so 14 is not reachable from here. Pinned rather
        // than hidden: it is a one-point difference at the bottom of the range, and the property
        // that makes it harmless is stability, which the next test checks.
        assertEquals(15, readerFontSizeOf(0.1f))
        assertTrue(readerFontSizeOf(0.1f) > MinServerReaderFontSize)
    }

    @Test
    fun `a server size this app cannot express still round-trips to itself`() {
        // 14 reads back as the app's floor, which projects to 15. That must settle rather than
        // oscillate — and because a pull never PATCHes, the phone never writes its 15 over the
        // browser's 14.
        val scale = readerFontScaleOf(MinServerReaderFontSize)
        assertEquals(scale, readerFontScaleOf(readerFontSizeOf(scale)), 0.0001f)
    }

    @Test
    fun `every app theme projects to a value the server accepts`() {
        val accepted = setOf("dark", "sepia", "light")
        for (theme in ReaderTheme.entries) {
            assertTrue(theme.name, serverReaderTheme(theme) in accepted)
        }
    }

    @Test
    fun `every app highlight mode projects to a value the server accepts`() {
        val accepted = setOf("sentence", "word", "off")
        for (mode in HighlightGranularity.entries) {
            assertTrue(mode.name, serverReaderHighlight(mode) in accepted)
        }
    }

    @Test
    fun `an unknown server value is ignored rather than guessed at`() {
        assertNull(readerThemeFromServer("solarized"))
        assertNull(readerThemeFromServer(null))
        assertNull(readerHighlightFromServer("paragraph"))
        assertNull(readerHighlightFromServer(null))
    }

    @Test
    fun `sleep timer durations snap to the nearest offered one`() {
        for (option in SleepTimerDefaultOptions) {
            assertEquals(option, sanitizeSleepTimerMinutes(option))
        }
        assertEquals(15, sanitizeSleepTimerMinutes(14))
        assertEquals(60, sanitizeSleepTimerMinutes(600))
        assertEquals(0, sanitizeSleepTimerMinutes(-5))
    }

    @Test
    fun `only the unplayed filter hides played chapters`() {
        assertTrue(serverHidePlayed(ChapterFilter.Unplayed))
        assertTrue(!serverHidePlayed(ChapterFilter.All))
        assertTrue(!serverHidePlayed(ChapterFilter.Ready))
    }

    @Test
    fun `hide played off does not clobber a filter the server has no opinion about`() {
        // Ready also projects to hide_played=false. Turning it into All because the server said
        // "false" would drop a filter the server was never asked about.
        assertEquals(ChapterFilter.Ready, chapterFilterFromServer(false, ChapterFilter.Ready))
        assertEquals(ChapterFilter.All, chapterFilterFromServer(false, ChapterFilter.All))
        assertEquals(ChapterFilter.All, chapterFilterFromServer(false, ChapterFilter.Unplayed))
        assertEquals(ChapterFilter.Unplayed, chapterFilterFromServer(true, ChapterFilter.Ready))
    }

    @Test
    fun `booleans are read as permissively as the server writes them`() {
        assertEquals(true, mapOf("k" to true).preferenceBool("k"))
        assertEquals(true, mapOf("k" to "yes").preferenceBool("k"))
        assertEquals(true, mapOf("k" to "ON").preferenceBool("k"))
        assertEquals(true, mapOf("k" to 1.0).preferenceBool("k"))
        assertEquals(false, mapOf("k" to "off").preferenceBool("k"))
        assertEquals(false, mapOf("k" to 0).preferenceBool("k"))
        assertNull(mapOf("k" to "perhaps").preferenceBool("k"))
        assertNull(emptyMap<String, Any?>().preferenceBool("k"))
    }

    @Test
    fun `numbers survive arriving as the doubles Moshi produces`() {
        assertEquals(19, mapOf("k" to 19.0).preferenceInt("k"))
        assertEquals(19, mapOf("k" to "19").preferenceInt("k"))
        assertNull(mapOf("k" to "large").preferenceInt("k"))
    }
}

/**
 * The reconciliation rule itself: adopt the account's value only when it differs from what the
 * local value already projects to.
 */
class AccountPreferenceReconcileTest {

    private val local = SyncedPreferences(
        chapterFilter = ChapterFilter.All,
        sleepTimerDefaultMinutes = 0,
        readerFontScale = DefaultReaderFontScale,
        readerTheme = ReaderTheme.Console,
        readerHighlight = HighlightGranularity.SentenceAndWord,
    )

    @Test
    fun `an empty blob changes nothing`() {
        // The first sync after an upgrade meets an account that has never been told about these
        // keys. It must be a no-op, not a reset to the server's defaults.
        assertEquals(local, reconcileAccountPreferences(emptyMap(), local))
    }

    @Test
    fun `an unrelated blob changes nothing`() {
        val server = mapOf<String, Any?>("voice_favourites" to listOf("en-GB-RyanNeural"))
        assertEquals(local, reconcileAccountPreferences(server, local))
    }

    @Test
    fun `a sentence-only reader is not promoted to sentence-and-word`() {
        // Both project to "sentence". A server holding "sentence" is agreeing with the phone, not
        // instructing it, so the narrower local choice has to survive.
        val sentenceOnly = local.copy(readerHighlight = HighlightGranularity.SentenceOnly)
        val server = mapOf<String, Any?>("reader_highlight" to "sentence")

        assertEquals(
            HighlightGranularity.SentenceOnly,
            reconcileAccountPreferences(server, sentenceOnly).readerHighlight,
        )
    }

    @Test
    fun `a highlight mode genuinely changed elsewhere is adopted`() {
        val sentenceOnly = local.copy(readerHighlight = HighlightGranularity.SentenceOnly)
        val server = mapOf<String, Any?>("reader_highlight" to "word")

        assertEquals(
            HighlightGranularity.WordOnly,
            reconcileAccountPreferences(server, sentenceOnly).readerHighlight,
        )
    }

    @Test
    fun `the night theme survives a server that only knows dark`() {
        // Night and Console both project to "dark"; the server cannot tell them apart, so it must
        // not be allowed to collapse one into the other.
        val night = local.copy(readerTheme = ReaderTheme.Night)
        val server = mapOf<String, Any?>("reader_theme" to "dark")

        assertEquals(ReaderTheme.Night, reconcileAccountPreferences(server, night).readerTheme)
    }

    @Test
    fun `a theme genuinely changed elsewhere is adopted`() {
        val night = local.copy(readerTheme = ReaderTheme.Night)
        val server = mapOf<String, Any?>("reader_theme" to "sepia")

        assertEquals(ReaderTheme.Paper, reconcileAccountPreferences(server, night).readerTheme)
    }

    @Test
    fun `a font size that agrees with the local scale leaves the finer value alone`() {
        // 1.0 scale is 19pt. The server echoing 19 must not round-trip the scale into itself and
        // lose precision a future finer-grained picker would have set.
        val server = mapOf<String, Any?>("reader_font_size" to 19.0)

        assertEquals(
            DefaultReaderFontScale,
            reconcileAccountPreferences(server, local).readerFontScale,
            0.0001f,
        )
    }

    @Test
    fun `a font size changed elsewhere is adopted`() {
        val server = mapOf<String, Any?>("reader_font_size" to 30.0)
        val reconciled = reconcileAccountPreferences(server, local)

        assertTrue(reconciled.readerFontScale > DefaultReaderFontScale)
        assertEquals(30, readerFontSizeOf(reconciled.readerFontScale))
    }

    @Test
    fun `an out-of-range font size from the account is clamped, not applied raw`() {
        val server = mapOf<String, Any?>("reader_font_size" to 900.0)
        val reconciled = reconcileAccountPreferences(server, local)

        assertEquals(MaxServerReaderFontSize, readerFontSizeOf(reconciled.readerFontScale))
    }

    @Test
    fun `hide played from the account reaches the chapter filter`() {
        val server = mapOf<String, Any?>("hide_played" to true)

        assertEquals(
            ChapterFilter.Unplayed,
            reconcileAccountPreferences(server, local).chapterFilter,
        )
    }

    @Test
    fun `a sleep timer default from the account is snapped before it is stored`() {
        val server = mapOf<String, Any?>("sleep_timer_default_minutes" to 44.0)

        assertEquals(45, reconcileAccountPreferences(server, local).sleepTimerDefaultMinutes)
    }

    @Test
    fun `reconciling twice is stable`() {
        // Guards the ping-pong the lossy mappings could otherwise cause between two clients.
        val server = mapOf<String, Any?>(
            "hide_played" to true,
            "reader_theme" to "sepia",
            "reader_highlight" to "word",
            "reader_font_size" to 24.0,
            "sleep_timer_default_minutes" to 30.0,
        )
        val once = reconcileAccountPreferences(server, local)
        val twice = reconcileAccountPreferences(server, once)

        assertEquals(once, twice)
    }

    @Test
    fun `a light theme has no exact match but still resolves to a bright page`() {
        val server = mapOf<String, Any?>("reader_theme" to "light")

        assertEquals(ReaderTheme.Paper, reconcileAccountPreferences(server, local).readerTheme)
    }
}

/** The PATCH bodies. The rule under test is that each carries exactly one key. */
class AccountPreferencePatchTest {

    @Test
    fun `every patch sends one key and nothing else`() {
        val patches = listOf(
            chapterFilterPatch(ChapterFilter.Unplayed),
            sleepTimerDefaultPatch(30),
            readerFontScalePatch(1.0f),
            readerThemePatch(ReaderTheme.Paper),
            readerHighlightPatch(HighlightGranularity.Off),
        )
        for (patch in patches) {
            // A phone that has been offline holds stale copies of every key it did not touch.
            // Sending them back would overwrite whatever the browser changed in the meantime.
            assertEquals(patch.toString(), 1, patch.size)
            assertTrue(patch.keys.first() in AccountPreferenceKeys.Synced)
        }
    }

    @Test
    fun `patches carry the server's vocabulary, not the app's enum names`() {
        assertEquals(mapOf("hide_played" to true), chapterFilterPatch(ChapterFilter.Unplayed))
        assertEquals(mapOf("hide_played" to false), chapterFilterPatch(ChapterFilter.Ready))
        assertEquals(mapOf("reader_theme" to "sepia"), readerThemePatch(ReaderTheme.Paper))
        assertEquals(
            mapOf("reader_highlight" to "off"),
            readerHighlightPatch(HighlightGranularity.Off),
        )
        assertEquals(mapOf("reader_font_size" to 19), readerFontScalePatch(1.0f))
    }

    @Test
    fun `a patch value is always within what the server declares`() {
        assertEquals(mapOf("sleep_timer_default_minutes" to 60), sleepTimerDefaultPatch(9_000))
        assertEquals(mapOf("reader_font_size" to 30), readerFontScalePatch(99f))
        // 15, not 14: the app's own scale floor is above the server's smallest size.
        assertEquals(mapOf("reader_font_size" to 15), readerFontScalePatch(0f))
    }

    @Test
    fun `the device player keys are deliberately not synced`() {
        // Speed, skip interval, skip silence and volume boost stay on this phone. Pinned so the
        // decision has to be reversed on purpose rather than drifting in.
        assertTrue(SyncedPlayerKeys.isEmpty())
        assertTrue("playback_speed" !in AccountPreferenceKeys.Synced)
        assertTrue("skip_interval_seconds" !in AccountPreferenceKeys.Synced)
        assertTrue("skip_silence" !in AccountPreferenceKeys.Synced)
        assertTrue("volume_boost" !in AccountPreferenceKeys.Synced)
    }
}
