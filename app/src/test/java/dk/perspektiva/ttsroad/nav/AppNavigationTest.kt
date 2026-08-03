package dk.perspektiva.ttsroad.nav

import dk.perspektiva.ttsroad.data.FictionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AppNavigationTest {

    private fun fiction(id: Int) = FictionSummary(id = id, title = "Fiction $id")

    @Test
    fun `back from a fiction returns to the fictions list, not the library`() {
        val stack = rootBackStack
            .navigateTo(AppScreen.Fictions)
            .navigateTo(AppScreen.Fiction(fiction(1)))

        val afterBack = stack.popScreen()

        assertEquals(listOf(AppScreen.Library, AppScreen.Fictions), afterBack)
        assertEquals(listOf(AppScreen.Library), afterBack.popScreen())
    }

    @Test
    fun `back from a fiction opened straight from the library returns to the library`() {
        val stack = rootBackStack.navigateTo(AppScreen.Fiction(fiction(1)))

        assertEquals(rootBackStack, stack.popScreen())
    }

    @Test
    fun `player and settings opened from a nested screen pop back to it`() {
        val fictionScreen = AppScreen.Fiction(fiction(7))
        val stack = rootBackStack
            .navigateTo(AppScreen.Fictions)
            .navigateTo(fictionScreen)
            .navigateTo(AppScreen.Player)
            .navigateTo(AppScreen.Settings)

        val afterBack = stack.popScreen()
        assertEquals(AppScreen.Player, afterBack.last())
        assertEquals(fictionScreen, afterBack.popScreen().last())
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
        assertEquals("Fiction:1", AppScreen.Fiction(fiction(1)).saveKey)
        assertEquals("Fiction:2", AppScreen.Fiction(fiction(2)).saveKey)
        assertEquals("Devices", AppScreen.Devices.saveKey)
    }

    /** Devices hangs off Settings, so backing out of it lands back on Settings, not the library. */
    @Test
    fun `back from devices returns to settings`() {
        val stack = rootBackStack
            .navigateTo(AppScreen.Settings)
            .navigateTo(AppScreen.Devices)

        assertEquals(listOf(AppScreen.Library, AppScreen.Settings), stack.popScreen())
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
}
