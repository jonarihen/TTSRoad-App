package dk.perspektiva.ttsroad

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class SharedFictionLinkTest {
    @get:Rule val compose = createComposeRule()

    @After
    fun emptyInbox() = SharedFictionInbox.clear()

    @Test
    fun `finds the link inside a browser share with a title in front`() {
        assertEquals(
            SharedFictionLink.Link("https://www.royalroad.com/fiction/21220/mother-of-learning"),
            parseSharedFictionLink("Mother of Learning https://www.royalroad.com/fiction/21220/mother-of-learning"),
        )
        assertEquals(
            SharedFictionLink.Link("https://www.royalroad.com/fiction/1"),
            parseSharedFictionLink("Look at this (https://www.royalroad.com/fiction/1)."),
        )
    }

    @Test
    fun `text with no link is reported, and nothing shared is ignored`() {
        assertEquals(SharedFictionLink.NotALink, parseSharedFictionLink("a great story"))
        assertNull(parseSharedFictionLink(""))
        assertNull(parseSharedFictionLink(null))
    }

    @Test
    fun `a share intent is read once`() {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "https://www.royalroad.com/fiction/2")

        assertEquals(SharedFictionLink.Link("https://www.royalroad.com/fiction/2"), consumeSharedFictionLink(intent))
        assertNull(consumeSharedFictionLink(intent))
        assertNull(consumeSharedFictionLink(Intent(Intent.ACTION_MAIN)))
    }

    @Test
    fun `routing depends on sign-in, discovery and permission`() {
        val link = SharedFictionLink.Link("https://www.royalroad.com/fiction/2")
        fun action(signedIn: Boolean = true, resolved: Boolean = true, admin: Boolean = true, canAdd: Boolean = true) =
            sharedLinkAction(link, signedIn, resolved, admin, canAdd)

        assertEquals(SharedLinkAction.WaitForSignIn, action(signedIn = false))
        assertEquals(SharedLinkAction.WaitForServer, action(resolved = false))
        assertEquals(SharedLinkAction.OpenAddSheet, action())
        assertEquals(SharedLinkAction.CannotAdd, action(admin = false))
        assertEquals(SharedLinkAction.CannotAdd, action(canAdd = false))
        assertEquals(
            SharedLinkAction.NotALink,
            sharedLinkAction(SharedFictionLink.NotALink, true, true, true, true),
        )
    }

    @Test
    fun `the inbox hands a link out exactly once`() {
        SharedFictionInbox.offer("https://www.royalroad.com/fiction/3")

        assertEquals("https://www.royalroad.com/fiction/3", SharedFictionInbox.take())
        assertNull(SharedFictionInbox.take())
    }

    @Test
    fun `a waiting link opens the add sheet with the url filled in`() {
        SharedFictionInbox.offer("https://www.royalroad.com/fiction/4")
        compose.setContent { TtsRoadTheme { AddFictionSection(canAddByUrl = true) } }

        compose.onNodeWithText("FICTION URL OR ID").assertIsDisplayed()
        compose.onNodeWithText("https://www.royalroad.com/fiction/4").assertIsDisplayed()
        assertNull(SharedFictionInbox.pending.value)
    }
}
