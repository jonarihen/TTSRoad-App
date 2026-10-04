package dk.perspektiva.ttsroad.media

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        cache.announced(car, "ashes", null)
        cache.announced(car, "cinder", null)
        cache.announced(other, "ashes", null)

        cache.invalidate(car)

        assertEquals(listOf(SearchAnnouncement(other, "ashes", null)), cache.announcedSearches())
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
        cache.announced(car, "ashes", null)
        cache.announced(other, "cinder", null)

        cache.invalidate()

        assertTrue(cache.announcedSearches().isEmpty())
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

    @Test
    fun `distinct queries bound results and announcements independently for each browser`() = runTest {
        val cache = BrowserSearchResultCache()
        val browsers = listOf(browser(1), browser(2))
        val limit = MaxRetainedSearchQueriesPerBrowser
        var searches = 0
        for (index in 0 until 100) {
            for (browser in browsers) {
                cache.results(browser, "query:$index", generation = 3L, fresh = true) {
                    searches++
                    SearchResult(listOf(item("chapter:$index")), generation = 3L)
                }
                cache.announced(browser, "query:$index", null)
                assertTrue(cache.announcedSearches().count { it.browser == browser } <= limit)
            }
        }

        val expectedQueries = (100 - limit until 100).map { "query:$it" }
        for (browser in browsers) {
            assertEquals(expectedQueries, cache.announcedSearches().filter { it.browser == browser }.map { it.query })
            for (index in 100 - limit until 100) {
                assertEquals(listOf(item("chapter:$index")), cache.results(browser, "query:$index", 4L, fresh = false) {
                    error("Retained query must not search again")
                })
            }
            val evicted = cache.results(browser, "query:0", generation = 4L, fresh = false) {
                searches++
                SearchResult(listOf(item("chapter:new")), generation = 4L)
            }
            assertEquals(listOf(item("chapter:new")), evicted)
            assertTrue(cache.announcedSearches().none { it.browser == browser && it.query == "query:0" })
        }
        assertEquals(202, searches)
    }

    @Test
    fun `page-only queries share the bound and retire evicted announcements`() = runTest {
        val cache = BrowserSearchResultCache(maxQueriesPerBrowser = 2)
        val car = browser(1)
        cache.results(car, "ashes", generation = 3L, fresh = true) {
            SearchResult(listOf(item("chapter:1")), generation = 3L)
        }
        cache.announced(car, "ashes", null)
        for (index in 0 until 100) {
            cache.results(car, "page:$index", generation = 3L, fresh = false) {
                SearchResult(listOf(item("chapter:$index")), generation = 3L)
            }
        }

        assertTrue(cache.announcedSearches().isEmpty())
        val evicted = cache.results(car, "page:0", generation = 4L, fresh = false) {
            SearchResult(listOf(item("chapter:new")), generation = 4L)
        }
        assertEquals(listOf(item("chapter:new")), evicted)
    }

    @Test
    fun `paging an active query keeps its announced set through repeated LRU eviction`() = runTest {
        val cache = BrowserSearchResultCache(maxQueriesPerBrowser = 2)
        val car = browser(1)
        val announced = listOf(item("chapter:1"), item("chapter:2"), item("chapter:3"))
        cache.results(car, "ashes", generation = 3L, fresh = true) {
            SearchResult(announced, generation = 3L)
        }
        cache.announced(car, "ashes", null)
        val pages = mutableListOf<MediaItem>()
        for (index in announced.indices) {
            val generation = 4L + index
            cache.results(car, "query:$index", generation, fresh = true) {
                SearchResult(listOf(item("chapter:new:$index")), generation)
            }
            cache.announced(car, "query:$index", null)
            val held = cache.results(car, "ashes", generation, fresh = false) {
                error("Active pages must use the announced set")
            }!!
            pages += held[index]
            assertEquals(announced.size, held.size)
            assertEquals(setOf("ashes", "query:$index"), cache.announcedSearches().map { it.query }.toSet())
        }

        assertEquals(announced, pages)
        assertEquals(announced.size, pages.size)
    }

    @Test
    fun `repeating a fresh query renews its eviction recency and announcement params`() = runTest {
        val cache = BrowserSearchResultCache(maxQueriesPerBrowser = 2)
        val car = browser(1)
        val search: suspend () -> SearchResult? = {
            SearchResult(listOf(item("chapter:1")), generation = 3L)
        }
        cache.results(car, "ashes", 3L, fresh = true, search)
        cache.announced(car, "ashes", null)
        cache.results(car, "cinder", 3L, fresh = true, search)
        cache.announced(car, "cinder", null)
        val refreshed = cache.results(car, "ashes", generation = 4L, fresh = true) {
            SearchResult(listOf(item("chapter:2")), generation = 4L)
        }
        val params = LibraryParams.Builder().setSuggested(true).build()
        cache.announced(car, "ashes", params)
        cache.results(car, "embers", 4L, fresh = true, search)
        cache.announced(car, "embers", null)

        assertEquals(
            listOf(SearchAnnouncement(car, "ashes", params), SearchAnnouncement(car, "embers", null)),
            cache.announcedSearches(),
        )
        assertEquals(refreshed, cache.results(car, "ashes", generation = 5L, fresh = false) {
            error("The freshly announced set must survive eviction")
        })
    }

    @Test
    fun `an evicted suspended search cannot resurrect results or announcements`() = runTest {
        val cache = BrowserSearchResultCache(maxQueriesPerBrowser = 1)
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
        cache.results(car, "cinder", generation = 4L, fresh = true) {
            SearchResult(listOf(item("chapter:2")), generation = 4L)
        }
        cache.announced(car, "cinder", null)
        answer.complete(SearchResult(listOf(item("chapter:1")), generation = 3L))

        assertNull(pending.await())
        cache.announced(car, "ashes", null)
        assertEquals(listOf(SearchAnnouncement(car, "cinder", null)), cache.announcedSearches())
        assertEquals(listOf(item("chapter:2")), cache.results(car, "cinder", generation = 5L, fresh = false) {
            error("Evicted search must not replace retained results")
        })
    }
}
