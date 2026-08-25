package dk.perspektiva.ttsroad.data

import java.text.SimpleDateFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading and writing the backup file (#116).
 *
 * The file comes out of a system picker, so *any* file on the device can be handed to it. The rules
 * that matter are therefore about refusing gracefully — a picked photo is an ordinary outcome, not
 * a fault — and about never trimming a document from a server newer than this build, because the
 * trimming would only be discovered on the day the backup was needed.
 */
class ListeningStateBackupTest {

    @Test
    fun `an unknown key survives being written and read again`() {
        val document = mapOf(
            "version" to 2.0,
            "a_key_from_a_newer_server" to mapOf("nested" to listOf(1.0, 2.0)),
        )

        val reparsed = parseListeningStateJson(listeningStateJson(document))

        assertEquals(document, reparsed)
    }

    /**
     * Someone who saved the raw API response by hand has a file with the `document` wrapper still
     * on it. Refusing that would be technically correct and useless.
     */
    @Test
    fun `a file that still carries the API wrapper is unwrapped`() {
        val parsed = parseListeningStateJson("""{"document": {"version": 2, "positions": []}}""")

        assertEquals(setOf("version", "positions"), parsed?.keys)
    }

    @Test
    fun `anything that is not a backup is refused rather than thrown`() {
        assertNull(parseListeningStateJson("not json at all"))
        assertNull(parseListeningStateJson("[1, 2, 3]"))
        assertNull(parseListeningStateJson(""))
        // Valid JSON, and not a backup. Accepting it would report "nothing changed" and leave the
        // user unsure whether they picked the wrong file.
        assertNull(parseListeningStateJson("{}"))
    }

    /**
     * "ok" alone cannot separate a backup that restored four hundred positions from one that was
     * already fully applied — and the second is exactly what restoring the wrong file looks like.
     */
    @Test
    fun `the summary reports the server's own counts`() {
        val summary = listeningStateImportSummary(
            mapOf("positions" to 401.0, "bookmarks" to 12.0),
        )

        assertTrue(summary, "401 positions" in summary)
        assertTrue(summary, "12 bookmarks" in summary)
    }

    @Test
    fun `a key this build has never heard of is still reported`() {
        val summary = listeningStateImportSummary(mapOf("reading_sessions_merged" to 3.0))

        assertTrue(summary, "3 reading sessions merged" in summary)
    }

    @Test
    fun `a restore that changed nothing says so plainly`() {
        assertTrue(
            listeningStateImportSummary(mapOf("positions" to 0.0)).contains("Nothing in that file"),
        )
        assertTrue(listeningStateImportSummary(null).contains("Nothing in that file"))
        assertTrue(listeningStateImportSummary(emptyMap()).contains("Nothing in that file"))
    }

    /** Two backups in the same folder on the same day would otherwise land on top of each other. */
    @Test
    fun `the default file name carries the date`() {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse("2026-08-25")!!

        assertEquals("ttsroad-listening-state-2026-08-25.json", listeningStateFileName(date))
    }
}
