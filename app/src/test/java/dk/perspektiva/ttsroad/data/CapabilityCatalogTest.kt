package dk.perspektiva.ttsroad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings list of what a server can do.
 *
 * The panel exists because hiding a control the server cannot back is right and invisible: from the
 * user's side "this server is older than my app", "I am not an admin" and "this app is broken" all
 * look the same (#120). So the cases worth pinning are the ones where the list would quietly
 * mislead — a flag omitted, or a guess shown as a fact.
 */
class CapabilityCatalogTest {

    @Test
    fun `a flag this build has never heard of is still listed`() {
        // A server newer than the app is the normal case. Dropping its flag would make the panel
        // wrong about the one thing it is for.
        val rows = CapabilityCatalog.rows(mapOf("time_travel" to true))

        assertEquals(1, rows.size)
        assertEquals("time_travel", rows.single().key)
        assertEquals("time_travel", rows.single().label)
        assertTrue(rows.single().supported)
    }

    @Test
    fun `known flags come first, in a fixed order, with unknown ones after`() {
        val rows = CapabilityCatalog.rows(
            mapOf("zzz_unknown" to true, "bookmarks" to true, "readalong" to true),
        )

        assertEquals(listOf("readalong", "bookmarks", "zzz_unknown"), rows.map { it.key })
    }

    @Test
    fun `a server that said nothing produces no rows rather than a wall of No`() {
        // Not reaching a server is not the same as a server without features. Listing every
        // capability as unsupported would be a guess presented as fact.
        assertTrue(CapabilityCatalog.rows(emptyMap()).isEmpty())
    }

    @Test
    fun `known flags get a human label`() {
        assertEquals("Read along", CapabilityCatalog.label("readalong"))
        assertEquals("Up Next queue", CapabilityCatalog.label("queue"))
    }

    @Test
    fun `a supported flag the app does not use says so instead of claiming a tick`() {
        // These are true on the server and unused here. A bare "Yes" would promise the user a
        // feature that is not in the app.
        assertNotNull(CapabilityCatalog.note("delta_sync", supported = true))
        assertNotNull(CapabilityCatalog.note("live_events", supported = true))
    }

    @Test
    fun `a flag the app does use carries no qualifier`() {
        assertNull(CapabilityCatalog.note("readalong", supported = true))
        assertNull(CapabilityCatalog.note("bookmarks", supported = true))
        assertNull(CapabilityCatalog.note("queue", supported = true))
        // Listing exports shipped in #113, so the "not used yet" qualifier came off with it.
        assertNull(CapabilityCatalog.note("audiobook_export", supported = true))
    }

    @Test
    fun `an unsupported flag never carries a qualifier`() {
        // The note explains why a *supported* flag shows no feature. On an unsupported one it would
        // read as an excuse for something the server cannot do anyway.
        for (key in CapabilityCatalog.Order) {
            assertNull(key, CapabilityCatalog.note(key, supported = false))
        }
    }

    @Test
    fun `every ordered flag has a label that is not just its raw key`() {
        for (key in CapabilityCatalog.Order) {
            assertFalse(key, CapabilityCatalog.label(key) == key)
        }
    }

    @Test
    fun `the order list has no duplicates`() {
        assertEquals(CapabilityCatalog.Order.size, CapabilityCatalog.Order.toSet().size)
    }

    @Test
    fun `a non-boolean value is reported as unsupported rather than guessed at`() {
        val rows = CapabilityCatalog.rows(mapOf("readalong" to false))

        assertFalse(rows.single().supported)
        assertNull(rows.single().note)
    }
}
