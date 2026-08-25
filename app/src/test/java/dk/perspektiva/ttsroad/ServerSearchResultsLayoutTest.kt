package dk.perspektiva.ttsroad

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.data.SearchGroup
import dk.perspektiva.ttsroad.data.SearchHit
import dk.perspektiva.ttsroad.data.SearchResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #100: server-search results were eager content in a `Column` above the only scrolling container.
 *
 * A response carries three groups of up to twenty hits each, every one a card with a snippet. That
 * is several screens of content, and none of it was inside anything that scrolls — so the later
 * hits, and the entire fiction catalogue below them, were laid out past the bottom of the window
 * with no gesture that could reach them. A successful search made the library unreachable.
 *
 * These run at a real phone viewport with enough hits to exceed it several times over, which is
 * what the issue's acceptance criteria ask for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class ServerSearchResultsLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `the last hit of the last group can be reached`() {
        renderResults()

        compose.onNodeWithTag(GridTag).performScrollToKey("search-hit-text-$LastHit")
        compose.onNodeWithText(textHitTitle(LastHit)).assertIsDisplayed()
    }

    /**
     * The teeth. A "you can scroll to it" test means nothing if everything already fits — it would
     * pass just as happily against the broken `Column`, because `performScrollToKey` on content
     * that is entirely on screen is a no-op that succeeds.
     *
     * So: assert the last hit is *not* displayed before scrolling. If a future change trimmed the
     * fixture to fit one screen, this fails and says why rather than leaving the tests above
     * silently vacuous.
     */
    @Test
    fun `the fixture really is taller than the viewport`() {
        renderResults()

        assertThrows(AssertionError::class.java) {
            compose.onNodeWithText(textHitTitle(LastHit)).assertIsDisplayed()
        }
    }

    @Test
    fun `the catalogue below the results is still reachable after a search`() {
        renderResults()

        compose.onNodeWithTag(GridTag).performScrollToKey("fiction-$LastFiction")
        compose.onNodeWithText("Fiction $LastFiction").assertIsDisplayed()
    }

    @Test
    fun `every group heading is reachable`() {
        renderResults()

        // Uppercased because that is what MetaText renders — the AARIS label style, not a typo.
        for ((id, title) in listOf(
            "fictions" to "// FICTIONS (3)",
            "chapters" to "// CHAPTER TITLES (8)",
            "text" to "// IN THE TEXT (8)",
        )) {
            compose.onNodeWithTag(GridTag).performScrollToKey("search-heading-$id")
            compose.onNodeWithText(title).assertIsDisplayed()
        }
    }

    @Test
    fun `a hit scrolled into view is tappable, not merely drawn`() {
        var opened: Int? = null
        renderResults(onOpenChapter = { opened = it.chapterId })

        compose.onNodeWithTag(GridTag).performScrollToKey("search-hit-text-$LastHit")
        compose.onNodeWithText(textHitTitle(LastHit)).performClick()

        assertEquals(TextChapterIdBase + LastHit, opened)
    }

    /**
     * Two groups can hold the same chapter — a term in a chapter's title is usually in its text as
     * well — so keys have to be unique across the whole list and not within a section. A lazy list
     * throws on a duplicate key, so this failing looks like a crash on an ordinary search.
     */
    @Test
    fun `the same chapter appearing in two groups does not collide`() {
        val shared = SearchHit(kind = "chapter", chapterId = 4242, chapterTitle = "The Lighthouse")
        renderResults(
            response = SearchResponse(
                query = "lighthouse",
                chapters = SearchGroup(items = listOf(shared), total = 1),
                text = SearchGroup(items = listOf(shared), total = 1),
                indexed = true,
                total = 2,
            ),
        )

        compose.onNodeWithTag(GridTag).performScrollToKey("search-hit-text-0")
        compose.onNodeWithText("// IN THE TEXT (1)").assertIsDisplayed()
    }

    private fun renderResults(
        response: SearchResponse = tallResponse(),
        onOpenChapter: (SearchHit) -> Unit = {},
    ) {
        compose.setContent {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 158.dp),
                state = rememberLazyGridState(),
                modifier = Modifier.fillMaxSize().testTag(GridTag),
            ) {
                serverSearchSection(
                    query = "lighthouse",
                    results = response,
                    isSearching = false,
                    error = null,
                    onSearch = {},
                    onOpenFiction = {},
                    onOpenChapter = onOpenChapter,
                )
                // Stands in for the catalogue grid: what a search used to push off the screen.
                items(count = FictionCount, key = { "fiction-$it" }) { index ->
                    Text("Fiction $index")
                }
            }
        }
    }

    private companion object {
        const val GridTag = "browse-grid"
        const val HitsPerGroup = 8
        const val LastHit = HitsPerGroup - 1
        const val FictionCount = 6
        const val LastFiction = FictionCount - 1
        const val TextChapterIdBase = 9000

        fun textHitTitle(index: Int) = "Text hit $index"

        fun tallResponse() = SearchResponse(
            query = "lighthouse",
            fictions = SearchGroup(
                items = List(3) { SearchHit(kind = "fiction", fictionId = it, fictionTitle = "Fiction hit $it") },
                total = 3,
            ),
            chapters = SearchGroup(
                items = List(HitsPerGroup) {
                    SearchHit(
                        kind = "chapter",
                        chapterId = it,
                        chapterTitle = "Chapter hit $it",
                        fictionTitle = "Some Serial",
                    )
                },
                total = HitsPerGroup,
            ),
            text = SearchGroup(
                items = List(HitsPerGroup) {
                    SearchHit(
                        kind = "text",
                        chapterId = TextChapterIdBase + it,
                        chapterTitle = textHitTitle(it),
                        fictionTitle = "Some Serial",
                        // A real narration-text snippet is a paragraph, and its height is most of
                        // why three groups do not fit on a phone.
                        snippet = "…the lighthouse keeper had not slept in three days, and the " +
                            "lamp above him turned and turned as if it had somewhere to be…",
                    )
                },
                total = HitsPerGroup,
            ),
            indexed = true,
            total = 3 + HitsPerGroup * 2,
        )
    }
}
