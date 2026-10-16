package dk.perspektiva.ttsroad.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `isSameOrigin accepts matching scheme host and port`() {
        assertTrue(
            ServerUrls.isSameOrigin(
                "https://ttsroad.example.com/audio/1.mp3",
                "https://ttsroad.example.com/",
            ),
        )
        assertTrue(
            ServerUrls.isSameOrigin(
                "https://ttsroad.example.com:8443/audio/1.mp3",
                "https://ttsroad.example.com:8443",
            ),
        )
        assertTrue(
            ServerUrls.isSameOrigin(
                "https://ttsroad.example.com:443/audio/1.mp3",
                "https://ttsroad.example.com",
            ),
        )
    }

    @Test
    fun `isSameOrigin rejects mismatched host or port or scheme`() {
        assertFalse(
            ServerUrls.isSameOrigin(
                "https://evil.example.com/audio/1.mp3",
                "https://ttsroad.example.com/",
            ),
        )
        assertFalse(
            ServerUrls.isSameOrigin(
                "http://ttsroad.example.com/audio/1.mp3",
                "https://ttsroad.example.com/",
            ),
        )
        assertFalse(
            ServerUrls.isSameOrigin(
                "https://ttsroad.example.com:8000/audio/1.mp3",
                "https://ttsroad.example.com:9000/",
            ),
        )
    }

    @Test
    fun `isSameOrigin rejects blank or invalid inputs`() {
        assertFalse(ServerUrls.isSameOrigin(null, "https://ttsroad.example.com/"))
        assertFalse(ServerUrls.isSameOrigin("", "https://ttsroad.example.com/"))
        assertFalse(ServerUrls.isSameOrigin("https://ttsroad.example.com/audio/1.mp3", null))
        assertFalse(ServerUrls.isSameOrigin("https://ttsroad.example.com/audio/1.mp3", ""))
        assertFalse(ServerUrls.isSameOrigin("not a url", "https://ttsroad.example.com/"))
        assertFalse(ServerUrls.isSameOrigin("https://ttsroad.example.com/audio/1.mp3", "not a url"))
    }

    @Test
    fun `rewriteAudioUrlOrNull rewrites onto the server origin`() {
        assertEquals(
            "https://ttsroad.example.com/audio/1.mp3",
            ServerUrls.rewriteAudioUrlOrNull(
                url = "https://base.example/audio/1.mp3",
                serverUrl = "https://ttsroad.example.com/",
            ),
        )
        assertEquals(
            "https://ttsroad.example.com/audio/1.mp3",
            ServerUrls.rewriteAudioUrlOrNull(
                url = "/audio/1.mp3",
                serverUrl = "https://ttsroad.example.com/",
            ),
        )
    }

    @Test
    fun `rewriteAudioUrlOrNull fails closed when server url is unusable`() {
        val url = "https://base.example/audio/1.mp3"
        assertNull(ServerUrls.rewriteAudioUrlOrNull(url, serverUrl = null))
        assertNull(ServerUrls.rewriteAudioUrlOrNull(url, serverUrl = ""))
        assertNull(ServerUrls.rewriteAudioUrlOrNull(url, serverUrl = "   "))
        assertNull(ServerUrls.rewriteAudioUrlOrNull(url, serverUrl = "not-a-url"))
        assertNull(ServerUrls.rewriteAudioUrlOrNull(url, serverUrl = "https://"))
        assertNull(ServerUrls.rewriteAudioUrlOrNull(null, serverUrl = "https://ttsroad.example.com/"))
        assertNull(ServerUrls.rewriteAudioUrlOrNull("", serverUrl = "https://ttsroad.example.com/"))
    }

    @Test
    fun `audio keeps a reverse-proxy path prefix from the login url`() {
        val server = "https://home.example.dk/ttsroad/"
        // BASE_URL pointing at the backend's internal address knows nothing of /ttsroad.
        assertEquals(
            "https://home.example.dk/ttsroad/audio/12.mp3",
            ServerUrls.rewriteAudioUrlOrNull("http://127.0.0.1:8000/audio/12.mp3", server),
        )
        // Unset BASE_URL: a root-relative path.
        assertEquals(
            "https://home.example.dk/ttsroad/audio/12.mp3?v=3",
            ServerUrls.rewriteAudioUrlOrNull("/audio/12.mp3?v=3", server),
        )
        // BASE_URL already configured with the public subpath is not prefixed twice.
        assertEquals(
            "https://home.example.dk/ttsroad/audio/12.mp3",
            ServerUrls.rewriteAudioUrlOrNull("https://public.example.dk/ttsroad/audio/12.mp3", server),
        )
    }

    @Test
    fun `rewriteHost keeps the prefix for relative and absolute urls`() {
        val server = "https://home.example.dk/apps/ttsroad/"
        assertEquals(
            "https://home.example.dk/apps/ttsroad/feeds/abc.xml",
            ServerUrls.rewriteHost("feeds/abc.xml", server),
        )
        assertEquals(
            "https://home.example.dk/apps/ttsroad/feeds/abc.xml#top",
            ServerUrls.rewriteHost("http://10.0.0.2:8000/feeds/abc.xml#top", server),
        )
        // A path that merely starts with the same letters is not already prefixed.
        assertEquals(
            "https://home.example.dk/apps/ttsroad/apps/ttsroadx/a",
            ServerUrls.rewriteHost("/apps/ttsroadx/a", server),
        )
    }

    @Test
    fun `server covers under a subpath are recognised and keep the prefix`() {
        val server = "https://home.example.dk/ttsroad/"
        assertEquals(
            "https://home.example.dk/ttsroad/cover/7.jpg",
            ServerUrls.resolveCoverOrNull("/cover/7.jpg", server),
        )
        assertEquals(
            "https://home.example.dk/ttsroad/cover/7.jpg",
            ServerUrls.resolveCoverOrNull("http://127.0.0.1:8000/cover/7.jpg", server),
        )
        assertEquals(
            "https://home.example.dk/ttsroad/cover/7.jpg",
            ServerUrls.resolveCoverOrNull("https://public.example.dk/ttsroad/cover/7.jpg", server),
        )
        // Third-party artwork stays on its own host, so it never receives the bearer token.
        val external = "https://www.royalroadcdn.com/public/covers-large/1.jpg"
        assertEquals(external, ServerUrls.resolveCoverOrNull(external, server))
    }

    @Test
    fun `origin checks ignore the path prefix and stay strict`() {
        val server = "https://home.example.dk/ttsroad/"
        assertTrue(ServerUrls.isSameOrigin("https://home.example.dk/ttsroad/audio/1.mp3", server))
        assertFalse(ServerUrls.isSameOrigin("https://home.example.dk:8443/ttsroad/audio/1.mp3", server))
        assertFalse(ServerUrls.isSameOrigin("http://home.example.dk/ttsroad/audio/1.mp3", server))
    }
}
