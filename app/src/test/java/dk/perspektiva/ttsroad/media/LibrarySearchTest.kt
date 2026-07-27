package dk.perspektiva.ttsroad.media

import dk.perspektiva.ttsroad.data.AudioInfo
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.FictionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySearchTest {

    private val ashes = FictionSummary(
        id = 1,
        title = "Ashes of Aether",
        author = "R.Vale",
        tags = listOf("Fantasy", "Progression"),
    )
    private val wandering = FictionSummary(
        id = 2,
        title = "The Wandering Inn",
        author = "pirateaba",
        tags = listOf("Fantasy"),
    )
    private val ironLedger = FictionSummary(
        id = 3,
        title = "Iron Ledger",
        author = "R. Vale",
        tags = listOf("LitRPG"),
    )
    private val fictions = listOf(ashes, wandering, ironLedger)

    @Test
    fun `an exact spoken title resolves`() {
        assertEquals(ashes, resolveSpokenFiction(fictions, "Ashes of Aether"))
    }

    /** Voice transcription arrives with no punctuation and inconsistent casing. */
    @Test
    fun `casing and punctuation do not matter`() {
        assertEquals(ashes, resolveSpokenFiction(fictions, "ashes of aether"))
        assertEquals(ashes, resolveSpokenFiction(fictions, "ASHES, OF AETHER!"))
    }

    /** The assistant does not always strip the leading verb before handing the query over. */
    @Test
    fun `a leading play verb is ignored`() {
        assertEquals(ashes, resolveSpokenFiction(fictions, "play ashes of aether"))
        assertEquals(ashes, resolveSpokenFiction(fictions, "play the ashes of aether"))
    }

    @Test
    fun `a title made entirely of filler words is still findable`() {
        // "The Wandering Inn" loses "the" to filler stripping; the rest must still match, and a
        // query of nothing but filler must not match everything.
        assertEquals(wandering, resolveSpokenFiction(fictions, "the wandering inn"))
    }

    @Test
    fun `words in the wrong order still find the book`() {
        assertEquals(ashes, resolveSpokenFiction(fictions, "aether ashes"))
    }

    @Test
    fun `a partial title resolves`() {
        assertEquals(wandering, resolveSpokenFiction(fictions, "wandering"))
    }

    /**
     * The important negative case. Starting an unrelated book because a misheard query happened to
     * share a tag is the worst possible failure while driving.
     */
    @Test
    fun `a tag-only match is not strong enough to start playing`() {
        assertNull(resolveSpokenFiction(fictions, "fantasy"))
        assertNull(resolveSpokenFiction(fictions, "litrpg"))
    }

    @Test
    fun `an author-only match is not strong enough to start playing`() {
        // Two books share this author, so there is no single right answer to act on.
        assertNull(resolveSpokenFiction(fictions, "R. Vale"))
    }

    @Test
    fun `nothing matches an unrelated query`() {
        assertNull(resolveSpokenFiction(fictions, "moby dick"))
        assertNull(resolveSpokenFiction(emptyList(), "ashes of aether"))
    }

    @Test
    fun `an empty query resolves to nothing rather than the first book`() {
        assertNull(resolveSpokenFiction(fictions, ""))
        assertNull(resolveSpokenFiction(fictions, "   "))
        assertNull(resolveSpokenFiction(fictions, "play"))
    }

    @Test
    fun `browsing search is broader than spoken resolution`() {
        // Searching in the car should surface tag and author hits, even though they are too weak
        // to auto-play.
        assertEquals(listOf(ashes, wandering), searchFictions(fictions, "fantasy"))
        assertEquals(listOf(ashes, ironLedger), searchFictions(fictions, "R. Vale"))
    }

    @Test
    fun `an exact title outranks a weaker match for the same words`() {
        val results = searchFictions(fictions, "ashes of aether")

        assertEquals(ashes, results.first())
    }

    @Test
    fun `search results are stable for equally scored matches`() {
        val byTag = searchFictions(fictions, "fantasy")

        // Same score, so ordering falls back to title - not to whatever order the API returned.
        assertEquals(listOf("Ashes of Aether", "The Wandering Inn"), byTag.map { it.title })
    }

    private fun chapter(id: Int, title: String, playable: Boolean = true) = ChapterSummary(
        id = id,
        title = title,
        audio = if (playable) AudioInfo(url = "https://example.test/$id.mp3") else null,
    )

    @Test
    fun `chapter search matches on title`() {
        val chapters = listOf(
            chapter(1, "The Gathering Storm"),
            chapter(2, "Ashes and Salt"),
        )

        assertEquals(listOf(2), searchChapters(chapters, "ashes").map { it.resolvedChapterId })
    }

    @Test
    fun `chapters without audio are never offered`() {
        val chapters = listOf(chapter(1, "Ashes and Salt", playable = false))

        assertTrue(searchChapters(chapters, "ashes").isEmpty())
    }

    @Test
    fun `an empty chapter query matches nothing`() {
        val chapters = listOf(chapter(1, "Ashes and Salt"))

        assertTrue(searchChapters(chapters, "").isEmpty())
        assertTrue(searchChapters(chapters, "play the").isEmpty())
    }
}
