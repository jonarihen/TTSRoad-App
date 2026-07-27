package dk.perspektiva.ttsroad

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsRoadApplicationTest {

    @Test
    fun `image authorization is allowed for the signed-in server origin`() {
        assertTrue(
            isSameOrigin(
                "https://ttsroad.example.com/cover/book.jpg",
                "https://ttsroad.example.com/",
            ),
        )
    }

    @Test
    fun `image authorization is rejected for an external cover host`() {
        assertFalse(
            isSameOrigin(
                "https://www.royalroadcdn.com/public/covers-full/book.jpg",
                "https://ttsroad.example.com/",
            ),
        )
    }

    @Test
    fun `image authorization requires matching scheme and port`() {
        assertFalse(isSameOrigin("http://ttsroad.example.com/cover/book.jpg", "https://ttsroad.example.com/"))
        assertFalse(
            isSameOrigin(
                "https://ttsroad.example.com:8443/cover/book.jpg",
                "https://ttsroad.example.com/",
            ),
        )
    }

    @Test
    fun `invalid server URL cannot receive image authorization`() {
        assertFalse(isSameOrigin("https://ttsroad.example.com/cover/book.jpg", "not a URL"))
    }
}
