package dk.perspektiva.ttsroad.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlsTest {

    @Test
    fun `rewrites host when BASE_URL differs from the connected address`() {
        assertEquals(
            "http://192.168.1.10:8000/media/covers/1.jpg",
            ServerUrls.rewriteHost(
                url = "https://ttsroad.example.com/media/covers/1.jpg",
                serverUrl = "http://192.168.1.10:8000/",
            ),
        )
    }

    @Test
    fun `keeps path, query and fragment intact`() {
        assertEquals(
            "https://host.example/media/cover.jpg?v=3#frag",
            ServerUrls.rewriteHost(
                url = "https://other.example:9000/media/cover.jpg?v=3#frag",
                serverUrl = "https://host.example/",
            ),
        )
    }

    @Test
    fun `resolves relative urls against the connected server`() {
        assertEquals(
            "http://10.0.2.2:8000/media/cover.jpg",
            ServerUrls.rewriteHost("/media/cover.jpg", "http://10.0.2.2:8000/"),
        )
        assertEquals(
            "http://10.0.2.2:8000/media/cover.jpg",
            ServerUrls.rewriteHost("media/cover.jpg", "http://10.0.2.2:8000/"),
        )
    }

    @Test
    fun `tolerates unencoded characters that java-net-URI would reject`() {
        assertEquals(
            "https://host.example/media/A Book/cover 1.jpg",
            ServerUrls.rewriteHost(
                url = "https://base.example/media/A Book/cover 1.jpg",
                serverUrl = "https://host.example/",
            ),
        )
    }

    @Test
    fun `returns the url unchanged when the server url is unusable`() {
        val url = "https://base.example/media/cover.jpg"
        assertEquals(url, ServerUrls.rewriteHost(url, serverUrl = null))
        assertEquals(url, ServerUrls.rewriteHost(url, serverUrl = ""))
        assertEquals(url, ServerUrls.rewriteHost(url, serverUrl = "   "))
        assertEquals(url, ServerUrls.rewriteHost(url, serverUrl = "not-a-url"))
        assertEquals(url, ServerUrls.rewriteHost(url, serverUrl = "https://"))
    }

    @Test
    fun `leaves a blank url alone rather than inventing a server root`() {
        assertEquals("", ServerUrls.rewriteHost("", "https://host.example/"))
    }

    @Test
    fun `rewriteHostOrNull passes through null and blank covers`() {
        assertNull(ServerUrls.rewriteHostOrNull(null, "https://host.example/"))
        assertNull(ServerUrls.rewriteHostOrNull("", "https://host.example/"))
        assertNull(ServerUrls.rewriteHostOrNull("   ", "https://host.example/"))
        assertEquals(
            "https://host.example/cover.jpg",
            ServerUrls.rewriteHostOrNull("https://base.example/cover.jpg", "https://host.example/"),
        )
    }

    @Test
    fun `external cover keeps its original host`() {
        assertEquals(
            "https://www.royalroadcdn.com/public/covers-full/example.jpg",
            ServerUrls.resolveCoverOrNull(
                "https://www.royalroadcdn.com/public/covers-full/example.jpg",
                "https://ttsroad.example.com/",
            ),
        )
    }

    @Test
    fun `server-owned absolute cover follows the connected host`() {
        assertEquals(
            "https://ttsroad.example.com/cover/book.jpg",
            ServerUrls.resolveCoverOrNull(
                "https://configured.example.com/cover/book.jpg",
                "https://ttsroad.example.com/",
            ),
        )
    }

    @Test
    fun `relative cover resolves against the connected host`() {
        assertEquals(
            "https://ttsroad.example.com/cover/book.jpg",
            ServerUrls.resolveCoverOrNull("/cover/book.jpg", "https://ttsroad.example.com/"),
        )
    }

    @Test
    fun `resolveCoverOrNull rejects missing cover values`() {
        assertNull(ServerUrls.resolveCoverOrNull(null, "https://ttsroad.example.com/"))
        assertNull(ServerUrls.resolveCoverOrNull("   ", "https://ttsroad.example.com/"))
    }
}
