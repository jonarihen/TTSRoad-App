package dk.perspektiva.ttsroad

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dk.perspektiva.ttsroad.data.FakeSessionStore
import dk.perspektiva.ttsroad.data.FictionNotificationSettings
import dk.perspektiva.ttsroad.data.FictionNotificationSettingsRequest
import dk.perspektiva.ttsroad.data.FictionNotificationSettingsResult
import dk.perspektiva.ttsroad.data.FictionNotificationSettingsResult.Loaded
import dk.perspektiva.ttsroad.data.FictionNotificationSettingsResult.Refused
import dk.perspektiva.ttsroad.data.NotificationModeBacklog
import dk.perspektiva.ttsroad.data.NotificationModeEvery
import dk.perspektiva.ttsroad.data.NotificationModeOff
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.data.notificationStatusDescription
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class FictionNotificationSettingsUiTest {
    @get:Rule val compose = createComposeRule()

    private val armed = FictionNotificationSettings(NotificationModeBacklog, 2.0, 5400.0, true)

    private fun showSheet(state: FictionNotificationSettingsState, onSaved: () -> Unit = {}) {
        compose.setContent {
            TtsRoadTheme {
                FictionNotificationSettingsSheet(state, onDismiss = {}, onSaved = onSaved)
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `every status including unknown is a clickable labelled 48dp target`() {
        var settings by mutableStateOf<FictionNotificationSettings?>(null)
        var clicks = 0
        compose.setContent {
            TtsRoadTheme {
                FictionNotificationStatusButton(settings, onClick = { clicks++ })
            }
        }
        val cases = listOf(
            armed to "ARMED",
            armed.copy(backlogArmed = false) to "WAITING",
            armed.copy(mode = NotificationModeOff) to "OFF",
            armed.copy(mode = NotificationModeEvery) to "EVERY CHAPTER",
            armed.copy(backlogArmed = null) to "STATUS UNAVAILABLE",
            armed.copy(mode = "future") to "STATUS UNAVAILABLE",
            null to "STATUS UNAVAILABLE",
        )

        for ((value, label) in cases) {
            compose.runOnIdle { settings = value }
            compose.onNodeWithText(label).assertIsDisplayed()
            val button = compose.onNodeWithTag("fiction-notification-status")
            button.assertIsEnabled().assertHasClickAction()
                .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Chapter notifications")))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, notificationStatusDescription(value)))
                .performClick()
            val bounds = button.getUnclippedBoundsInRoot()
            assertTrue(bounds.left >= 0.dp)
            assertTrue(bounds.right <= 320.dp)
        }
        assertEquals(cases.size, clicks)
    }

    @Test
    fun `checking never invents a status and stale button keeps its label with last known semantics`() {
        var settings by mutableStateOf<FictionNotificationSettings?>(null)
        var stale by mutableStateOf(false)
        compose.setContent {
            TtsRoadTheme {
                FictionNotificationStatusButton(settings, stale = stale, loading = true, onClick = {})
            }
        }
        compose.onNodeWithText("CHECKING").assertIsDisplayed()
        compose.runOnIdle {
            settings = armed
            stale = true
        }

        compose.onNodeWithText("ARMED").assertIsDisplayed()
        compose.onNodeWithText("LAST KNOWN").assertIsDisplayed()
        compose.onNodeWithTag("fiction-notification-status")
            .assertIsEnabled()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    notificationStatusDescription(armed) + " Last known state; refresh to confirm.",
                ),
            )
    }

    @Test
    fun `opening and reopening always refresh even with cached settings`() {
        var loads = 0
        val state = FictionNotificationSettingsState(
            load = { loads++; Loaded(armed.copy(remainingSeconds = loads * 60.0)) },
            update = { Loaded(armed) },
        )
        runBlocking { state.refresh() }
        var open by mutableStateOf(true)
        compose.setContent {
            TtsRoadTheme {
                if (open) FictionNotificationSettingsSheet(state, onDismiss = {}, onSaved = {})
            }
        }
        compose.waitForIdle()
        assertEquals(2, loads)
        compose.onNodeWithText("Ready to listen: 2 min.").assertExists()

        compose.runOnIdle { open = false }
        compose.waitForIdle()
        compose.runOnIdle { open = true }
        compose.waitForIdle()
        assertEquals(3, loads)
        compose.onNodeWithText("Ready to listen: 3 min.").assertExists()
    }

    @Test
    fun `initial pending load has no selected defaults and cannot save`() {
        val response = CompletableDeferred<FictionNotificationSettingsResult>()
        val state = FictionNotificationSettingsState(load = { response.await() }, update = { Loaded(armed) })
        showSheet(state)

        compose.onNodeWithTag("fiction-notification-save").assertIsNotEnabled()
        for (label in listOf("Every chapter", "Off", "Wait for a backlog")) {
            compose.onNodeWithText(label).assertIsNotEnabled().assertIsNotSelected()
        }
        compose.runOnIdle { response.complete(Loaded(armed)) }
        compose.onNodeWithText("Wait for a backlog").assertIsEnabled().assertIsSelected()
        compose.onNodeWithTag("fiction-notification-save").assertIsEnabled()
    }

    @Test
    fun `initial failure exposes reachable retry but no enabled default controls`() {
        var fail = true
        val state = FictionNotificationSettingsState(
            load = { if (fail) throw IOException("secret server address") else Loaded(armed) },
            update = { Loaded(armed) },
        )
        showSheet(state)

        compose.onNodeWithText("Could not load chapter notification settings. Try again.").assertExists()
        compose.onNodeWithTag("fiction-notification-save").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        for (label in listOf("Every chapter", "Off", "Wait for a backlog")) {
            compose.onNodeWithText(label).assertIsNotEnabled().assertIsNotSelected()
        }
        compose.runOnIdle { fail = false }
        compose.onNodeWithTag("fiction-notification-retry").performScrollTo().assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).assertIsEnabled().performClick()
        compose.onNodeWithText("Wait for a backlog").assertIsEnabled().assertIsSelected()
        compose.onNodeWithTag("fiction-notification-save").assertIsEnabled()
        compose.onNodeWithText("RETRY").assertDoesNotExist()
    }

    @Test
    fun `failed opening refresh keeps saved status and amount explicitly last known`() {
        var fail = false
        val state = FictionNotificationSettingsState(
            load = { if (fail) Refused("Could not reach the server.") else Loaded(armed) },
            update = { Loaded(armed) },
        )
        runBlocking { state.refresh() }
        fail = true
        showSheet(state)

        compose.onNodeWithText("SAVED STATUS").assertExists()
        compose.onNodeWithText("ALARM ARMED").assertExists()
        compose.onNodeWithText(notificationStatusDescription(armed)).assertExists()
        compose.onNodeWithText("Ready to listen: 1 h 30 min.").assertExists()
        compose.onNodeWithText("Last known state. Refresh to confirm the current server settings.").assertExists()
        compose.onNodeWithText("RETRY").performScrollTo().assertIsDisplayed().assertIsEnabled()
        assertEquals(armed, state.settings)
        assertTrue(state.stale)
    }

    @Test
    fun `dirty mode and hours survive refresh while saved status and ready amount update independently`() {
        var saved = armed
        val state = FictionNotificationSettingsState(load = { Loaded(saved) }, update = { Loaded(saved) })
        showSheet(state)
        compose.onNodeWithText("Custom hours").performScrollTo().performTextReplacement("3.5")
        compose.onNodeWithText("Off").performScrollTo().performClick()

        saved = armed.copy(mode = NotificationModeEvery, backlogHours = 5.0, remainingSeconds = 600.0)
        compose.runOnIdle { runBlocking { state.refresh() } }

        compose.onNodeWithText("Off").assertIsSelected()
        compose.onNodeWithText(notificationStatusDescription(saved)).assertExists()
        compose.onNodeWithText("Ready to listen: 10 min.").assertExists()
        compose.onNodeWithText("Not saved yet.").assertExists()
        compose.onNodeWithText("Wait for a backlog").performScrollTo().performClick()
        compose.onNodeWithText("Custom hours").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("3.5")),
        )
        compose.onNodeWithText(notificationStatusDescription(saved)).assertExists()
    }

    @Test
    fun `clean draft follows refreshed settings with accessible mode and preset selection`() {
        var saved = armed
        val state = FictionNotificationSettingsState(load = { Loaded(saved) }, update = { Loaded(saved) })
        showSheet(state)
        compose.onNodeWithText("Wait for a backlog").assertIsSelected()
        compose.onNodeWithText("2h").assertIsSelected()

        saved = armed.copy(backlogHours = 5.0, backlogArmed = false)
        compose.runOnIdle { runBlocking { state.refresh() } }
        compose.onNodeWithText("5h").assertIsSelected()
        compose.onNodeWithText("2h").assertIsNotSelected()
        compose.onNodeWithText("Not saved yet.").assertDoesNotExist()
        compose.onNodeWithText(notificationStatusDescription(saved)).assertExists()

        saved = saved.copy(mode = NotificationModeOff)
        compose.runOnIdle { runBlocking { state.refresh() } }
        compose.onNodeWithText("Off").assertIsSelected()
        compose.onNodeWithText("Custom hours").assertDoesNotExist()
        compose.onNodeWithText("Not saved yet.").assertDoesNotExist()
    }

    @Test
    fun `unknown saved mode is honest and requires an explicit mode before save`() {
        val unknown = armed.copy(mode = "future")
        val state = FictionNotificationSettingsState(load = { Loaded(unknown) }, update = { Loaded(armed) })
        showSheet(state)

        compose.onNodeWithText("STATUS UNAVAILABLE").assertExists()
        compose.onNodeWithTag("fiction-notification-save").assertIsNotEnabled()
        for (label in listOf("Every chapter", "Off", "Wait for a backlog")) {
            compose.onNodeWithText(label).assertIsEnabled().assertIsNotSelected()
        }
        compose.onNodeWithText("Off").performScrollTo().performClick()
        compose.onNodeWithTag("fiction-notification-save").assertIsEnabled()
        compose.onNodeWithText("STATUS UNAVAILABLE").assertExists()
    }

    @Test
    fun `invalid hidden hours cannot block every or off and saved valid threshold is retained`() {
        val requests = mutableListOf<FictionNotificationSettingsRequest>()
        var saved = armed.copy(backlogHours = 5.0)
        var saves = 0
        val state = FictionNotificationSettingsState(
            load = { Loaded(saved) },
            update = {
                requests += it
                saved = saved.copy(mode = it.mode, backlogHours = it.backlogHours)
                Loaded(saved)
            },
        )
        showSheet(state) { saves++ }

        for ((label, mode) in listOf("Every chapter" to NotificationModeEvery, "Off" to NotificationModeOff)) {
            compose.onNodeWithText("Wait for a backlog").performScrollTo().performClick()
            compose.onNodeWithText("Custom hours").performScrollTo().performTextReplacement("not hours")
            compose.onNodeWithTag("fiction-notification-save").assertIsNotEnabled()
            compose.onNodeWithText(label).performScrollTo().performClick()
            compose.onNodeWithTag("fiction-notification-save").performScrollTo().assertIsEnabled().performClick()
            compose.waitForIdle()
            assertEquals(FictionNotificationSettingsRequest(mode, 5.0), requests.last())
        }
        assertEquals(2, saves)
        compose.onNodeWithText("Not saved yet.").assertDoesNotExist()
    }

    @Test
    fun `invalid saved threshold falls back to two hours when switching off`() {
        var request: FictionNotificationSettingsRequest? = null
        val invalid = armed.copy(backlogHours = -1.0)
        val state = FictionNotificationSettingsState(
            load = { Loaded(invalid) },
            update = { request = it; Loaded(invalid.copy(mode = it.mode, backlogHours = it.backlogHours)) },
        )
        showSheet(state)

        compose.onNodeWithTag("fiction-notification-save").assertIsNotEnabled()
        compose.onNodeWithText("Off").performScrollTo().performClick()
        compose.onNodeWithTag("fiction-notification-save").performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(FictionNotificationSettingsRequest(NotificationModeOff, 2.0), request)
    }

    @Test
    fun `small viewport scrolls custom threshold help and save into accessible reach`() {
        var request: FictionNotificationSettingsRequest? = null
        val state = FictionNotificationSettingsState(
            load = { Loaded(armed) },
            update = { request = it; Loaded(armed.copy(backlogHours = it.backlogHours)) },
        )
        showSheet(state)
        compose.onNodeWithText("Custom hours").performScrollTo().performTextReplacement("3.5")
        compose.onNodeWithText(
            "Ready, unplayed audio is counted at 1x speed. Get one alert at the threshold, " +
                "then no more until you listen below it.",
        ).performScrollTo().assertIsDisplayed()
        val save = compose.onNodeWithTag("fiction-notification-save").performScrollTo()
        save.assertIsDisplayed().assertIsEnabled().assertHasClickAction()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        val bounds = save.getUnclippedBoundsInRoot()
        assertTrue(bounds.left >= 0.dp)
        assertTrue(bounds.right <= 320.dp)
        save.performClick()
        compose.waitForIdle()
        assertEquals(FictionNotificationSettingsRequest(NotificationModeBacklog, 3.5), request)
    }

    @Test
    fun `saving disables editor and duplicate submits and success updates shared state before callback`() {
        val response = CompletableDeferred<FictionNotificationSettingsResult>()
        var requests = 0
        var callbacks = 0
        val state = FictionNotificationSettingsState(
            load = { Loaded(armed) },
            update = { requests++; response.await() },
        )
        showSheet(state) {
            assertEquals(NotificationModeOff, state.settings?.mode)
            callbacks++
        }
        compose.onNodeWithText("Off").performScrollTo().performClick()
        compose.onNodeWithTag("fiction-notification-save").performScrollTo().performClick()

        compose.onNodeWithText("SAVING…").assertExists()
        compose.onNodeWithTag("fiction-notification-save").assertIsNotEnabled().performClick()
        compose.onNodeWithText("Off").assertIsNotEnabled()
        compose.onNodeWithText("Every chapter").assertIsNotEnabled()
        assertEquals(1, requests)
        assertEquals(0, callbacks)
        compose.runOnIdle { response.complete(Loaded(armed.copy(mode = NotificationModeOff))) }
        compose.waitForIdle()
        assertEquals(1, callbacks)
        assertEquals(1, requests)
        compose.onNodeWithText("Not saved yet.").assertDoesNotExist()
    }

    @Test
    fun `failed save retains dirty draft and saved label without success callback`() {
        var callbacks = 0
        val state = FictionNotificationSettingsState(
            load = { Loaded(armed) },
            update = { Refused("Please try again.") },
        )
        showSheet(state) { callbacks++ }
        compose.onNodeWithText("Off").performScrollTo().performClick()
        compose.onNodeWithTag("fiction-notification-save").performScrollTo().performClick()

        compose.onNodeWithText("Off").assertIsSelected()
        compose.onNodeWithText("Not saved yet.").assertExists()
        compose.onNodeWithText("ALARM ARMED").assertExists()
        compose.onNodeWithText("Last known state. Refresh to confirm the current server settings.").assertExists()
        compose.onNodeWithText("Please try again.").assertExists()
        compose.onNodeWithTag("fiction-notification-save").assertIsEnabled()
        assertEquals(0, callbacks)
        assertEquals(armed, state.settings)
    }

    @Test
    fun `refresh after failed save updates status without hiding action error or discarding draft`() {
        var current = armed
        var failSave = true
        var callbacks = 0
        val requests = mutableListOf<FictionNotificationSettingsRequest>()
        val state = FictionNotificationSettingsState(
            load = { Loaded(current) },
            update = {
                requests += it
                if (failSave) Refused("Save refused.") else {
                    current = current.copy(mode = it.mode, backlogHours = it.backlogHours)
                    Loaded(current)
                }
            },
        )
        showSheet(state) { callbacks++ }
        compose.onNodeWithText("Custom hours").performScrollTo().performTextReplacement("3.5")
        compose.onNodeWithTag("fiction-notification-save").performScrollTo().performClick()
        compose.onNodeWithText("Save refused.").assertExists()
        compose.onNodeWithText("Not saved yet.").assertExists()

        current = armed.copy(backlogArmed = false, remainingSeconds = 600.0)
        compose.runOnIdle { runBlocking { state.refresh() } }

        compose.onNodeWithText("Save refused.").assertExists()
        compose.onNodeWithText("Not saved yet.").assertExists()
        compose.onNodeWithText("Wait for a backlog").assertIsSelected()
        compose.onNodeWithText("Custom hours").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("3.5")),
        )
        compose.onNodeWithText(notificationStatusDescription(current)).assertExists()
        compose.onNodeWithText("Ready to listen: 10 min.").assertExists()
        compose.onNodeWithText("Last known state. Refresh to confirm the current server settings.").assertDoesNotExist()
        compose.onNodeWithTag("fiction-notification-retry").assertDoesNotExist()
        assertFalse(state.stale)
        assertEquals(0, callbacks)

        failSave = false
        compose.onNodeWithTag("fiction-notification-save").performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()

        assertEquals(List(2) { FictionNotificationSettingsRequest(NotificationModeBacklog, 3.5) }, requests)
        assertEquals(1, callbacks)
        assertEquals(current, state.settings)
        compose.onNodeWithText("Save refused.").assertDoesNotExist()
        compose.onNodeWithText("Not saved yet.").assertDoesNotExist()
    }

    @Test
    fun `matching background settings do not acknowledge or replace an unsaved draft on later refresh`() {
        var current = armed
        val state = FictionNotificationSettingsState(load = { Loaded(current) }, update = { Loaded(current) })
        showSheet(state)
        compose.onNodeWithText("Custom hours").performScrollTo().performTextReplacement("3.5")

        current = armed.copy(backlogHours = 3.5)
        compose.runOnIdle { runBlocking { state.refresh() } }
        compose.onNodeWithText("Not saved yet.").assertExists()

        current = armed.copy(mode = NotificationModeOff, backlogHours = 5.0)
        compose.runOnIdle { runBlocking { state.refresh() } }

        compose.onNodeWithText(notificationStatusDescription(current)).assertExists()
        compose.onNodeWithText("Wait for a backlog").assertIsSelected()
        compose.onNodeWithText("Not saved yet.").assertExists()
        compose.onNodeWithText("Custom hours").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("3.5")),
        )
    }

    @Test
    fun `switching shared state cancels the old save without invoking the new callback or replacing its draft`() {
        val pending = CompletableDeferred<FictionNotificationSettingsResult>()
        val original = FictionNotificationSettingsState(load = { Loaded(armed) }, update = { pending.await() })
        val replacementSettings = armed.copy(mode = NotificationModeEvery, backlogHours = 5.0)
        val replacement = FictionNotificationSettingsState(
            load = { Loaded(replacementSettings) },
            update = { Loaded(replacementSettings) },
        )
        var state by mutableStateOf(original)
        var callbacks = 0
        compose.setContent {
            TtsRoadTheme {
                FictionNotificationSettingsSheet(state, onDismiss = {}, onSaved = { callbacks++ })
            }
        }
        compose.onNodeWithText("Off").performScrollTo().performClick()
        compose.onNodeWithTag("fiction-notification-save").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(original.saving) }

        compose.runOnIdle { state = replacement }
        compose.waitForIdle()
        assertFalse(original.saving)
        assertTrue(original.stale)
        compose.runOnIdle { pending.complete(Loaded(armed.copy(mode = NotificationModeOff))) }
        compose.waitForIdle()

        assertEquals(0, callbacks)
        assertEquals(replacementSettings, replacement.settings)
        assertEquals(armed, original.settings)
        compose.onNodeWithText("Every chapter").assertIsSelected()
        compose.onNodeWithText("Not saved yet.").assertDoesNotExist()
        compose.onNodeWithTag("fiction-notification-save").assertIsEnabled()
    }

    @Test
    fun `remembered state keys identity to repository fiction server token and availability but not refresh key`() {
        var repository by mutableStateOf(TtsRoadRepository(FakeSessionStore(SessionState())))
        var fictionId by mutableStateOf(7)
        var session by mutableStateOf(SessionState())
        var available by mutableStateOf(false)
        var refreshKey by mutableStateOf(0)
        lateinit var state: FictionNotificationSettingsState
        compose.setContent {
            state = rememberFictionNotificationSettings(repository, fictionId, session, available, refreshKey)
        }
        compose.waitForIdle()
        var previous = state
        compose.runOnIdle { refreshKey++ }
        compose.runOnIdle { assertSame(previous, state) }

        val changes: List<() -> Unit> = listOf(
            { repository = TtsRoadRepository(FakeSessionStore(SessionState())) },
            { fictionId++ },
            { session = session.copy(serverUrl = "https://example.test") },
            { session = session.copy(token = "test-token") },
            { available = true; session = session.copy(token = null) },
        )
        for (change in changes) {
            compose.runOnIdle { change() }
            compose.runOnIdle {
                assertNotSame(previous, state)
                assertNull(state.settings)
                assertNull(state.error)
                assertFalse(state.loading)
                runBlocking { state.refresh() }
                assertNull(state.error)
                previous = state
            }
        }
    }

    @Test
    fun `polling refreshes on resume chapter changes and every minute but stops while paused or stopped`() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    if (request.path == "/api/mobile/capabilities") {
                        """{"capabilities":{"backlog_notifications":true}}"""
                    } else {
                        """{"mode":"backlog","backlog_hours":2,"remaining_seconds":5400,"backlog_armed":true}"""
                    },
                )
        }
        server.start()
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this)
        }
        try {
            val session = SessionState(serverUrl = server.url("/").toString(), token = "test-token")
            val repository = TtsRoadRepository(FakeSessionStore(session))
            runBlocking { repository.refreshCurrentCapabilities() }
            val discoveryRequests = server.requestCount
            var refreshKey by mutableStateOf(0)
            lateinit var state: FictionNotificationSettingsState
            compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.STARTED }
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    state = rememberFictionNotificationSettings(repository, 7, session, true, refreshKey)
                }
            }
            compose.waitForIdle()
            assertEquals(discoveryRequests, server.requestCount)

            val pollingStartedAt = compose.mainClock.currentTime
            compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
            compose.waitUntil(5_000) { state.settings != null && !state.loading }
            assertEquals(discoveryRequests + 1, server.requestCount)
            val original = state
            compose.runOnIdle { refreshKey++ }
            compose.waitForIdle()
            compose.waitUntil(5_000) { server.requestCount == discoveryRequests + 2 && !state.loading }
            assertSame(original, state)

            compose.mainClock.advanceTimeBy(59_000 - (compose.mainClock.currentTime - pollingStartedAt))
            compose.waitForIdle()
            assertEquals(discoveryRequests + 2, server.requestCount)
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitUntil(5_000) { server.requestCount == discoveryRequests + 3 && !state.loading }
            compose.mainClock.advanceTimeBy(60_000)
            compose.waitUntil(5_000) { server.requestCount == discoveryRequests + 4 && !state.loading }

            var expectedRequests = discoveryRequests + 4
            for (backgroundState in listOf(Lifecycle.State.STARTED, Lifecycle.State.CREATED)) {
                compose.runOnUiThread { owner.lifecycle.currentState = backgroundState }
                compose.runOnIdle { refreshKey++ }
                compose.waitForIdle()
                compose.mainClock.advanceTimeBy(120_000)
                compose.waitForIdle()
                assertEquals(expectedRequests, server.requestCount)
                assertFalse(state.loading)

                compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
                expectedRequests++
                compose.waitUntil(5_000) { server.requestCount == expectedRequests && !state.loading }
                assertSame(original, state)
            }
        } finally {
            compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.DESTROYED }
            server.shutdown()
        }
    }
}
