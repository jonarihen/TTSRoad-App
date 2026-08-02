package dk.perspektiva.ttsroad.data

import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSessionsTest {

    /** UTC and a fixed locale so the assertions do not depend on where the build runs. */
    private fun format(iso: String?) =
        formatServerTimestamp(iso, zone = ZoneOffset.UTC, locale = Locale.UK)

    @Test
    fun `an ISO instant renders as a readable local date and time`() {
        assertEquals("26 Oct 2026, 12:00", format("2026-10-26T12:00:00Z"))
    }

    @Test
    fun `an offset timestamp is converted, not printed as written`() {
        // 14:30+02:00 is 12:30 UTC, and UTC is what this test formats in.
        assertEquals("26 Oct 2026, 12:30", format("2026-10-26T14:30:00+02:00"))
    }

    /** FastAPI serialises naive datetimes without a zone; this backend stores them as UTC. */
    @Test
    fun `a zoneless timestamp is read as UTC rather than rejected`() {
        assertEquals("26 Oct 2026, 12:00", format("2026-10-26T12:00:00"))
    }

    @Test
    fun `fractional seconds do not defeat the parser`() {
        assertEquals("26 Oct 2026, 12:00", format("2026-10-26T12:00:00.123456Z"))
    }

    @Test
    fun `a missing or unusable timestamp yields null instead of raw JSON`() {
        for (input in listOf(null, "", "   ", "yesterday", "2026-13-45T99:99:99Z", "0")) {
            assertNull("input: $input", format(input))
        }
    }

    @Test
    fun `expiry is described relative to now`() {
        val now = instantMillis("2026-08-01T12:00:00Z")

        assertEquals("expires in 90 days", formatExpiresIn("2026-10-30T12:00:00Z", now))
        assertEquals("expires in 1 day", formatExpiresIn("2026-08-02T12:00:00Z", now))
        assertEquals("expires today", formatExpiresIn("2026-08-01T23:00:00Z", now))
    }

    @Test
    fun `an expiry in the past reads as expired`() {
        val now = instantMillis("2026-08-01T12:00:00Z")

        assertEquals("expired", formatExpiresIn("2026-07-30T12:00:00Z", now))
        assertEquals("expired", formatExpiresIn("2026-08-01T11:59:00Z", now))
    }

    @Test
    fun `an unusable expiry is simply not described`() {
        val now = instantMillis("2026-08-01T12:00:00Z")

        assertNull(formatExpiresIn(null, now))
        assertNull(formatExpiresIn("", now))
        assertNull(formatExpiresIn("soon", now))
    }

    @Test
    fun `a device with nothing but an id still describes itself`() {
        val device = DeviceSession(id = 4)

        assertEquals("Unnamed device", device.resolvedName)
        assertTrue(device.resolvedName.isNotBlank())
    }

    @Test
    fun `a blank device name is treated as missing`() {
        assertEquals("Unnamed device", DeviceSession(id = 4, deviceName = "   ").resolvedName)
        assertEquals("Pixel 8", DeviceSession(id = 4, deviceName = "Pixel 8").resolvedName)
    }

    private fun instantMillis(iso: String) = java.time.Instant.parse(iso).toEpochMilli()
}
