package dk.perspektiva.ttsroad.nav

import dk.perspektiva.ttsroad.data.FictionSummary
import org.junit.Assert.assertEquals
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
}
