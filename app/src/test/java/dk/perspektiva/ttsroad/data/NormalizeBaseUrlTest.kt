package dk.perspektiva.ttsroad.data

import org.junit.Assert.assertEquals
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
    fun `rejects a host with no scheme and names the requirement`() {
        // The message is what the login screen renders in its error slot, so the
        // wording is part of the contract, not just an assertion detail.
        for (input in listOf("192.168.1.5:8000", "ttsroad.example.com", "ftp://ttsroad.example.com", "")) {
            val error = assertThrows(IllegalArgumentException::class.java) { normalizeBaseUrl(input) }
            assertEquals("Server URL must start with http:// or https://", error.message)
        }
    }
}
