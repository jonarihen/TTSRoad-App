package dk.perspektiva.ttsroad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * New-chapter notices, and the one thing the server cannot decide for us (#175).
 *
 * The lifecycle itself is the server's: a notice opens when a chapter is pulled and only becomes
 * dismissible once it plays, and the client reads `dismissible`/`playable` off the wire rather than
 * deriving them. What is decided here is which notices are **news to this handset** — the rule that
 * keeps a cold start from re-announcing the entire backlog.
 */
class ChapterNotificationsTest {

    private fun notice(
        id: Int,
        state: String,
        fictionTitle: String = "A Test Serial",
        progress: Int? = null,
    ) = ChapterNotificationEntry(
        id = id,
        state = state,
        dismissible = state == "ready",
        playable = state == "ready",
        fiction = ChapterNotificationFiction(id = 7, title = fictionTitle),
        chapter = ChapterNotificationChapter(
            id = 100 + id,
            title = "Chapter $id",
            chapterNumber = id,
            ttsProgress = progress,
        ),
    )

    @Test
    fun `the first look seeds and announces nothing`() {
        // A chapter that was already ready when the app started is not news — the app was closed
        // when it happened. Without this, every cold start re-announces everything.
        val (fresh, seen) = newlyReady(listOf(notice(1, "ready"), notice(2, "ready")), alreadySeen = null)

        assertTrue(fresh.isEmpty())
        assertEquals(setOf(1, 2), seen)
    }

    @Test
    fun `only a chapter that has just become ready is announced`() {
        val first = newlyReady(listOf(notice(1, "pulled")), alreadySeen = null)
        assertTrue(first.first.isEmpty())

        val second = newlyReady(listOf(notice(1, "ready")), alreadySeen = first.second)
        assertEquals(listOf(1), second.first.map { it.id })

        // Polling again must not repeat it.
        val third = newlyReady(listOf(notice(1, "ready")), alreadySeen = second.second)
        assertTrue(third.first.isEmpty())
    }

    @Test
    fun `an unknown state reads as still converting, never as ready`() {
        // Guessing Ready would offer Play for audio that may not exist.
        assertEquals(ChapterNotificationState.Pulled, ChapterNotificationState.fromWire("something-new"))
        assertEquals(ChapterNotificationState.Pulled, ChapterNotificationState.fromWire(null))
        assertEquals(ChapterNotificationState.Ready, ChapterNotificationState.fromWire("ready"))
        assertEquals(ChapterNotificationState.Stalled, ChapterNotificationState.fromWire("stalled"))
    }

    @Test
    fun `several chapters collapse into one notification`() {
        // A serial converting a backlog would otherwise post a dozen at once, burying the shade.
        val single = readyNotificationText(listOf(notice(1, "ready")))
        assertEquals("A Test Serial", single?.first)
        assertEquals("Chapter 1 is ready to listen", single?.second)

        val many = readyNotificationText(
            listOf(notice(1, "ready"), notice(2, "ready", fictionTitle = "Another Serial")),
        )
        assertEquals("2 chapters ready", many?.first)
        assertEquals("New audio in 2 serials", many?.second)

        val sameSerial = readyNotificationText(listOf(notice(1, "ready"), notice(2, "ready")))
        assertEquals("New audio in A Test Serial", sameSerial?.second)

        assertNull(readyNotificationText(emptyList()))
    }

    @Test
    fun `a converting row says how far it has got`() {
        assertEquals("Chapter 1  ·  converting 62%", notice(1, "pulled", progress = 62).detailLabel())
        assertEquals("Chapter 1  ·  converting", notice(1, "pulled").detailLabel())
        assertEquals("Chapter 1  ·  ready to listen", notice(1, "ready").detailLabel())
        assertEquals("Chapter 1  ·  conversion failed", notice(1, "stalled").detailLabel())
    }

    @Test
    fun `dismissible and playable are read, never derived`() {
        // The server answers 409 to a dismissal of a converting chapter. A client that worked the
        // rule out for itself would be a fourth opinion about something the server enforces — so a
        // payload that says a pulled row is dismissible is believed, not corrected.
        val odd = notice(1, "pulled").copy(dismissible = true, playable = true)

        assertEquals(ChapterNotificationState.Pulled, odd.presentation)
        assertTrue(odd.dismissible)
        assertTrue(odd.playable)
    }

    @Test
    fun `a dismissed row is not drawn even when a stale list carries one`() {
        val rows = visibleNotifications(listOf(notice(1, "ready"), notice(2, "dismissed")))

        assertEquals(listOf(1), rows.map { it.id })
    }

    @Test
    fun `an empty list is described rather than left blank`() {
        // Here an empty list is usually the answer somebody came for.
        assertEquals(
            "Every serial you follow is up to date.",
            chapterNotificationsEmptyNote(followsAnything = true),
        )
        assertTrue(chapterNotificationsEmptyNote(followsAnything = false).contains("Follow a serial"))
    }
}
