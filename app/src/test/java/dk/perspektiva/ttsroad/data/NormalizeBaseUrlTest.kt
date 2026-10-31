package dk.perspektiva.ttsroad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class NormalizeBaseUrlTest {
    @Test
    fun `keeps a well-formed url and appends a single trailing slash`() {
        assertEquals("https://ttsroad.example.com/", normalizeBaseUrl("https://ttsroad.example.com"))
        assertEquals("https://ttsroad.example.com/", normalizeBaseUrl("https://ttsroad.example.com/"))
        assertEquals("http://192.168.1.5:8000/", normalizeBaseUrl("http://192.168.1.5:8000"))
    }

    @Test
    fun `trims surrounding whitespace pasted with the url`() {
        assertEquals("https://ttsroad.example.com/", normalizeBaseUrl("  https://ttsroad.example.com  "))
    }

    @Test
    fun `collapses several trailing slashes`() {
        assertEquals("https://ttsroad.example.com/", normalizeBaseUrl("https://ttsroad.example.com///"))
        assertEquals("https://home.example.dk/ttsroad/", normalizeBaseUrl("https://home.example.dk/ttsroad//"))
    }

    @Test
    fun `collapses a doubled scheme from pasting into a pre-filled field`() {
        assertEquals("https://home.example.dk/", normalizeBaseUrl("https://https://home.example.dk"))
        // The pasted address is the one the user meant, so its scheme wins.
        assertEquals("http://100.64.1.2:8000/", normalizeBaseUrl("https://http://100.64.1.2:8000"))
    }

    @Test
    fun `lowercases a scheme the keyboard capitalised`() {
        assertEquals("https://home.example.dk/", normalizeBaseUrl("Https://home.example.dk"))
        assertEquals("http://nas:8000/", normalizeBaseUrl("HTTP://nas:8000"))
    }

    @Test
    fun `assumes https for a bare host`() {
        assertEquals("https://ttsroad.example.com/", normalizeBaseUrl("ttsroad.example.com"))
        assertEquals("https://192.168.1.5:8000/", normalizeBaseUrl("192.168.1.5:8000"))
    }

    @Test
    fun `rejects other schemes and names the requirement`() {
        // The message is what the login screen renders in its error slot, so the
        // wording is part of the contract, not just an assertion detail.
        val error = assertThrows(IllegalArgumentException::class.java) {
            normalizeBaseUrl("ftp://ttsroad.example.com")
        }
        assertEquals("Server URL must start with http:// or https://", error.message)
    }

    @Test
    fun `rejects an empty address`() {
        for (input in listOf("", "   ", "https://", "https:///")) {
            assertThrows(IllegalArgumentException::class.java) { normalizeBaseUrl(input) }
        }
    }

    @Test
    fun `probes only a host that could be real`() {
        assertEquals("https://home.example.dk/", probeableBaseUrl("home.example.dk"))
        assertEquals("http://192.168.1.5:8000/", probeableBaseUrl("http://192.168.1.5:8000"))
        assertEquals("http://localhost:8000/", probeableBaseUrl("http://localhost:8000"))
        assertEquals("http://[::1]:8000/", probeableBaseUrl("http://[::1]:8000"))
        assertEquals("https://home.example.dk/ttsroad/", probeableBaseUrl("https://home.example.dk/ttsroad"))

        for (partial in listOf(
            "",
            "   ",
            "https://",
            "http://",
            "https:///",
            "https://home.",
            "https://home.e",
            "https://192.168.1",
            "https://192",
            "https://home.example.dk:",
            "http://nas:",
            "https://nas-",
            "https://-nas",
            "ftp://home.example.dk",
        )) {
            assertNull(partial, probeableBaseUrl(partial))
        }
    }

    @Test
    fun `probes single label LAN hosts`() {
        assertEquals("http://nas:8000/", probeableBaseUrl("http://nas:8000"))
        assertEquals("https://nas/", probeableBaseUrl("https://nas"))
        assertEquals("https://nas/", probeableBaseUrl("nas"))
        assertEquals("http://NAS/", probeableBaseUrl("HTTP://NAS"))
        assertEquals("http://media-box2:8000/ttsroad/", probeableBaseUrl("http://media-box2:8000/ttsroad"))
        assertEquals("http://1nas:8000/", probeableBaseUrl("http://1nas:8000"))
    }
}
