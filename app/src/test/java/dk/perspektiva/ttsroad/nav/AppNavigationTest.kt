package dk.perspektiva.ttsroad.nav

import dk.perspektiva.ttsroad.data.FictionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AppNavigationTest {

    private fun fiction(id: Int) = FictionSummary(id = id, title = "Fiction $id")

    @Test
    fun `back from a fiction returns to the browse root`() {
        val stack = rootBackStack
            .switchToRoot(AppRoot.Browse)
            .navigateTo(AppScreen.Fiction(fiction(1)))

        val afterBack = stack.popScreen()

        assertEquals(listOf(AppScreen.Fictions), afterBack)
        assertEquals(listOf(AppScreen.Fictions), afterBack.popScreen())
    }

    @Test
    fun `back from a fiction opened straight from the library returns to the library`() {
        val stack = rootBackStack.navigateTo(AppScreen.Fiction(fiction(1)))

        assertEquals(rootBackStack, stack.popScreen())
    }

    @Test
    fun `a player opened from a nested screen pops back to it`() {
        val fictionScreen = AppScreen.Fiction(fiction(7))
        val stack = rootBackStack
            .switchToRoot(AppRoot.Browse)
            .navigateTo(fictionScreen)
            .navigateTo(AppScreen.Player)

        val afterBack = stack.popScreen()
        assertEquals(fictionScreen, afterBack.last())
    }

    @Test
    fun `the root entry is never popped`() {
        assertEquals(rootBackStack, rootBackStack.popScreen())
    }

    @Test
    fun `navigating to the current screen is a no-op`() {
        val stack = rootBackStack.navigateTo(AppScreen.Fictions)

        assertEquals(stack, stack.navigateTo(AppScreen.Fictions))
    }

    @Test
    fun `re-entering an open screen pops back to it instead of stacking a copy`() {
        val fictionScreen = AppScreen.Fiction(fiction(1))
        val stack = rootBackStack
            .navigateTo(AppScreen.Fictions)
            .navigateTo(fictionScreen)
            .navigateTo(AppScreen.Player)

        // Tapping the mini player bar's fiction, or any path leading back to an open screen.
        val reentered = stack.navigateTo(fictionScreen)

        assertEquals(listOf(AppScreen.Library, AppScreen.Fictions, fictionScreen), reentered)
    }

    @Test
    fun `different fictions are distinct entries`() {
        val stack = rootBackStack
            .navigateTo(AppScreen.Fictions)
            .navigateTo(AppScreen.Fiction(fiction(1)))
            .navigateTo(AppScreen.Fiction(fiction(2)))

        assertEquals(4, stack.size)
        assertEquals(AppScreen.Fiction(fiction(1)), stack.popScreen().last())
    }

    @Test
    fun `save keys are stable and distinguish fictions`() {
        assertEquals("Fictions", AppScreen.Fictions.saveKey)
        assertEquals("Listening", AppScreen.Listening.saveKey)
        assertEquals("Fiction:1", AppScreen.Fiction(fiction(1)).saveKey)
        assertEquals("Fiction:2", AppScreen.Fiction(fiction(2)).saveKey)
        assertEquals("Devices", AppScreen.Devices.saveKey)
        assertEquals("Stats", AppScreen.Stats.saveKey)
    }

    /** Devices hangs off Settings, so backing out of it lands on that root, not Home. */
    @Test
    fun `back from devices returns to settings`() {
        val stack = rootBackStack
            .switchToRoot(AppRoot.Settings)
            .navigateTo(AppScreen.Devices)

        assertEquals(listOf(AppScreen.Settings), stack.popScreen())
    }

    @Test
    fun `switching roots does not put the previous tab behind back`() {
        val browseStack = rootBackStack
            .switchToRoot(AppRoot.Browse)
            .navigateTo(AppScreen.Fiction(fiction(1)))

        val listening = browseStack.switchToRoot(AppRoot.Listening)

        assertEquals(listOf(AppScreen.Listening), listening)
        assertEquals(listening, listening.popScreen())
        assertEquals(AppRoot.Listening, listening.activeRoot)
    }

    @Test
    fun `selecting the active tab returns its drill-down to the root`() {
        val stack = rootBackStack
            .switchToRoot(AppRoot.Browse)
            .navigateTo(AppScreen.Fiction(fiction(1)))

        assertEquals(listOf(AppScreen.Fictions), stack.switchToRoot(AppRoot.Browse))
    }

    @Test
    fun `a fiction consistently belongs to browse however it was opened`() {
        val fromHome = rootBackStack.navigateTo(AppScreen.Fiction(fiction(1)))
        val fromBrowse = rootBackStack
            .switchToRoot(AppRoot.Browse)
            .navigateTo(AppScreen.Fiction(fiction(1)))

        assertEquals(AppRoot.Browse, fromHome.activeRoot)
        assertEquals(AppRoot.Browse, fromBrowse.activeRoot)
    }

    @Test
    fun `content and account drill-downs identify their stable roots`() {
        assertEquals(AppRoot.Listening, listOf(AppScreen.Player).activeRoot)
        assertEquals(AppRoot.Listening, listOf(AppScreen.Bookmarks).activeRoot)
        assertEquals(AppRoot.Listening, listOf(AppScreen.Queue).activeRoot)
        assertEquals(AppRoot.Listening, listOf(reader(1)).activeRoot)
        assertEquals(AppRoot.Settings, listOf(AppScreen.Devices).activeRoot)
    }

    @Test
    fun `the four roots are stable and unique`() {
        assertEquals(
            listOf(AppScreen.Library, AppScreen.Fictions, AppScreen.Listening, AppScreen.Settings),
            AppRoot.entries.map { it.screen },
        )
        assertEquals(4, AppRoot.entries.map { it.label }.toSet().size)
    }

    private fun reader(chapterId: Int) = AppScreen.Reader(
        chapterId = chapterId,
        title = "Chapter $chapterId",
    )

    @Test
    fun `back from the reader returns to whatever opened it`() {
        val fromPlayer = rootBackStack.navigateTo(AppScreen.Player).navigateTo(reader(10))
        assertEquals(AppScreen.Player, fromPlayer.popScreen().last())

        val fictionScreen = AppScreen.Fiction(fiction(1))
        val fromChapterList = rootBackStack.navigateTo(fictionScreen).navigateTo(reader(10))
        assertEquals(fictionScreen, fromChapterList.popScreen().last())
    }

    @Test
    fun `each chapter is its own reader entry`() {
        // Reading on from chapter 10 to 11 is a new destination, not a re-entry, so the scroll
        // position of the chapter just finished is not restored over the new one.
        val stack = rootBackStack.navigateTo(reader(10)).navigateTo(reader(11))

        assertEquals(3, stack.size)
        assertEquals(reader(10), stack.popScreen().last())
        assertEquals("Reader:10", reader(10).saveKey)
        assertEquals("Reader:11", reader(11).saveKey)
    }

    @Test
    fun `reopening the same chapter pops back to it instead of stacking a copy`() {
        val stack = rootBackStack
            .navigateTo(reader(10))
            .navigateTo(AppScreen.Player)

        assertEquals(
            listOf(AppScreen.Library, reader(10)),
            stack.navigateTo(reader(10)),
        )
    }

    @Test
    fun `the reader save key does not collide with a fiction of the same id`() {
        assertNotEquals(reader(1).saveKey, AppScreen.Fiction(fiction(1)).saveKey)
    }

    @Test
    fun `the reader following playback leaves back pointing at whatever opened it`() {
        val fictionScreen = AppScreen.Fiction(fiction(1))
        val stack = rootBackStack.navigateTo(fictionScreen).navigateTo(reader(10))

        // An overnight listen advances chapter after chapter. Each one replaces the last, so the
        // stack stays three deep and BACK still returns to the fiction rather than walking back
        // through every chapter that played while nobody was looking.
        val readOn = stack.replaceTop(reader(11)).replaceTop(reader(12)).replaceTop(reader(13))

        assertEquals(listOf(AppScreen.Library, fictionScreen, reader(13)), readOn)
        assertEquals(fictionScreen, readOn.popScreen().last())
    }

    @Test
    fun `replacing changes the save key, so the new chapter starts at the top`() {
        val stack = rootBackStack.navigateTo(reader(10))

        assertEquals("Reader:11", stack.replaceTop(reader(11)).last().saveKey)
    }

    @Test
    fun `replacing the root entry is a no-op`() {
        assertEquals(rootBackStack, rootBackStack.replaceTop(AppScreen.Settings))
    }

    @Test
    fun `an edited fiction is written into every entry that carries it`() {
        // The editor and the screen under it both hold a copy of the same row, and the top bar
        // reads its title out of the stack. Updating one of them would leave the other stale.
        val stack = rootBackStack
            .navigateTo(AppScreen.Fictions)
            .navigateTo(AppScreen.Fiction(fiction(1)))
            .navigateTo(AppScreen.FictionEdit(fiction(1)))

        val edited = fiction(1).copy(title = "Ashfall: Book One")
        val updated = stack.withFiction(edited)

        assertEquals(AppScreen.FictionEdit(edited), updated.last())
        assertEquals(AppScreen.Fiction(edited), updated.popScreen().last())
        assertEquals(AppScreen.Fictions, updated.popScreen().popScreen().last())
    }

    @Test
    fun `another fiction on the stack is left exactly as it was`() {
        val other = AppScreen.Fiction(fiction(2))
        val stack = rootBackStack.navigateTo(other).navigateTo(AppScreen.Fiction(fiction(1)))

        val updated = stack.withFiction(fiction(1).copy(title = "Ashfall: Book One"))

        assertSame(other, updated[1])
    }

    @Test
    fun `an edit does not move the stack or reset the screen under it`() {
        val stack = rootBackStack
            .navigateTo(AppScreen.Fiction(fiction(1)))
            .navigateTo(AppScreen.FictionEdit(fiction(1)))
        val keys = stack.map { it.saveKey }

        val updated = stack.withFiction(fiction(1).copy(title = "Ashfall: Book One"))

        assertEquals(stack.size, updated.size)
        // Saved per-entry state hangs off these, so a rename must not change them: the chapter list
        // behind the editor would jump back to the top of a 500-row fiction.
        assertEquals(keys, updated.map { it.saveKey })
    }

    @Test
    fun `the editor is its own entry, so back returns to the fiction`() {
        val fictionScreen = AppScreen.Fiction(fiction(1))
        val stack = rootBackStack.navigateTo(fictionScreen).navigateTo(AppScreen.FictionEdit(fiction(1)))

        assertEquals(fictionScreen, stack.popScreen().last())
        assertNotEquals(fictionScreen.saveKey, AppScreen.FictionEdit(fiction(1)).saveKey)
    }
}
