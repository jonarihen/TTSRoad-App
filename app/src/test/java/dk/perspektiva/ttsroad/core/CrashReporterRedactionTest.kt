package dk.perspektiva.ttsroad.core

import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.protocol.Message
import io.sentry.protocol.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrashReporterRedactionTest {
    private val servers = listOf("https://home.example.com", "http://192.168.1.20:8000")

    @Test
    fun `breadcrumb data values are redacted, not just the message`() {
        val breadcrumb = Breadcrumb.http("https://home.example.com/api/mobile/fictions", "GET", 500).apply {
            message = "GET https://home.example.com/api/mobile/fictions"
            setData("host", "home.example.com")
            setData("old", "http://192.168.1.20:8000/audio/1.mp3")
            setData("count", 3)
        }

        val redacted = redactBreadcrumb(breadcrumb, servers)

        assertEquals("GET $RedactedServer/api/mobile/fictions", redacted.message)
        assertEquals("$RedactedServer/api/mobile/fictions", redacted.data["url"])
        assertEquals(RedactedServer, redacted.data["host"])
        assertEquals("$RedactedServer/audio/1.mp3", redacted.data["old"])
        assertEquals(3, redacted.data["count"])
        assertEquals(500, redacted.data["status_code"])
    }

    @Test
    fun `an event is redacted for every remembered server`() {
        val event = SentryEvent().apply {
            message = Message().apply { formatted = "now https://home.example.com, before 192.168.1.20:8000" }
            request = Request().apply { url = "http://192.168.1.20:8000/api/mobile/library" }
        }

        val redacted = redactEvent(event, servers)

        assertEquals("now $RedactedServer, before $RedactedServer", redacted.message?.formatted)
        assertEquals("$RedactedServer/api/mobile/library", redacted.request?.url)
    }

    @Test
    fun `no remembered server leaves a breadcrumb untouched`() {
        val breadcrumb = Breadcrumb("plain").apply { setData("host", "home.example.com") }

        val redacted = redactBreadcrumb(breadcrumb, emptyList())

        assertEquals("home.example.com", redacted.data["host"])
        assertNull(redacted.data["url"])
    }
}
