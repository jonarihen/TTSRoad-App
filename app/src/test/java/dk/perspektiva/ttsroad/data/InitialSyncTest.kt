package dk.perspektiva.ttsroad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The body `POST /api/mobile/fictions` actually receives.
 *
 * Worth testing as arithmetic rather than through the form, because the failure it guards is
 * silent: an omitted `sync_limit` is not an error, it is the server converting the whole backlog.
 * Nothing on the phone reports that, and the bill arrives hours later as a queue full of chapters.
 */
class InitialSyncTest {

    @Test
    fun `the form opens on the newest 25, matching the web`() {
        // Not the API's default, which is "everything" — a missing field cannot mean anything else.
        // The web form has always put Last 25 in front of the person adding the book.
        assertEquals(25, InitialSync.Default.limit)
        assertEquals(SyncDirection.Last, InitialSync.Default.direction)
    }

    @Test
    fun `a bounded window sends both the limit and the direction`() {
        val body = AddFictionOptions(sync = InitialSync(50, SyncDirection.First))
            .toRequest("https://example.test/fiction/1")

        assertEquals(50, body.syncLimit)
        assertEquals("first", body.syncDirection)
    }

    @Test
    fun `everything sends no limit, which is exactly what the server reads as all`() {
        val body = AddFictionOptions(sync = InitialSync(limit = null)).toRequest("12345")

        assertNull(body.syncLimit)
        // Meaningless without a limit, and sending it would imply a direction was in play.
        assertNull(body.syncDirection)
    }

    @Test
    fun `the wire value is the enum's own, not its label`() {
        // The labels are display text and will get reworded; "NEWEST" is not a value the API takes.
        assertEquals("last", SyncDirection.Last.wire)
        assertEquals("first", SyncDirection.First.wire)
    }

    @Test
    fun `a blank or unreadable chapter count keeps the last number rather than becoming all`() {
        // These are one backspace apart and one of them starts hours of narration.
        assertEquals(25, parseSyncLimit("", 25))
        assertEquals(25, parseSyncLimit("   ", 25))
        assertEquals(40, parseSyncLimit("not a number", 40))
    }

    @Test
    fun `the chapter count is clamped to the range the web input declares`() {
        assertEquals(InitialSync.MaxLimit, parseSyncLimit("99999", 25))
        assertEquals(InitialSync.MinLimit, parseSyncLimit("0", 25))
        assertEquals(300, parseSyncLimit("300", 25))
    }

    @Test
    fun `the summary says what will actually happen`() {
        assertEquals("The 25 newest chapters", InitialSync(25, SyncDirection.Last).summary)
        assertEquals("The oldest chapter only", InitialSync(1, SyncDirection.First).summary)
        assertTrue(InitialSync(limit = null).summary.contains("Every chapter"))
    }

    @Test
    fun `voice and rate are omitted when untouched, so the server's defaults apply`() {
        val body = AddFictionOptions().toRequest("12345")

        assertNull(body.voice)
        assertNull(body.rate)
        assertEquals(true, body.enabled)
    }

    @Test
    fun `a rate is normalised before it is sent`() {
        // The server stores the string without checking it, so an unsigned "10" would not fail on
        // save — it would fail at conversion time as a chapter that never narrates.
        assertEquals("+10%", AddFictionOptions(rate = "10").toRequest("1").rate)
        assertEquals("-5%", AddFictionOptions(rate = " -5% ").toRequest("1").rate)
        assertNull(AddFictionOptions(rate = "  ").toRequest("1").rate)
    }

    @Test
    fun `a rate the server cannot read is reported rather than sent`() {
        assertEquals("A rate reads like +0%, +25% or -10%.", AddFictionOptions(rate = "fast").rateProblem)
        assertNull(AddFictionOptions(rate = "+25%").rateProblem)
        // Blank is not a problem: it means "leave it to the server".
        assertNull(AddFictionOptions(rate = "").rateProblem)
        assertNull(AddFictionOptions().rateProblem)
    }

    @Test
    fun `turning off polling is sent, because the server defaults it on`() {
        assertEquals(false, AddFictionOptions(enabled = false).toRequest("1").enabled)
    }
}
