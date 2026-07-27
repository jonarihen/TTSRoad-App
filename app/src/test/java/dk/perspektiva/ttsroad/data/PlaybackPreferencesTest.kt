package dk.perspektiva.ttsroad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DataStore itself needs an Android context, but the part that can actually be wrong is the
 * sanitising: a value written by a different build, or a corrupted file, must not be able to hand
 * the player a speed of 0 or a negative skip.
 */
class PlaybackPreferencesTest {

    @Test
    fun `a stored speed inside the sane range is kept as-is`() {
        for (preset in SpeedPresets) {
            assertEquals(preset, sanitizeSpeed(preset), 0.0001f)
        }
    }

    @Test
    fun `an out-of-range speed is clamped rather than reset`() {
        // Clamped, not snapped to a preset: a value written by a future build with finer steps
        // should survive a downgrade instead of silently reverting to 1.0x.
        assertEquals(0.5f, sanitizeSpeed(0.1f), 0.0001f)
        assertEquals(3.0f, sanitizeSpeed(9.0f), 0.0001f)
        assertEquals(1.35f, sanitizeSpeed(1.35f), 0.0001f)
    }

    @Test
    fun `a speed of zero cannot reach the player`() {
        assertTrue(sanitizeSpeed(0f) > 0f)
        assertTrue(sanitizeSpeed(-2f) > 0f)
    }

    @Test
    fun `NaN falls back to the default instead of poisoning the player`() {
        assertEquals(DefaultSpeed, sanitizeSpeed(Float.NaN), 0.0001f)
    }

    @Test
    fun `every offered skip interval round-trips`() {
        for (option in SkipIntervalOptionsMs) {
            assertEquals(option, sanitizeSkipIntervalMs(option))
        }
    }

    @Test
    fun `an unrecognised skip interval falls back to the default`() {
        assertEquals(DefaultSkipIntervalMs, sanitizeSkipIntervalMs(0L))
        assertEquals(DefaultSkipIntervalMs, sanitizeSkipIntervalMs(-30_000L))
        assertEquals(DefaultSkipIntervalMs, sanitizeSkipIntervalMs(7_000L))
    }

    @Test
    fun `the default skip interval is one of the offered options`() {
        assertTrue(DefaultSkipIntervalMs in SkipIntervalOptionsMs)
    }

    @Test
    fun `the default speed is one of the offered presets`() {
        assertTrue(SpeedPresets.any { kotlin.math.abs(it - DefaultSpeed) < 0.0001f })
    }

    @Test
    fun `skip labels read as seconds until they are whole minutes`() {
        assertEquals("10s", formatSkipInterval(10_000L))
        assertEquals("15s", formatSkipInterval(15_000L))
        assertEquals("30s", formatSkipInterval(30_000L))
        assertEquals("45s", formatSkipInterval(45_000L))
        assertEquals("1m", formatSkipInterval(60_000L))
    }
}
