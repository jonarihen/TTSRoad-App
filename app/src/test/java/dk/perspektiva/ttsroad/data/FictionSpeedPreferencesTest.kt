package dk.perspektiva.ttsroad.data

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-fiction speed, as encoding and as the decision it feeds.
 *
 * The decision is one line and the encoding is two, which is exactly why they are worth pinning: a
 * speed that silently falls back to the global is indistinguishable from one that was never set,
 * and the way that happens is a stored value this build cannot read back.
 */
class FictionSpeedPreferencesTest {

    private val defaultLocale = Locale.getDefault()

    @After
    fun tearDown() {
        Locale.setDefault(defaultLocale)
    }

    // --- which speed wins ---------------------------------------------------------------------

    @Test
    fun `a book with no override plays at the global speed`() {
        assertEquals(1.5f, effectiveSpeed(1.5f, emptyMap(), fictionId = 7), 0.001f)
    }

    @Test
    fun `a book with an override plays at its own speed`() {
        assertEquals(1.2f, effectiveSpeed(1.5f, mapOf(7 to 1.2f), fictionId = 7), 0.001f)
    }

    @Test
    fun `one book's override does not reach another`() {
        assertEquals(1.5f, effectiveSpeed(1.5f, mapOf(7 to 1.2f), fictionId = 8), 0.001f)
    }

    @Test
    fun `an item with no fiction id takes the global speed rather than a guess`() {
        assertEquals(1.5f, effectiveSpeed(1.5f, mapOf(7 to 1.2f), fictionId = null), 0.001f)
    }

    @Test
    fun `an override outside the allowed range is clamped rather than played`() {
        // A speed the player would refuse must not reach it just because it came from the map.
        // The bounds are private to PlaybackPreferences on purpose, so they are spelled out here
        // rather than widened for a test — sanitizeSpeed is the contract being checked.
        assertEquals(sanitizeSpeed(99f), effectiveSpeed(1f, mapOf(7 to 99f), fictionId = 7), 0.001f)
        assertEquals(3.0f, effectiveSpeed(1f, mapOf(7 to 99f), fictionId = 7), 0.001f)
        assertEquals(0.5f, effectiveSpeed(1f, mapOf(7 to 0.01f), fictionId = 7), 0.001f)
    }

    // --- the stored form ----------------------------------------------------------------------

    @Test
    fun `an empty map stores nothing and reads back as nothing`() {
        assertEquals("", encodeFictionSpeeds(emptyMap()))
        assertTrue(decodeFictionSpeeds("").isEmpty())
        assertTrue(decodeFictionSpeeds(null).isEmpty())
    }

    @Test
    fun `a map round-trips`() {
        val speeds = mapOf(3 to 1.25f, 7 to 0.9f, 11 to 2.0f)

        val decoded = decodeFictionSpeeds(encodeFictionSpeeds(speeds))

        assertEquals(speeds.keys, decoded.keys)
        speeds.forEach { (id, speed) -> assertEquals(speed, decoded.getValue(id), 0.005f) }
    }

    @Test
    fun `a comma-decimal locale cannot make a stored speed unreadable`() {
        // The failure this guards: a phone set to a comma-decimal locale writes "7=1,25", which
        // parses back as nothing, and the book silently reverts to the global speed. Nobody would
        // report that as a bug — it looks exactly like never having set it.
        Locale.setDefault(Locale.GERMANY)

        val decoded = decodeFictionSpeeds(encodeFictionSpeeds(mapOf(7 to 1.25f)))

        assertEquals(1.25f, decoded.getValue(7), 0.005f)
    }

    @Test
    fun `a corrupted entry is dropped without taking the rest with it`() {
        val decoded = decodeFictionSpeeds("3=1.25,nonsense,=2.0,9=,11=2.0")

        assertEquals(setOf(3, 11), decoded.keys)
    }

    @Test
    fun `a stored speed outside the range is clamped on the way in`() {
        assertEquals(sanitizeSpeed(99f), decodeFictionSpeeds("7=99").getValue(7), 0.001f)
    }

    @Test
    fun `a non-positive fiction id is never stored`() {
        assertEquals("", encodeFictionSpeeds(mapOf(0 to 1.5f, -3 to 1.5f)))
        assertTrue(decodeFictionSpeeds("0=1.5,-3=1.5").isEmpty())
    }

    @Test
    fun `entries are stored in a stable order, so the same map is the same string`() {
        val one = encodeFictionSpeeds(linkedMapOf(11 to 2.0f, 3 to 1.25f))
        val other = encodeFictionSpeeds(linkedMapOf(3 to 1.25f, 11 to 2.0f))

        assertEquals(one, other)
    }
}
