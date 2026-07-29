package dk.perspektiva.ttsroad.data

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixed clock so these read the same at 03:00 as at 15:00. */
private val Now: Instant = Instant.parse("2026-07-29T12:00:00Z")

class DeviceSessionsTest {
    @Test
    fun `timestamps render in the given zone`() {
        assertEquals(
            "29 Jul 2026, 12:00",
            deviceTimestampLabel("2026-07-29T12:00:00Z", ZoneId.of("UTC")),
        )
        assertEquals(
            "29 Jul 2026, 14:00",
            deviceTimestampLabel("2026-07-29T12:00:00Z", ZoneId.of("Europe/Copenhagen")),
        )
    }

    @Test
    fun `microsecond precision from the server parses`() {
        assertEquals(
            "29 Jul 2026, 12:00",
            deviceTimestampLabel("2026-07-29T12:00:00.123456Z", ZoneId.of("UTC")),
        )
    }

    @Test
    fun `a missing or unreadable timestamp renders as a dash`() {
        listOf(null, "", "  ", "yesterday", "2026-07-29").forEach {
            assertEquals("-", deviceTimestampLabel(it, ZoneId.of("UTC")))
            assertEquals("-", deviceLastUsedLabel(it, Now))
            assertEquals("-", deviceExpiryLabel(it, Now))
        }
    }

    @Test
    fun `last used counts backwards from now`() {
        assertEquals("Just now", deviceLastUsedLabel("2026-07-29T11:59:30Z", Now))
        assertEquals("12 min ago", deviceLastUsedLabel("2026-07-29T11:48:00Z", Now))
        assertEquals("5h ago", deviceLastUsedLabel("2026-07-29T07:00:00Z", Now))
        assertEquals("Yesterday", deviceLastUsedLabel("2026-07-28T11:00:00Z", Now))
        assertEquals("9 days ago", deviceLastUsedLabel("2026-07-20T11:00:00Z", Now))
    }

    @Test
    fun `a phone whose clock runs fast does not read as the future`() {
        assertEquals("Just now", deviceLastUsedLabel("2026-07-29T12:05:00Z", Now))
    }

    @Test
    fun `expiry counts forward and says so when it has passed`() {
        // The 90-day window a fresh login gets.
        assertEquals("Expires in 90 days", deviceExpiryLabel("2026-10-27T12:00:00Z", Now))
        assertEquals("Expires tomorrow", deviceExpiryLabel("2026-07-30T13:00:00Z", Now))
        assertEquals("Expires in 6h", deviceExpiryLabel("2026-07-29T18:00:00Z", Now))
        assertEquals("Expires in 30 min", deviceExpiryLabel("2026-07-29T12:30:00Z", Now))
        assertEquals("Expired", deviceExpiryLabel("2026-07-01T12:00:00Z", Now))
        assertEquals("Expired", deviceExpiryLabel("2026-07-29T12:00:00Z", Now))
    }

    @Test
    fun `only an active session is offered a revoke button`() {
        assertTrue(DeviceSession(id = 1, status = "active").isActive)
        assertFalse(DeviceSession(id = 2, status = "revoked").isActive)
        assertFalse(DeviceSession(id = 3, status = "expired").isActive)
    }
}
