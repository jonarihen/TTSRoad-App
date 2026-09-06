package dk.perspektiva.ttsroad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * The one coordinate identity the reader's auto-scroll is built on.
 *
 * `readerFollowScrollDelta` is handed the spoken line's position "in the viewport, 0 at the top
 * edge", and the reader works that out as `item.offset - layoutInfo.viewportStartOffset` plus the
 * line's offset inside the paragraph. Both halves of that subtraction are documented in terms of a
 * coordinate space the caller never sees, and the reader applies `contentPadding` — which is
 * exactly where the two could disagree. Get the sign or the padding wrong and nothing crashes: the
 * highlight simply settles a content-padding's worth away from where it was asked to, for every
 * chapter, which is the kind of thing that reads as "the scrolling is a bit off" forever.
 *
 * So it is measured rather than reasoned about. The list below has content padding on purpose.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class ReaderScrollGeometryTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `an item's viewport y is its offset less the viewport start`() {
        lateinit var listState: LazyListState
        var listTopInRoot = 0f
        val itemTopInRoot = HashMap<Int, Float>()

        rule.setContent {
            listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .onGloballyPositioned { listTopInRoot = it.positionInRoot().y },
                // The reader's own vertical padding, which is what makes this worth asserting.
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 20.dp),
            ) {
                items(12) { index ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(90.dp)
                            .onGloballyPositioned {
                                itemTopInRoot[index] = it.positionInRoot().y
                            },
                    )
                }
            }
        }
        rule.waitForIdle()

        fun assertAgreement(where: String) {
            val info = listState.layoutInfo
            for (item in info.visibleItemsInfo) {
                val measured = (itemTopInRoot.getValue(item.index) - listTopInRoot).roundToInt()
                val derived = item.offset - info.viewportStartOffset
                assertEquals("$where: item ${item.index}", measured, derived)
            }
        }

        // At rest the first item sits a content-padding down from the top edge, so a formula that
        // ignored `viewportStartOffset` would already be wrong here.
        assertAgreement("at rest")

        kotlinx.coroutines.runBlocking { listState.scrollToItem(4, 37) }
        rule.waitForIdle()
        // And scrolled to an arbitrary offset, where the first visible item is partly above the
        // top edge and its viewport y is negative.
        assertAgreement("scrolled")
    }
}
