package dk.perspektiva.ttsroad.core

import androidx.media3.datasource.DataSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioAuthSnapshotTest {
    private fun request(url: String) = DataSpec.Builder()
        .setUri(url)
        .setHttpRequestHeaders(mapOf("authorization" to "stale", "AUTHORIZATION" to "older", "Range" to "bytes=1-"))
        .build()

    @Test
    fun `captured session cannot mix new token with old origin`() {
        var published = AudioAuthSnapshot("https://old.example", "Bearer old")
        val captured = published
        published = AudioAuthSnapshot("https://new.example", "Bearer new")
        assertEquals("Bearer old", captured.resolve(request("https://old.example/audio")).httpRequestHeaders["Authorization"])
        assertFalse(published.resolve(request("https://old.example/audio")).httpRequestHeaders.keys.any { it.equals("Authorization", true) })
        assertEquals("Bearer new", published.resolve(request("https://new.example/audio")).httpRequestHeaders["Authorization"])
    }

    @Test
    fun `signed out and foreign requests lose every preexisting authorization header`() {
        for (snapshot in listOf(AudioAuthSnapshot(), AudioAuthSnapshot("https://home.example", "Bearer current"))) {
            val headers = snapshot.resolve(request("https://foreign.example/audio")).httpRequestHeaders
            assertEquals(mapOf("Range" to "bytes=1-"), headers)
        }
    }

    @Test
    fun `matching origin replaces stale authorization case insensitively`() {
        val headers = AudioAuthSnapshot("https://home.example", "Bearer current")
            .resolve(request("https://home.example/audio")).httpRequestHeaders
        assertEquals(mapOf("Range" to "bytes=1-", "Authorization" to "Bearer current"), headers)
    }
}
