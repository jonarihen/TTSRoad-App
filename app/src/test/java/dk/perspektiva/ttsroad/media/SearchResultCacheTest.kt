package dk.perspektiva.ttsroad.media

import androidx.media3.common.MediaItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchResultCacheTest {
    private fun item(id: String) = MediaItem.Builder().setMediaId(id).build()

    @Test
    fun `repeating a query reuses the result within a generation`() = runTest {
        val cache = SearchResultCache()
        var searches = 0
        val search: suspend () -> List<MediaItem> = {
            searches++
            listOf(item("chapter:1"))
        }

        val first = cache.results("ashes", generation = 3L, search)
        val second = cache.results("ashes", generation = 3L, search)

        assertEquals(first, second)
        assertEquals(1, searches)
    }

    @Test
    fun `a new generation refetches the same query`() = runTest {
        val cache = SearchResultCache()
        var searches = 0

        cache.results("ashes", generation = 3L) {
            searches++
            listOf(item("chapter:1"))
        }
        val refreshed = cache.results("ashes", generation = 4L) {
            searches++
            listOf(item("chapter:2"))
        }

        assertEquals(listOf(item("chapter:2")), refreshed)
        assertEquals(2, searches)
    }

    @Test
    fun `a new query searches even within a generation`() = runTest {
        val cache = SearchResultCache()
        var searches = 0
        val search: suspend () -> List<MediaItem> = {
            searches++
            emptyList()
        }

        cache.results("ashes", generation = 3L, search)
        cache.results("cinder", generation = 3L, search)

        assertEquals(2, searches)
    }
}
