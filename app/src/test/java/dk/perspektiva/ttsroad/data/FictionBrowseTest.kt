package dk.perspektiva.ttsroad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the browse grid narrows itself by.
 *
 * The behaviour here is invisible from the outside when it is wrong — a grid that has quietly
 * ORed its tags looks exactly like a grid that has ANDed them until you count the rows — so it is
 * tested as the pure list-to-list function it is rather than through a rendered grid.
 */
class FictionBrowseTest {

    private fun fiction(
        id: Int,
        title: String = "Untitled",
        author: String? = null,
        tags: List<String> = emptyList(),
        following: Boolean = true,
    ) = FictionSummary(
        id = id,
        title = title,
        author = author,
        tags = tags,
        following = following,
    )

    // ── tags ──────────────────────────────────────────────────────────────────

    @Test
    fun `two tags means both, not either`() {
        // The web console's rule (`Array.from(activeTags).every(...)`). ORing them would widen the
        // list as you add to it, which is the opposite of what ticking a second box looks like.
        val rows = listOf(
            fiction(id = 1, tags = listOf("litrpg", "progression")),
            fiction(id = 2, tags = listOf("litrpg")),
            fiction(id = 3, tags = listOf("progression")),
        )

        val filtered = rows.browseView(tags = setOf("litrpg", "progression"))

        assertEquals(listOf(1), filtered.map { it.id })
    }

    @Test
    fun `tag matching ignores the casing the server happens to use`() {
        // Royal Road sends "LitRPG"; the filter's own vocabulary is lower-cased. Comparing the two
        // raw would make the tag list offer a box that matches nothing.
        val rows = listOf(fiction(id = 1, tags = listOf("LitRPG", " Progression ")))

        assertTrue(rows.first().hasAllTags(setOf("litrpg")))
        assertTrue(rows.first().hasAllTags(setOf("progression")))
        assertEquals(listOf(1), rows.browseView(tags = setOf("litrpg")).map { it.id })
    }

    @Test
    fun `no tags selected hides nothing`() {
        val rows = listOf(fiction(id = 1), fiction(id = 2, tags = listOf("litrpg")))

        assertEquals(listOf(1, 2), rows.browseView(tags = emptySet()).map { it.id }.sorted())
    }

    @Test
    fun `the tag list is de-duplicated, lower-cased and alphabetical`() {
        val rows = listOf(
            fiction(id = 1, tags = listOf("LitRPG", "Adventure")),
            fiction(id = 2, tags = listOf("litrpg", "", "  ")),
        )

        assertEquals(listOf("adventure", "litrpg"), rows.availableTags())
    }

    @Test
    fun `a stored tag nothing carries any more is dropped rather than emptying the grid`() {
        // The failure this prevents: unfollow the only xianxia book, restart, and browse comes back
        // empty with no box on screen to un-tick, because the sheet cannot offer a tag that is no
        // longer in the list.
        val available = listOf("litrpg", "progression")

        assertEquals(setOf("litrpg"), setOf("litrpg", "xianxia").retainingKnownTags(available))
        assertEquals(emptySet<String>(), setOf("xianxia").retainingKnownTags(available))
    }

    // ── scope ─────────────────────────────────────────────────────────────────

    @Test
    fun `following shows the shelf and all shows the server`() {
        val rows = listOf(
            fiction(id = 1, following = true),
            fiction(id = 2, following = false),
            fiction(id = 3, following = true),
        )

        assertEquals(listOf(1, 3), rows.browseView(scope = BrowseScope.Following).map { it.id })
        assertEquals(listOf(1, 2, 3), rows.browseView(scope = BrowseScope.All).map { it.id }.sorted())
    }

    @Test
    fun `on a server without per-user libraries both tabs hold the same list`() {
        // `following` defaults to true precisely so that a server with no shelf concept does not
        // read as "you follow nothing" and hand back an empty FOLLOWING tab.
        val rows = listOf(fiction(id = 1), fiction(id = 2))

        assertEquals(
            rows.browseView(scope = BrowseScope.All).map { it.id },
            rows.browseView(scope = BrowseScope.Following).map { it.id },
        )
    }

    @Test
    fun `the tab counts are over the whole list, not over what the filters left`() {
        // A tab says what it switches *to*. Recomputing it against the active filter would make
        // both tabs read as whatever is currently drawn.
        val rows = listOf(
            fiction(id = 1, tags = listOf("litrpg"), following = true),
            fiction(id = 2, following = true),
            fiction(id = 3, following = false),
        )

        assertEquals(2, rows.browseScopeCount(BrowseScope.Following))
        assertEquals(3, rows.browseScopeCount(BrowseScope.All))
    }

    @Test
    fun `browse opens on everything, unlike the web console's library page`() {
        // Deliberate divergence, recorded so it is not "fixed" back: HOME is already this app's
        // shelf, so opening BROWSE on the shelf too would hide the only list a new book can be
        // found in. See BrowseScope.Default.
        assertEquals(BrowseScope.All, BrowseScope.Default)
    }

    // ── text ──────────────────────────────────────────────────────────────────

    @Test
    fun `the search field looks at title, author and tags`() {
        val rows = listOf(
            fiction(id = 1, title = "The Lighthouse"),
            fiction(id = 2, author = "Lighthouse Keeper"),
            fiction(id = 3, tags = listOf("lighthouse")),
            fiction(id = 4, title = "Something else"),
        )

        assertEquals(listOf(1, 2, 3), rows.browseView(query = "lighthouse").map { it.id }.sorted())
    }

    @Test
    fun `a blank search matches everything, whitespace included`() {
        val rows = listOf(fiction(id = 1), fiction(id = 2))

        assertEquals(2, rows.browseView(query = "   ").size)
    }

    // ── the three together ────────────────────────────────────────────────────

    @Test
    fun `scope, tags and text all apply, and the result is still ordered`() {
        val rows = listOf(
            fiction(id = 1, title = "Zed", tags = listOf("litrpg"), following = true),
            fiction(id = 2, title = "Alpha", tags = listOf("litrpg"), following = true),
            fiction(id = 3, title = "Beta", tags = listOf("litrpg"), following = false),
            fiction(id = 4, title = "Gamma", tags = listOf("romance"), following = true),
        )

        val filtered = rows.browseView(
            scope = BrowseScope.Following,
            tags = setOf("litrpg"),
            query = "",
            sort = FictionSort.Title,
        )

        assertEquals(listOf(2, 1), filtered.map { it.id })
    }

    @Test
    fun `filtering does not disturb the caller's list`() {
        val rows = listOf(fiction(id = 1, title = "Zed"), fiction(id = 2, title = "Alpha"))

        rows.browseView(query = "zed", sort = FictionSort.Title)

        assertEquals(listOf(1, 2), rows.map { it.id })
    }

    // ── the empty state ───────────────────────────────────────────────────────

    @Test
    fun `an empty grid names the narrowing that emptied it`() {
        // "No fictions found" is a lie in three of these four cases, and the lie is expensive: it
        // reads as the server having lost the shelf rather than as a filter the user can undo.
        assertEquals(
            "No matches for \"lighthouse\"",
            browseEmptyMessage("lighthouse", emptySet(), BrowseScope.All),
        )
        assertEquals(
            "Nothing tagged litrpg",
            browseEmptyMessage("", setOf("litrpg"), BrowseScope.All),
        )
        assertEquals(
            "Nothing carries all 2 tags",
            browseEmptyMessage("", setOf("litrpg", "romance"), BrowseScope.All),
        )
        assertEquals(
            "You are not following anything yet",
            browseEmptyMessage("", emptySet(), BrowseScope.Following),
        )
        assertEquals("No fictions found", browseEmptyMessage("", emptySet(), BrowseScope.All))
    }

    @Test
    fun `a search names itself even with a tag also on`() {
        // The search is the thing just typed, so it is the cause worth naming first.
        assertEquals(
            "No matches for \"zzz\"",
            browseEmptyMessage("zzz", setOf("litrpg"), BrowseScope.Following),
        )
    }

    @Test
    fun `a whitespace-only search is not treated as a search`() {
        assertFalse(browseEmptyMessage("  ", emptySet(), BrowseScope.All).contains("matches"))
    }
}
