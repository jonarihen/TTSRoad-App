package dk.perspektiva.ttsroad.download

import dk.perspektiva.ttsroad.data.AudioInfo
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.media.TtsRoadMediaIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun chapter(
    id: Int = 42,
    fictionId: Int = 7,
    url: String? = "https://configured-base-url.invalid/audio/my-fiction/0042.mp3",
) = ChapterSummary(
    id = id,
    fictionId = fictionId,
    title = "Chapter $id",
    audio = url?.let { AudioInfo(url = it) },
)

class ChapterDownloadSpecTest {
    private val serverUrl = "https://ttsroad.example.com/"

    @Test
    fun `the download id is the chapter's media id`() {
        // The id is what the chapter row looks itself up by, so it has to be the same string the
        // media item uses — not a second, parallel identity scheme.
        val spec = chapterDownloadSpec(chapter(id = 42), serverUrl)

        assertEquals(TtsRoadMediaIds.chapter(42), spec?.id)
    }

    @Test
    fun `the url is rewritten onto the host the user signed in to`() {
        // The 0.7.0/0.7.2 bug in miniature: the backend builds absolute URLs from its own BASE_URL,
        // which is routinely not the address the phone can reach. Downloading the raw URL would hit
        // the wrong host.
        val spec = chapterDownloadSpec(chapter(), serverUrl)

        assertEquals("https://ttsroad.example.com/audio/my-fiction/0042.mp3", spec?.url)
    }

    @Test
    fun `a relative audio url is resolved against the server`() {
        val spec = chapterDownloadSpec(chapter(url = "/audio/my-fiction/0042.mp3"), serverUrl)

        assertEquals("https://ttsroad.example.com/audio/my-fiction/0042.mp3", spec?.url)
    }

    @Test
    fun `the cache key ignores the host, so the same audio downloaded from either address is one file`() {
        val overLan = chapterDownloadSpec(chapter(), "http://192.168.1.20:8000/")
        val overDomain = chapterDownloadSpec(chapter(), serverUrl)

        assertEquals(overDomain?.cacheKey, overLan?.cacheKey)
        assertEquals(DownloadCacheKeys.forUrl(overDomain!!.url), overDomain.cacheKey)
    }

    @Test
    fun `the cache key is scoped to the server when it reports an identity`() {
        val identity = DownloadCacheKeys.serverIdentity("https://ttsroad.example.com")
        val spec = chapterDownloadSpec(chapter(), serverUrl, identity)!!

        assertEquals(DownloadCacheKeys.forUrl(spec.url, identity), spec.cacheKey)
        // Still host-independent within that server — downloading over the LAN and over the domain
        // is one file, which is the property 0.8.0 was built around.
        assertEquals(spec.cacheKey, chapterDownloadSpec(chapter(), "http://192.168.1.20:8000/", identity)?.cacheKey)
    }

    @Test
    fun `the same chapter on two servers gets two cache keys`() {
        val mine = chapterDownloadSpec(chapter(), serverUrl, DownloadCacheKeys.serverIdentity("https://a.example"))
        val theirs = chapterDownloadSpec(chapter(), serverUrl, DownloadCacheKeys.serverIdentity("https://b.example"))

        assertNotEquals(mine?.cacheKey, theirs?.cacheKey)
    }

    @Test
    fun `the fiction and chapter ids ride along so a restart can rebuild context`() {
        val spec = chapterDownloadSpec(chapter(id = 42, fictionId = 7), serverUrl)

        assertEquals(7, spec?.fictionId)
        assertEquals(42, spec?.chapterId)
    }

    @Test
    fun `a chapter with no audio yet cannot be downloaded`() {
        assertNull(chapterDownloadSpec(chapter(url = null), serverUrl))
    }

    @Test
    fun `a chapter with a blank audio url cannot be downloaded`() {
        assertNull(chapterDownloadSpec(chapter(url = "   "), serverUrl))
    }

    @Test
    fun `the ids survive a round trip through the download record`() {
        val spec = chapterDownloadSpec(chapter(id = 42, fictionId = 7), serverUrl)!!

        assertEquals(DownloadIds(fictionId = 7, chapterId = 42), decodeDownloadIds(spec.encodedIds()))
    }

    @Test
    fun `an empty or malformed download record decodes to nothing rather than to zeros`() {
        assertNull(decodeDownloadIds(null))
        assertNull(decodeDownloadIds(ByteArray(0)))
        assertNull(decodeDownloadIds("not-a-pair".toByteArray()))
        assertNull(decodeDownloadIds("7:not-a-number".toByteArray()))
        assertNull(decodeDownloadIds("7:42:auto:extra".toByteArray()))
    }

    @Test
    fun `a manual download is encoded exactly as 0_9_0 wrote it`() {
        val spec = chapterDownloadSpec(chapter(id = 42, fictionId = 7), serverUrl)!!

        // Byte-for-byte, because every download record already on a phone was written this way and
        // has to keep decoding after the upgrade.
        assertEquals("7:42", spec.encodedIds().toString(Charsets.UTF_8))
    }

    @Test
    fun `an existing record with no origin field reads as manual`() {
        // Which is what stops keep-ahead deleting downloads made before it existed.
        assertEquals(
            DownloadIds(fictionId = 7, chapterId = 42, origin = DownloadOrigin.Manual),
            decodeDownloadIds("7:42".toByteArray()),
        )
    }

    @Test
    fun `the origin survives a round trip through the download record`() {
        val spec = chapterDownloadSpec(
            chapter(id = 42, fictionId = 7),
            serverUrl,
            origin = DownloadOrigin.Auto,
        )!!

        assertEquals(
            DownloadIds(fictionId = 7, chapterId = 42, origin = DownloadOrigin.Auto),
            decodeDownloadIds(spec.encodedIds()),
        )
    }

    @Test
    fun `an unrecognised origin marker reads as manual rather than as deletable`() {
        assertEquals(
            DownloadIds(fictionId = 7, chapterId = 42, origin = DownloadOrigin.Manual),
            decodeDownloadIds("7:42:something-later".toByteArray()),
        )
    }
}
