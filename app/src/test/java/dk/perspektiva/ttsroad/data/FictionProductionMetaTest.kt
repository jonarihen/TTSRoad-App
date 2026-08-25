package dk.perspektiva.ttsroad.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a fiction is *produced*, as opposed to what it is about.
 *
 * `_fiction_payload` dumps the whole `FictionResponse`, so voice, rate, enabled, source_type and
 * last_polled_at have been reaching the phone since before the app existed. [FictionSummary] did
 * not declare them, so all five were dropped and the fiction screen could not say which narrator
 * a book used or that polling had been switched off for it.
 */
class FictionProductionMetaTest {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(FictionSummary::class.java)

    private fun decode(json: String): FictionSummary =
        requireNotNull(adapter.fromJson(json)) { "adapter returned null for $json" }

    @Test
    fun `decodes the five fields the client used to drop`() {
        val fiction = decode(
            """
            {
              "id": 3,
              "title": "The Lighthouse",
              "voice": "en-GB-RyanNeural",
              "rate": "+8%",
              "enabled": true,
              "source_type": "patreon",
              "last_polled_at": "2026-08-25T05:00:00Z"
            }
            """.trimIndent(),
        )

        assertEquals("en-GB-RyanNeural", fiction.voice)
        assertEquals("+8%", fiction.rate)
        assertEquals(true, fiction.enabled)
        assertEquals("patreon", fiction.sourceType)
        assertFalse(fiction.isPaused)
    }

    @Test
    fun `a server that sends none of them still decodes and reads as not paused`() {
        val fiction = decode("""{"id": 3, "title": "The Lighthouse"}""")

        assertNull(fiction.voice)
        assertNull(fiction.rate)
        assertNull(fiction.enabled)
        assertNull(fiction.sourceType)
        assertNull(fiction.lastPolledAt)
        // The important half: "we were not told" must not render as a paused warning on every book.
        assertFalse(fiction.isPaused)
        assertNull(fiction.sourceTypeLabel)
        assertNull(fiction.lastPolledLabel())
    }

    @Test
    fun `only an explicit false is paused`() {
        assertTrue(FictionSummary(id = 1, enabled = false).isPaused)
        assertFalse(FictionSummary(id = 1, enabled = true).isPaused)
        assertFalse(FictionSummary(id = 1, enabled = null).isPaused)
    }

    @Test
    fun `royal road is not worth labelling but the others are`() {
        assertNull(FictionSummary(id = 1, sourceType = SourceType.RoyalRoad).sourceTypeLabel)
        assertNull(FictionSummary(id = 1, sourceType = "").sourceTypeLabel)
        assertEquals("EPUB import", FictionSummary(id = 1, sourceType = SourceType.Epub).sourceTypeLabel)
        assertEquals("Patreon", FictionSummary(id = 1, sourceType = SourceType.Patreon).sourceTypeLabel)
    }

    @Test
    fun `an adapter this build has never heard of is shown rather than hidden`() {
        // New sources land server-side on their own schedule; a raw key beats saying nothing.
        assertEquals("wanderinginn", FictionSummary(id = 1, sourceType = "wanderinginn").sourceTypeLabel)
    }

    @Test
    fun `poll age is reported at the coarseness the question deserves`() {
        val now = Instant.parse("2026-08-25T12:00:00Z")
        fun polledAt(iso: String) = FictionSummary(id = 1, lastPolledAt = iso).lastPolledLabel(now)

        assertEquals("polled just now", polledAt("2026-08-25T11:59:30Z"))
        assertEquals("polled 20m ago", polledAt("2026-08-25T11:40:00Z"))
        assertEquals("polled 5h ago", polledAt("2026-08-25T07:00:00Z"))
        assertEquals("polled 3d ago", polledAt("2026-08-22T12:00:00Z"))
    }

    @Test
    fun `a server clock ahead of the phone does not report a negative age`() {
        val now = Instant.parse("2026-08-25T12:00:00Z")
        val fiction = FictionSummary(id = 1, lastPolledAt = "2026-08-25T12:05:00Z")

        assertEquals("polled just now", fiction.lastPolledLabel(now))
    }

    @Test
    fun `an unreadable timestamp is dropped rather than thrown`() {
        // One bad date should cost its own line, not the whole screen.
        assertNull(FictionSummary(id = 1, lastPolledAt = "not a date").lastPolledLabel())
        assertNull(FictionSummary(id = 1, lastPolledAt = "").lastPolledLabel())
    }

    @Test
    fun `a naive server timestamp is read as UTC`() {
        // The backend stores naive UTC and _utc_iso converts at the edge, but not every payload
        // has always carried the Z. parseServerInstant handles all three spellings.
        val now = Instant.parse("2026-08-25T12:00:00Z")
        val fiction = FictionSummary(id = 1, lastPolledAt = "2026-08-25T11:00:00")

        assertEquals("polled 1h ago", fiction.lastPolledLabel(now))
    }
}
