package dk.perspektiva.ttsroad

import dk.perspektiva.ttsroad.core.ServerUrls
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsRoadApplicationTest {

    @Test
    fun `image authorization is allowed for the signed-in server origin`() {
        assertTrue(
            ServerUrls.isSameOrigin(
                "https://ttsroad.example.com/cover/book.jpg",
                "https://ttsroad.example.com/",
            ),
        )
    }

    @Test
    fun `image authorization is rejected for an external cover host`() {
        assertFalse(
            ServerUrls.isSameOrigin(
                "https://www.royalroadcdn.com/public/covers-full/book.jpg",
                "https://ttsroad.example.com/",
            ),
        )
    }

    @Test
    fun `image authorization requires matching scheme and port`() {
        assertFalse(ServerUrls.isSameOrigin("http://ttsroad.example.com/cover/book.jpg", "https://ttsroad.example.com/"))
        assertFalse(
            ServerUrls.isSameOrigin(
                "https://ttsroad.example.com:8443/cover/book.jpg",
                "https://ttsroad.example.com/",
            ),
        )
    }

    @Test
    fun `invalid server URL cannot receive image authorization`() {
        assertFalse(ServerUrls.isSameOrigin("https://ttsroad.example.com/cover/book.jpg", "not a URL"))
    }
}
