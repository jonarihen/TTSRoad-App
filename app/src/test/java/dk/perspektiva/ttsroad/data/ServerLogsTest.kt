package dk.perspektiva.ttsroad.data

import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning a log page into lines (#124).
 *
 * The assertions worth having here are about what a *silence* says. Everywhere else in the app an
 * empty list is a mild disappointment; on this screen it usually means nothing has gone wrong,
 * which is the answer someone opened it hoping for — so "no rows" has to be described in terms of
 * the filters that produced it, and the four cases genuinely mean four different things.
 */
class ServerLogsTest {
    private val zone = ZoneId.of("Europe/Copenhagen")
    private val locale = Locale.UK

    private fun entry(
        id: Int = 1,
        level: String = "ERROR",
        message: String = "Synthesis failed for chapter 88",
        fictionId: Int? = 7,
        chapterId: Int? = 88,
        createdAt: String? = "2026-08-26T09:14:02Z",
    ) = ServerLogEntry(
        id = id,
        level = level,
        message = message,
        fictionId = fictionId,
        chapterId = chapterId,
        createdAt = createdAt,
    )

    private fun rows(vararg entries: ServerLogEntry, titles: Map<Int, String> = emptyMap()) =
        serverLogRows(
            ServerLogsResponse(logs = entries.toList()),
            titles = titles,
            zone = zone,
            locale = locale,
        )

    @Test
    fun `a line names the book when the client knows it and the id when it does not`() {
        val known = rows(entry(), titles = mapOf(7 to "Mother of Learning")).single()
        val unknown = rows(entry()).single()

        assertEquals("Mother of Learning · Chapter 88", known.source)
        // A fiction the shelf has never loaded — one this account does not follow — still gets a
        // usable line. Dropping the reference would hide which book the failure was about.
        assertEquals("Fiction 7 · Chapter 88", unknown.source)
    }

    @Test
    fun `a line about the install itself carries no source at all`() {
        // Most of a quiet log looks like this: the scheduler waking, voices refreshing. A client
        // that assumed a fiction id would have nothing to draw.
        val row = rows(entry(fictionId = null, chapterId = null)).single()

        assertNull(row.source)
        assertNull(row.fictionId)
    }

    @Test
    fun `the timestamp is rendered in the phone's zone, not the server's`() {
        val row = rows(entry()).single()

        // 09:14 UTC is 11:14 in Copenhagen. "When did this fail" only means something against the
        // clock the person reading it is looking at.
        assertEquals("26 Aug 2026, 11:14", row.time)
    }

    @Test
    fun `a timestamp the client cannot read leaves the line intact`() {
        val row = rows(entry(createdAt = "not-a-date")).single()

        assertNull(row.time)
        // The message is the point; the date is context. Dropping the row would lose the failure.
        assertEquals("Synthesis failed for chapter 88", row.message)
    }

    @Test
    fun `the level is upper-cased so the screen can switch on it`() {
        assertEquals("WARNING", rows(entry(level = "warning")).single().level)
    }

    @Test
    fun `appending a page keeps order and drops an id already on screen`() {
        val first = rows(entry(id = 4120), entry(id = 4119))
        val second = rows(entry(id = 4119), entry(id = 4118))

        val merged = mergeServerLogPages(first, second)

        // The cursor makes a repeat impossible in theory; the guard is here because the cost of
        // being wrong is a list showing the same failure twice and sending someone looking for the
        // second one.
        assertEquals(listOf(4120, 4119, 4118), merged.map { it.id })
    }

    @Test
    fun `no errors is good news, and says so`() {
        assertTrue(serverLogsEmptyNote("ERROR", null).contains("No errors"))
    }

    @Test
    fun `an empty log with no filters is a statement about the server`() {
        val note = serverLogsEmptyNote(null, null)

        assertTrue(note.contains("empty"))
        // Not "no results". Nothing at all in the log of a running install is worth noticing.
        assertFalse(note.contains("No errors"))
    }

    @Test
    fun `each combination of filters gets its own sentence`() {
        val byLevel = serverLogsEmptyNote("WARNING", null)
        val byFiction = serverLogsEmptyNote(null, 7)
        val byBoth = serverLogsEmptyNote("WARNING", 7)

        assertEquals(3, setOf(byLevel, byFiction, byBoth).size)
        assertTrue(byFiction.contains("fiction"))
        assertTrue(byBoth.contains("fiction"))
        assertTrue(byBoth.contains("warning"))
    }

    /** A level this client would never send is not described as though the server had honoured it. */
    @Test
    fun `an unrecognised level is not quoted back as a filter`() {
        assertEquals(serverLogsEmptyNote(null, null), serverLogsEmptyNote("CRITICAL", null))
    }

    /**
     * Both halves of the gate mean different things and both are required: the capability says the
     * server has the route, `is_admin` says this account may reach it. The server enforces it
     * regardless — hiding the screen only stops offering a door that answers 403.
     */
    @Test
    fun `the log needs the capability and an admin account`() {
        val supported = ServerCapabilities(logs = true)

        assertTrue(canReadServerLogs(supported, isAdmin = true))
        assertFalse(canReadServerLogs(supported, isAdmin = false))
        assertFalse(canReadServerLogs(ServerCapabilities(logs = false), isAdmin = true))
        assertFalse(canReadServerLogs(ServerCapabilities.Baseline, isAdmin = true))
    }

    /**
     * The two reasons the screen cannot be drawn are not interchangeable, and telling them apart is
     * the difference between someone updating a backend and someone asking for an admin account.
     */
    @Test
    fun `an old server and a regular account are given different answers`() {
        val old = serverLogsUnavailableNote(ServerCapabilities.Baseline, isAdmin = true)
        val notAdmin = serverLogsUnavailableNote(ServerCapabilities(logs = true), isAdmin = false)

        assertNotNull(old)
        assertNotNull(notAdmin)
        assertNotEquals(old, notAdmin)
        assertTrue(old!!.contains("server"))
        assertTrue(notAdmin!!.contains("admin-only"))
    }

    @Test
    fun `an admin on a server that has the route is told nothing at all`() {
        assertNull(serverLogsUnavailableNote(ServerCapabilities(logs = true), isAdmin = true))
    }

    /** Exactly the three the log column holds. Anything else is a 400, not an empty screen. */
    @Test
    fun `the offered levels are the ones the server accepts`() {
        assertEquals(listOf("ERROR", "WARNING", "INFO"), ServerLogLevels)
    }
}
