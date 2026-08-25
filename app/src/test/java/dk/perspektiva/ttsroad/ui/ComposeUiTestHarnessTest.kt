package dk.perspektiva.ttsroad.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves the Compose UI test harness itself, so a layout test that fails is failing about layout.
 *
 * This repo has no instrumented test source set worth running — `app/src/androidTest` exists only
 * for the R8 startup smoke test, and that needs a device and the signing keystore. Everything a
 * layout bug needs, though, is measurable without a phone: Robolectric supplies a `Configuration`,
 * Compose measures against it, and the semantics tree reports the bounds it settled on.
 *
 * The three things below are exactly the three the layout issues (#99, #100, #101, #104) need, and
 * each has a way of failing silently that would make a later assertion meaningless:
 *
 * - **A viewport of a stated size.** `qualifiers` is what makes "at 320 dp width" a real claim
 *   rather than a comment. If it were ignored, every compact-width test would quietly run at the
 *   default 320x470 *of some other device* and pass without testing the narrow case.
 * - **Content is actually displayed.** `assertIsDisplayed` distinguishes composed-and-visible from
 *   composed-and-clipped, which is the whole subject of #100 and #101.
 * - **Bounds are measurable in dp.** `assertWidthIsAtLeast` is how a 48 dp touch target (#104)
 *   becomes an assertion instead of an intention.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class ComposeUiTestHarnessTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `the viewport is the one the qualifiers ask for`() {
        var widthDp = 0
        var heightDp = 0
        compose.setContent {
            val configuration = LocalConfiguration.current
            widthDp = configuration.screenWidthDp
            heightDp = configuration.screenHeightDp
            Box(modifier = Modifier.fillMaxSize()) { Text("anchor") }
        }

        assertEquals(320, widthDp)
        assertEquals(640, heightDp)
        compose.onNodeWithText("anchor").assertIsDisplayed()
    }

    @Test
    fun `measured bounds come back in dp`() {
        compose.setContent {
            Box(modifier = Modifier.size(48.dp).testTag("target")) { Text("glyph") }
        }

        // Deliberately the tagged box and not the text inside it: a bounds assertion measures the
        // semantics node it matched, so `onNodeWithText` here would report the 6 dp glyph and a
        // touch-target test written that way would fail on a target that is perfectly fine.
        compose.onNodeWithTag("target")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }
}
