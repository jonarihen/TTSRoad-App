package dk.perspektiva.ttsroad.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DownloadCacheKeysTest {
    @Test
    fun `the same chapter keeps one cache key across a change of server address`() {
        // The whole point: signing in again against the LAN IP, the domain, or a VPN address must
        // not orphan gigabytes of already-downloaded audio.
        val lan = DownloadCacheKeys.forUrl("http://192.168.1.20:8000/audio/my-fiction/0001.mp3")
        val domain = DownloadCacheKeys.forUrl("https://ttsroad.example.com/audio/my-fiction/0001.mp3")
        val emulator = DownloadCacheKeys.forUrl("http://10.0.2.2:8000/audio/my-fiction/0001.mp3")

        assertEquals(lan, domain)
        assertEquals(lan, emulator)
    }

    @Test
    fun `the key is the server-relative path`() {
        assertEquals(
            "/audio/my-fiction/0001.mp3",
            DownloadCacheKeys.forUrl("https://ttsroad.example.com/audio/my-fiction/0001.mp3"),
        )
    }

    @Test
    fun `different chapters get different keys`() {
        assertNotEquals(
            DownloadCacheKeys.forUrl("https://ttsroad.example.com/audio/my-fiction/0001.mp3"),
            DownloadCacheKeys.forUrl("https://ttsroad.example.com/audio/my-fiction/0002.mp3"),
        )
    }

    @Test
    fun `two fictions sharing a filename do not collide`() {
        assertNotEquals(
            DownloadCacheKeys.forUrl("https://ttsroad.example.com/audio/one/0001.mp3"),
            DownloadCacheKeys.forUrl("https://ttsroad.example.com/audio/two/0001.mp3"),
        )
    }

    @Test
    fun `a relative url is already server-relative and keys the same as the absolute one`() {
        assertEquals(
            DownloadCacheKeys.forUrl("https://ttsroad.example.com/audio/my-fiction/0001.mp3"),
            DownloadCacheKeys.forUrl("/audio/my-fiction/0001.mp3"),
        )
    }

    @Test
    fun `a path without a leading slash is normalised`() {
        assertEquals(
            "/audio/my-fiction/0001.mp3",
            DownloadCacheKeys.forUrl("audio/my-fiction/0001.mp3"),
        )
    }

    @Test
    fun `a re-rendered chapter with a new query is a new key`() {
        // Query is kept deliberately: if the backend ever versions a re-synthesised chapter through
        // the URL, reusing the old key would play stale audio forever.
        assertNotEquals(
            DownloadCacheKeys.forUrl("https://ttsroad.example.com/audio/f/0001.mp3?v=1"),
            DownloadCacheKeys.forUrl("https://ttsroad.example.com/audio/f/0001.mp3?v=2"),
        )
    }

    @Test
    fun `a fragment is not part of the key`() {
        assertEquals(
            DownloadCacheKeys.forUrl("https://ttsroad.example.com/audio/f/0001.mp3"),
            DownloadCacheKeys.forUrl("https://ttsroad.example.com/audio/f/0001.mp3#t=30"),
        )
    }

    @Test
    fun `unencoded spaces in a filename survive`() {
        // java.net.URI would reject these; the backend really does emit them for EPUB imports.
        assertEquals(
            "/audio/my fiction/chapter 1.mp3",
            DownloadCacheKeys.forUrl("https://ttsroad.example.com/audio/my fiction/chapter 1.mp3"),
        )
    }

    @Test
    fun `a blank url keys to itself rather than throwing`() {
        assertEquals("", DownloadCacheKeys.forUrl(""))
    }
}
