package dk.perspektiva.ttsroad.core

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import dk.perspektiva.ttsroad.data.ServerCapabilities
import dk.perspektiva.ttsroad.data.ServerLogRow
import dk.perspektiva.ttsroad.serverLogLineText
import dk.perspektiva.ttsroad.ui.ErrorDetailsToggleTag
import dk.perspektiva.ttsroad.ui.ErrorLine
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticsTest {
    @get:Rule val compose = createComposeRule()

    private val server = "https://home.example.org:8443"

    @Test
    fun `the report names versions and the last error but never the server address`() {
        val error = UnknownHostException("Unable to resolve host \"home.example.org\" for $server/api")
            .toUserFacingError("x")
        val report = diagnosticsReport(
            appVersion = "0.22.0 (220)",
            androidVersion = "14 (API 34)",
            device = "Google Pixel",
            capabilities = ServerCapabilities(serverVersion = "1.5.7", search = true),
            lastError = error,
            serverAddresses = listOf(server),
        )
        assertTrue(report.contains("TTSRoad app 0.22.0 (220)"))
        assertTrue(report.contains("1.5.7"))
        assertTrue(report.contains("search"))
        assertTrue(report.contains(CantFindServerMessage))
        assertFalse(report, report.contains("home.example.org:8443"))
    }

    @Test
    fun `a copied log line has the server address removed`() {
        val row = ServerLogRow(1, "ERROR", "GET $server/x failed", "10:00", null, null, "MY BOOK")
        val text = serverLogLineText(row, listOf(server))
        assertTrue(text.contains("MY BOOK") && text.contains("failed"))
        assertFalse(text, text.contains("home.example.org"))
    }

    @Test
    fun `an inline error folds its raw text under Details`() {
        val error = UnknownHostException("raw-host-detail").toUserFacingError("x")
        compose.setContent { TtsRoadTheme { ErrorLine(error.message) } }
        compose.onNodeWithText("raw-host-detail", substring = true).assertDoesNotExist()
        compose.onNodeWithTag(ErrorDetailsToggleTag).performClick()
        compose.onNodeWithText("raw-host-detail", substring = true).assertExists()
    }

}
