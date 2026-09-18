package dk.perspektiva.ttsroad.media

import androidx.media3.common.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransitionFinishedItemTest {
    private fun item(id: String) = MediaItem.Builder().setMediaId(id).build()

    @Test
    fun `auto-advance leaves the previous chapter behind`() {
        assertEquals(item("chapter:1"), transitionFinishedItem(item("chapter:1"), "chapter:2"))
    }

    @Test
    fun `nothing playing means nothing to save`() {
        assertNull(transitionFinishedItem(null, "chapter:2"))
    }

    @Test
    fun `same item re-set saves nothing`() {
        assertNull(transitionFinishedItem(item("chapter:1"), "chapter:1"))
    }

    @Test
    fun `queue cleared saves the last item`() {
        assertEquals(item("chapter:1"), transitionFinishedItem(item("chapter:1"), null))
    }
}
