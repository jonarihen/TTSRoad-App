package dk.perspektiva.ttsroad.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(ChapterNotificationsResponse::class.java)

    private fun decode(json: String): ChapterNotificationsResponse =
        requireNotNull(adapter.fromJson(json)) { "adapter returned null for $json" }

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
            chapterNumber = id.toDouble(),
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
    fun `a ready backlog alert on the first poll stays silent`() {
        val backlog = notice(1, "ready").copy(kind = "backlog", message = "2 hours ready to listen")
        val first = newlyReady(listOf(backlog), alreadySeen = null)

        assertTrue(first.first.isEmpty())
        assertEquals(setOf(backlog.id), first.second)
        assertTrue(newlyReady(listOf(backlog), alreadySeen = first.second).first.isEmpty())
    }

    @Test
    fun `a backlog alert arriving directly ready after an empty poll is announced once`() {
        val first = newlyReady(emptyList(), alreadySeen = null)
        val backlog = notice(1, "ready").copy(kind = "backlog", message = "2 hours ready to listen")

        val second = newlyReady(listOf(backlog), alreadySeen = first.second)
        assertEquals(listOf(backlog), second.first)
        assertEquals(setOf(backlog.id), second.second)
        assertTrue(newlyReady(listOf(backlog), alreadySeen = second.second).first.isEmpty())
    }

    @Test
    fun `a new ready backlog alert is announced alongside previously seen ready chapters`() {
        val chapter = notice(1, "ready")
        val first = newlyReady(listOf(chapter), alreadySeen = null)
        val backlog = chapter.copy(id = 2, kind = "backlog", message = "2 hours ready to listen")

        val second = newlyReady(listOf(chapter, backlog), alreadySeen = first.second)
        assertEquals(listOf(backlog), second.first)
        assertEquals(setOf(chapter.id, backlog.id), second.second)
        assertTrue(newlyReady(listOf(chapter, backlog), alreadySeen = second.second).first.isEmpty())
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
        assertEquals("Chapter 1", single?.second)

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
    fun `server wording is shared by the inbox and single system notification`() {
        for (kind in listOf("chapter", "backlog")) {
            val entry = notice(1, "ready").copy(
                kind = kind,
                backlogSeconds = 7200.5,
                message = "2 hours ready to listen",
            )

            assertEquals("2 hours ready to listen", entry.notificationBody())
            assertEquals("A Test Serial" to entry.notificationBody(), readyNotificationText(listOf(entry)))
        }
    }

    @Test
    fun `missing or blank server wording falls back to the chapter title`() {
        for (kind in listOf("chapter", "backlog")) {
            for (message in listOf(null, "", " \n\t")) {
                val entry = notice(1, "ready").copy(
                    kind = kind,
                    backlogSeconds = 7200.5,
                    message = message,
                )

                assertEquals("Chapter 1", entry.notificationBody())
                assertEquals("A Test Serial" to "Chapter 1", readyNotificationText(listOf(entry)))
            }
        }
    }

    @Test
    fun `nonblank server wording is preserved verbatim`() {
        val entry = notice(1, "ready").copy(message = "  Ready for you  ")

        assertEquals("  Ready for you  ", entry.notificationBody())
        assertEquals("  Ready for you  ", readyNotificationText(listOf(entry))?.second)
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

    @Test
    fun `old servers default to chapter notices with no backlog snapshot or message`() {
        val entry = decode(
            """
            {
              "notifications": [{
                "id": 1,
                "state": "ready",
                "dismissible": true,
                "playable": true,
                "fiction": {"id": 7, "title": "A Test Serial"},
                "chapter": {"id": 101, "title": "Chapter 1"}
              }]
            }
            """.trimIndent(),
        ).notifications.single()

        assertEquals("chapter", entry.kind)
        assertNull(entry.backlogSeconds)
        assertNull(entry.message)
        assertEquals("Chapter 1", entry.notificationBody())
        assertTrue(entry.playable)
        assertTrue(entry.dismissible)
    }

    @Test
    fun `backlog payload decodes its fractional snapshot and server wording without deriving actions`() {
        val entry = decode(
            """
            {
              "notifications": [{
                "id": 2,
                "kind": "backlog",
                "state": "ready",
                "backlog_seconds": 7200.5,
                "message": "2 hours ready to listen",
                "dismissible": false,
                "playable": false,
                "fiction": {"id": 7, "title": "A Test Serial"},
                "chapter": {"id": 101, "title": "Chapter 1"}
              }]
            }
            """.trimIndent(),
        ).notifications.single()

        assertEquals("backlog", entry.kind)
        assertEquals(7200.5, entry.backlogSeconds)
        assertEquals("2 hours ready to listen", entry.message)
        assertEquals("A Test Serial" to "2 hours ready to listen", readyNotificationText(listOf(entry)))
        assertFalse(entry.playable)
        assertFalse(entry.dismissible)
    }

    @Test
    fun `optional notification fields accept null or omission for either kind`() {
        for (kind in listOf("chapter", "backlog")) {
            val entries = decode(
                """
                {
                  "notifications": [
                    {"id": 1, "kind": "$kind", "backlog_seconds": null, "message": null},
                    {"id": 2, "kind": "$kind"}
                  ]
                }
                """.trimIndent(),
            ).notifications

            for (entry in entries) {
                assertEquals(kind, entry.kind)
                assertNull(entry.backlogSeconds)
                assertNull(entry.message)
            }
        }
    }

    @Test
    fun `chapter_number decodes as a float and as null`() {
        // The backend stores chapter_number as a float, so 12.0 is a real wire value; an unnumbered
        // interlude sends null. An Int adapter throws on 12.0 and takes the whole payload down.
        val response = decode(
            """
            {
              "notifications": [
                {
                  "id": 1,
                  "state": "ready",
                  "dismissible": true,
                  "playable": true,
                  "fiction": {"id": 7, "title": "A Test Serial"},
                  "chapter": {"id": 101, "title": "Chapter 12", "chapter_number": 12.0}
                },
                {
                  "id": 2,
                  "state": "pulled",
                  "fiction": {"id": 7, "title": "A Test Serial"},
                  "chapter": {"id": 102, "title": "Interlude", "chapter_number": null}
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(12.0, response.notifications[0].chapter.chapterNumber)
        assertEquals("Chapter 12  ·  ready to listen", response.notifications[0].detailLabel())
        assertNull(response.notifications[1].chapter.chapterNumber)
    }
}
