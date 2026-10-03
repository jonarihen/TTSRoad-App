package dk.perspektiva.ttsroad.media

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchResultCacheTest {
    private fun item(id: String) = MediaItem.Builder().setMediaId(id).build()

    private fun browser(pid: Int) = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
        "dk.perspektiva.ttsroad",
        pid,
        1000,
        0,
        0,
        true,
        Bundle.EMPTY,
        true,
    )

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

    @Test
    fun `invalidating forces the next lookup to search again`() = runTest {
        val cache = SearchResultCache()
        var searches = 0
        val search: suspend () -> SearchResult? = {
            searches++
            SearchResult(listOf(item("chapter:1")), generation = 3L)
        }

        cache.results("ashes", generation = 3L, search)
        cache.invalidate()
        cache.results("ashes", generation = 3L, search)

        assertEquals(2, searches)
    }

    @Test
    fun `an account change refreshes the roots and every fiction folder the car could have open`() {
        assertEquals(
            listOf(
                TtsRoadMediaIds.Root,
                TtsRoadMediaIds.Continue,
                TtsRoadMediaIds.Fictions,
                TtsRoadMediaIds.Recent,
                TtsRoadMediaIds.Queue,
                "fiction:7",
                "fiction:9",
            ),
            browseNodesToRefresh(listOf(7, 9, 7)),
        )
    }


    @Test
    fun `the held result survives a library refresh until invalidated`() = runTest {
        val cache = SearchResultCache()
        cache.results("ashes", generation = 3L) { SearchResult(listOf(item("chapter:1")), generation = 3L) }

        assertEquals(listOf(item("chapter:1")), cache.held("ashes"))
        assertNull(cache.held("cinder"))

        cache.invalidate()
        assertNull(cache.held("ashes"))
    }

    @Test
    fun `interleaved browsers page their announced sets after the library changes`() = runTest {
        val cache = BrowserSearchResultCache()
        val car = browser(1)
        val other = browser(2)
        var generation = 3L
        var library = listOf(item("chapter:1"), item("chapter:2"), item("chapter:3"))
        var searches = 0
        val search: suspend () -> SearchResult? = {
            searches++
            SearchResult(library, generation)
        }
        val carAnnounced = cache.results(car, "ashes", generation, fresh = true, search)!!
        val carCount = carAnnounced.size

        generation++
        library = listOf(item("chapter:4"), item("chapter:5"))
        val otherAnnounced = cache.results(other, "cinder", generation, fresh = true, search)!!
        val otherCount = otherAnnounced.size

        generation++
        library = listOf(item("chapter:6"))
        val carFirstPage = cache.results(car, "ashes", generation, fresh = false, search)!!.take(2)
        val otherFirstPage = cache.results(other, "cinder", generation, fresh = false, search)!!.take(1)
        val carLastPage = cache.results(car, "ashes", generation, fresh = false, search)!!.drop(2)
        val otherLastPage = cache.results(other, "cinder", generation, fresh = false, search)!!.drop(1)

        assertEquals(3, carCount)
        assertEquals(2, otherCount)
        assertEquals(carAnnounced, carFirstPage + carLastPage)
        assertEquals(otherAnnounced, otherFirstPage + otherLastPage)
        assertEquals(carCount, (carFirstPage + carLastPage).size)
        assertEquals(otherCount, (otherFirstPage + otherLastPage).size)
        assertEquals(2, searches)
    }

    @Test
    fun `browsers searching the same query retain separate announced sets`() = runTest {
        val cache = BrowserSearchResultCache()
        val car = browser(1)
        val other = browser(2)
        val carAnnounced = listOf(item("chapter:1"), item("chapter:2"))
        val otherAnnounced = listOf(item("chapter:3"))
        cache.results(car, "ashes", generation = 3L, fresh = true) {
            SearchResult(carAnnounced, generation = 3L)
        }
        cache.results(other, "ashes", generation = 4L, fresh = true) {
            SearchResult(otherAnnounced, generation = 4L)
        }
        val unexpectedSearch: suspend () -> SearchResult? = { error("Announced results must be held") }

        assertEquals(carAnnounced, cache.results(car, "ashes", 5L, fresh = false, unexpectedSearch))
        assertEquals(otherAnnounced, cache.results(other, "ashes", 5L, fresh = false, unexpectedSearch))
    }

    @Test
    fun `a fresh search replaces only that browser and query`() = runTest {
        val cache = BrowserSearchResultCache()
        val car = browser(1)
        val other = browser(2)
        cache.results(car, "ashes", generation = 3L, fresh = true) {
            SearchResult(listOf(item("chapter:1")), generation = 3L)
        }
        cache.results(car, "cinder", generation = 3L, fresh = true) {
            SearchResult(listOf(item("chapter:2")), generation = 3L)
        }
        cache.results(other, "ashes", generation = 3L, fresh = true) {
            SearchResult(listOf(item("chapter:3")), generation = 3L)
        }
        val refreshed = cache.results(car, "ashes", generation = 3L, fresh = true) {
            SearchResult(listOf(item("chapter:4")), generation = 3L)
        }
        val unexpectedSearch: suspend () -> SearchResult? = { error("Announced results must be held") }

        assertEquals(listOf(item("chapter:4")), refreshed)
        assertEquals(refreshed, cache.results(car, "ashes", 3L, fresh = false, unexpectedSearch))
        assertEquals(listOf(item("chapter:2")), cache.results(car, "cinder", 3L, fresh = false, unexpectedSearch))
        assertEquals(listOf(item("chapter:3")), cache.results(other, "ashes", 3L, fresh = false, unexpectedSearch))
    }

    @Test
    fun `disconnect clears every query for only that browser`() = runTest {
        val cache = BrowserSearchResultCache()
        val car = browser(1)
        val other = browser(2)
        var searches = 0
        val search: suspend () -> SearchResult? = {
            searches++
            SearchResult(listOf(item("chapter:$searches")), generation = 3L)
        }
        cache.results(car, "ashes", 3L, fresh = true, search)
        cache.results(car, "cinder", 3L, fresh = true, search)
        val otherAnnounced = cache.results(other, "ashes", 3L, fresh = true, search)

        cache.invalidate(car)

        assertEquals(otherAnnounced, cache.results(other, "ashes", 3L, fresh = false, search))
        assertEquals(listOf(item("chapter:4")), cache.results(car, "ashes", 3L, fresh = false, search))
        assertEquals(listOf(item("chapter:5")), cache.results(car, "cinder", 3L, fresh = false, search))
        assertEquals(5, searches)
    }

    @Test
    fun `account change clears every browsers held results`() = runTest {
        val cache = BrowserSearchResultCache()
        val car = browser(1)
        val other = browser(2)
        val oldAccount: suspend () -> SearchResult? = {
            SearchResult(listOf(item("chapter:1")), generation = 3L)
        }
        cache.results(car, "ashes", 3L, fresh = true, oldAccount)
        cache.results(other, "cinder", 3L, fresh = true, oldAccount)

        cache.invalidate()

        var searches = 0
        val newAccount: suspend () -> SearchResult? = {
            searches++
            SearchResult(listOf(item("chapter:9")), generation = 4L)
        }
        assertEquals(listOf(item("chapter:9")), cache.results(car, "ashes", 4L, fresh = false, newAccount))
        assertEquals(listOf(item("chapter:9")), cache.results(other, "cinder", 4L, fresh = false, newAccount))
        assertEquals(2, searches)
    }

    @Test
    fun `disconnect during a search discards its late result`() = runTest {
        val cache = BrowserSearchResultCache()
        val car = browser(1)
        val started = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<SearchResult>()
        val pending = async {
            cache.results(car, "ashes", generation = 3L, fresh = true) {
                started.complete(Unit)
                answer.await()
            }
        }
        started.await()
        cache.invalidate(car)
        answer.complete(SearchResult(listOf(item("chapter:1")), generation = 3L))

        assertNull(pending.await())
        assertEquals(listOf(item("chapter:2")), cache.results(car, "ashes", generation = 3L, fresh = false) {
            SearchResult(listOf(item("chapter:2")), generation = 3L)
        })
    }

    @Test
    fun `account change during a search discards its late result`() = runTest {
        val cache = BrowserSearchResultCache()
        val car = browser(1)
        val started = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<SearchResult>()
        val pending = async {
            cache.results(car, "ashes", generation = 3L, fresh = true) {
                started.complete(Unit)
                answer.await()
            }
        }
        started.await()
        cache.invalidate()
        answer.complete(SearchResult(listOf(item("chapter:1")), generation = 3L))

        assertNull(pending.await())
        assertEquals(listOf(item("chapter:9")), cache.results(car, "ashes", generation = 4L, fresh = false) {
            SearchResult(listOf(item("chapter:9")), generation = 4L)
        })
    }

    @Test
    fun `a superseded search cannot overwrite the newer announced set`() = runTest {
        val cache = BrowserSearchResultCache()
        val car = browser(1)
        val started = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<SearchResult>()
        val pending = async {
            cache.results(car, "ashes", generation = 3L, fresh = true) {
                started.complete(Unit)
                answer.await()
            }
        }
        started.await()
        val announced = cache.results(car, "ashes", generation = 4L, fresh = true) {
            SearchResult(listOf(item("chapter:2")), generation = 4L)
        }
        answer.complete(SearchResult(listOf(item("chapter:1")), generation = 3L))

        assertNull(pending.await())
        assertEquals(announced, cache.results(car, "ashes", generation = 4L, fresh = false) {
            error("Announced results must be held")
        })
    }
}
