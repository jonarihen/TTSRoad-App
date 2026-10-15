package dk.perspektiva.ttsroad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastOpenedTokenTest {
    private val decrypted = mutableListOf<String>()
    private var fail = false
    private val cache = LastOpenedToken { stored ->
        decrypted += stored
        if (fail) null else "plain:$stored"
    }

    @Test
    fun `repeated opens of an unchanged value decrypt once`() {
        repeat(50) { assertEquals("plain:enc1:a", cache.open("enc1:a")) }

        assertEquals(listOf("enc1:a"), decrypted)
    }

    @Test
    fun `a changed value is decrypted and answered with its own plaintext`() {
        cache.open("enc1:a")

        assertEquals("plain:enc1:b", cache.open("enc1:b"))
        assertEquals("plain:enc1:b", cache.open("enc1:b"))
        assertEquals(listOf("enc1:a", "enc1:b"), decrypted)
    }

    @Test
    fun `a value that could not be opened is not remembered`() {
        fail = true
        assertNull(cache.open("enc1:a"))
        fail = false

        assertEquals("plain:enc1:a", cache.open("enc1:a"))
        assertEquals(listOf("enc1:a", "enc1:a"), decrypted)
    }

    @Test
    fun `a freshly sealed value is answered without decrypting`() {
        cache.remember("enc1:new", "token")

        assertEquals("token", cache.open("enc1:new"))
        assertEquals(emptyList<String>(), decrypted)
    }
}
