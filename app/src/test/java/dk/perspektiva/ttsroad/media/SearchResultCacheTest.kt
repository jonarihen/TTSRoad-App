package dk.perspektiva.ttsroad.media

import androidx.media3.common.MediaItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchResultCacheTest {
    private fun item(id: String) = MediaItem.Builder().setMediaId(id).build()

    @Test
    fun `repeating a query reuses the result within a generation`() = runTest {
        val cache = SearchResultCache()
        var searches = 0
        val search: suspend () -> SearchResult? = {
            searches++
            SearchResult(listOf(item("chapter:1")), generation = 3L)
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
            SearchResult(listOf(item("chapter:1")), generation = 3L)
        }
        val refreshed = cache.results("ashes", generation = 4L) {
            searches++
            SearchResult(listOf(item("chapter:2")), generation = 4L)
        }

        assertEquals(listOf(item("chapter:2")), refreshed)
        assertEquals(2, searches)
    }

    @Test
    fun `a new query searches even within a generation`() = runTest {
        val cache = SearchResultCache()
        var searches = 0
        val search: suspend () -> SearchResult? = {
            searches++
            SearchResult(emptyList(), generation = 3L)
        }

        cache.results("ashes", generation = 3L, search)
        cache.results("cinder", generation = 3L, search)

        assertEquals(2, searches)
    }

    @Test
    fun `a result is cached under the generation of the library it was built from`() = runTest {
        val cache = SearchResultCache()
        var searches = 0

        cache.results("ashes", generation = 3L) {
            searches++
            SearchResult(listOf(item("chapter:1")), generation = 4L)
        }
        cache.results("ashes", generation = 4L) {
            searches++
            SearchResult(listOf(item("chapter:2")), generation = 4L)
        }
        cache.results("ashes", generation = 5L) {
            searches++
            SearchResult(listOf(item("chapter:3")), generation = 5L)
        }

        assertEquals(2, searches)
    }

    @Test
    fun `a search overtaken by an account change is not cached`() = runTest {
        val cache = SearchResultCache()
        var searches = 0

        val first = cache.results("ashes", generation = 3L) {
            searches++
            null
        }
        cache.results("ashes", generation = 3L) {
            searches++
            SearchResult(listOf(item("chapter:9")), generation = 3L)
        }

        assertNull(first)
        assertEquals(2, searches)
    }
}
