package dk.perspektiva.ttsroad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * These are the web console's steps, and they have to stay the web console's steps: the two
     * clients play the same files against the same progress state, so "the speed I listen at" must
     * be literally selectable in both. The app used to offer 1.2x where the web offers 1.25x, which
     * meant one of the two could not be matched from the other.
     */
    @Test
    fun `the presets match the steps the web console offers`() {
        assertEquals(listOf(0.8f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f), SpeedPresets)
    }

    @Test
    fun `every preset is inside the range sanitising allows`() {
        // A preset the sanitiser would clamp is a preset the picker cannot actually select.
        for (preset in SpeedPresets) {
            assertEquals(preset, sanitizeSpeed(preset), 0.0001f)
        }
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

/**
 * The audio half of the same store. Split into its own class so the speed/skip cases above and the
 * skip-silence/boost cases here stay independently readable.
 */
class AudioPreferencesTest {

    @Test
    fun `off means no gain at all, not a small one`() {
        assertEquals(0, VolumeBoost.Off.gainMillibels)
    }

    @Test
    fun `boost levels increase strictly`() {
        val gains = VolumeBoost.entries.map { it.gainMillibels }

        assertEquals(gains.sorted(), gains)
        assertEquals(gains.distinct().size, gains.size)
    }

    /**
     * LoudnessEnhancer applies raw gain with no limiter, so a chapter already near full scale will
     * clip. 10 dB is the point past which that becomes audible on ordinary TTS output, and clipping
     * is a worse problem than the uneven loudness being fixed.
     */
    @Test
    fun `no boost level exceeds ten decibels`() {
        for (boost in VolumeBoost.entries) {
            assertTrue("${boost.name} is ${boost.gainMillibels}mB", boost.gainMillibels <= 1_000)
        }
    }

    @Test
    fun `every boost level has a label to show`() {
        for (boost in VolumeBoost.entries) {
            assertTrue(boost.label.isNotBlank())
        }
    }

    @Test
    fun `a stored boost name round-trips`() {
        for (boost in VolumeBoost.entries) {
            assertEquals(boost, volumeBoostOf(boost.name))
        }
    }

    @Test
    fun `an unknown or missing stored boost falls back to off`() {
        // A value written by a build with a different set of levels must not throw while reading
        // preferences - that would take the whole settings screen down with it.
        assertEquals(VolumeBoost.Off, volumeBoostOf(null))
        assertEquals(VolumeBoost.Off, volumeBoostOf(""))
        assertEquals(VolumeBoost.Off, volumeBoostOf("Extreme"))
        assertEquals(VolumeBoost.Off, volumeBoostOf("low"))
    }

    /**
     * On by default made the app sound faster than the web console on the identical file at the
     * same nominal 1.0x, because the web player has no equivalent and plays every pause in full.
     * The processing stays available; it just isn't applied to someone who never asked for it.
     */
    @Test
    fun `skip silence defaults off, so the app and the web console read at the same pace`() {
        assertFalse(DefaultSkipSilence)
        assertFalse(PlaybackPrefs().skipSilence)
    }

    @Test
    fun `boost defaults off, so nothing is altered until asked`() {
        assertEquals(VolumeBoost.Off, PlaybackPrefs().volumeBoost)
    }
}
