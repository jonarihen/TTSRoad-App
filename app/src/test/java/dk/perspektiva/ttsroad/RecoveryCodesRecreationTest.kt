package dk.perspektiva.ttsroad

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import android.os.Parcel
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dk.perspektiva.ttsroad.data.LoginResponse
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.SessionStore
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

class RecoveryCodesTestActivity : ComponentActivity() {
    internal lateinit var owner: RecoveryCodesOwner
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fixture?.let { render(it, savedInstanceState == null) }
    }

    internal fun savedState(): Bundle = Bundle().also { super.onSaveInstanceState(it) }

    internal fun render(fixture: RecoveryCodesFixture, showSettings: Boolean = true) {
        owner = recoveryCodesOwner(fixture.repository, fixture.sessions) { fixture.sessions.value }
        setContent {
            val session by fixture.sessions.collectAsStateWithLifecycle()
            val state by owner.state.collectAsStateWithLifecycle()
            TtsRoadTheme {
                CompositionLocalProvider(LocalRecoveryCodesOwner provides owner) {
                    Column {
                        if (showSettings && session.isLoggedIn) AccountSecuritySettings(fixture.repository)
                    }
                    if (state.codes.isNotEmpty() && owner.owns(session)) RecoveryCodesDialog(owner)
                }
            }
        }
    }

    companion object {
        internal var fixture: RecoveryCodesFixture? = null
    }
}

internal class RecoveryCodesFixture(server: MockWebServer, enabled: Boolean) {
    val sessions = MutableStateFlow(
        SessionState(serverUrl = server.url("/").toString(), token = "test-session", username = "reader"),
    )
    val repository = TtsRoadRepository(object : SessionStore {
        override suspend fun current() = sessions.value
        override suspend fun saveLogin(baseUrl: String, response: LoginResponse) = Unit
        override suspend fun clearToken() {
            sessions.value = sessions.value.copy(token = null)
        }
    })
    var enabled = enabled
    var delayCodes = false
    var rejectCodes = false
    val requestStarted = CountDownLatch(1)
    val responseAllowed = CountDownLatch(1)
    val codeRequests = mutableListOf<String>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val body = when (path) {
                    "/api/mobile/capabilities" -> """{"capabilities":{"account_security":true}}"""
                    "/api/mobile/account/2fa" -> """{"enabled":${this@RecoveryCodesFixture.enabled}}"""
                    "/api/mobile/account/2fa/setup" -> """{"secret":"provisional-key"}"""
                    "/api/mobile/logout" -> "{}"
                    else -> {
                        synchronized(codeRequests) { codeRequests.add(path) }
                        requestStarted.countDown()
                        if (delayCodes) check(responseAllowed.await(10, TimeUnit.SECONDS))
                        if (rejectCodes) {
                            return MockResponse().setResponseCode(400)
                                .setHeader("Content-Type", "application/json")
                                .setBody("""{"detail":"That code didn't match."}""")
                        }
                        this@RecoveryCodesFixture.enabled = true
                        """{"enabled":true,"recovery_codes":["one-time-alpha","one-time-beta"]}"""
                    }
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
        runBlocking { repository.refreshCurrentCapabilities() }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp", application = Application::class)
class RecoveryCodesRecreationTest {
    private val compose = createAndroidComposeRule<RecoveryCodesTestActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val application = RuntimeEnvironment.getApplication()
            shadowOf(application.packageManager).addActivityIfNotPresent(
                ComponentName(application, RecoveryCodesTestActivity::class.java),
            )
        }
    }).around(compose)
    private lateinit var server: MockWebServer
    private lateinit var fixture: RecoveryCodesFixture

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        if (::fixture.isInitialized) fixture.responseAllowed.countDown()
        RecoveryCodesTestActivity.fixture = null
        server.shutdown()
    }

    private fun show(enabled: Boolean = false) {
        fixture = RecoveryCodesFixture(server, enabled)
        RecoveryCodesTestActivity.fixture = fixture
        compose.activityRule.scenario.onActivity { it.render(fixture) }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(if (enabled) "NEW CODES" else "SET UP 2FA")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun enable() {
        compose.onNodeWithText("SET UP 2FA").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Authentication code").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Authentication code").performTextInput("123456")
        compose.onNodeWithText("ENABLE").performClick()
    }

    private fun reissue() {
        compose.onNodeWithText("NEW CODES").performClick()
        compose.onNodeWithText("REPLACE").performClick()
    }

    private fun assertCodes() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Save these recovery codes").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("one-time-alpha\none-time-beta").assertIsDisplayed()
    }

    private fun assertNoSavedCodes() {
        compose.activityRule.scenario.onActivity {
            val parcel = Parcel.obtain()
            try {
                parcel.writeBundle(it.savedState())
                val saved = parcel.marshall().toString(Charsets.UTF_16LE)
                assertFalse(saved.contains("one-time-alpha"))
                assertFalse(saved.contains("one-time-beta"))
            } finally {
                parcel.recycle()
            }
        }
    }

    private fun rotate(): RecoveryCodesOwner {
        val previous = compose.activity.owner
        RuntimeEnvironment.setQualifiers("w640dp-h320dp-land")
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { assertSame(previous, it.owner) }
        return previous
    }

    @Test
    fun `enabled codes survive rotation outside the settings screen until acknowledged`() {
        show()
        enable()
        assertCodes()
        assertNoSavedCodes()
        val owner = rotate()
        assertCodes()
        compose.onNodeWithText("CHANGE PASSWORD").assertDoesNotExist()
        compose.onNodeWithText("I SAVED THEM").performClick()
        compose.onNodeWithText("Save these recovery codes").assertDoesNotExist()
        assertTrue(owner.state.value.codes.isEmpty())
        rotate()
        compose.onNodeWithText("Save these recovery codes").assertDoesNotExist()
        assertEquals(listOf("/api/mobile/account/2fa/enable"), fixture.codeRequests)
    }

    @Test
    fun `reissued codes survive rotation until acknowledged without another request`() {
        show(enabled = true)
        reissue()
        assertCodes()
        assertNoSavedCodes()
        rotate()
        assertCodes()
        compose.onNodeWithText("I SAVED THEM").performClick()
        rotate()
        compose.onNodeWithText("Save these recovery codes").assertDoesNotExist()
        assertEquals(listOf("/api/mobile/account/2fa/recovery-codes"), fixture.codeRequests)
    }

    @Test
    fun `rotation while enabling does not cancel or lose the one-time response`() {
        show()
        fixture.delayCodes = true
        enable()
        assertTrue(fixture.requestStarted.await(5, TimeUnit.SECONDS))
        val owner = rotate()
        assertTrue(owner.state.value.busy)
        compose.runOnIdle { owner.acknowledge() }
        assertTrue(owner.state.value.busy)
        fixture.responseAllowed.countDown()
        assertCodes()
        assertEquals(1, fixture.codeRequests.size)
    }

    @Test
    fun `rotation while reissuing does not cancel or lose the one-time response`() {
        show(enabled = true)
        fixture.delayCodes = true
        reissue()
        assertTrue(fixture.requestStarted.await(5, TimeUnit.SECONDS))
        rotate()
        fixture.responseAllowed.countDown()
        assertCodes()
        assertEquals(1, fixture.codeRequests.size)
    }

    @Test
    fun `cancelling setup before submission never requests or retains codes`() {
        show()
        compose.onNodeWithText("SET UP 2FA").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("CANCEL").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("CANCEL").performClick()
        assertTrue(fixture.codeRequests.isEmpty())
        assertTrue(compose.activity.owner.state.value.codes.isEmpty())
        rotate()
        compose.onNodeWithText("Save these recovery codes").assertDoesNotExist()
    }

    @Test
    fun `cancelling replacement before submission never requests or retains codes`() {
        show(enabled = true)
        compose.onNodeWithText("NEW CODES").performClick()
        compose.onNodeWithText("CANCEL").performClick()
        assertTrue(fixture.codeRequests.isEmpty())
        rotate()
        compose.onNodeWithText("Save these recovery codes").assertDoesNotExist()
    }

    @Test
    fun `account change and sign out clear displayed codes`() {
        show(enabled = true)
        reissue()
        assertCodes()
        val owner = compose.activity.owner
        compose.runOnIdle { fixture.sessions.value = fixture.sessions.value.copy(username = "other-reader") }
        compose.waitUntil(5_000) { owner.state.value.codes.isEmpty() }
        compose.onNodeWithText("Save these recovery codes").assertDoesNotExist()
        compose.runOnIdle { owner.reissue() }
        assertCodes()
        runBlocking { fixture.repository.logout() }
        compose.waitForIdle()
        compose.waitUntil(5_000) { owner.state.value.codes.isEmpty() }
        compose.onNodeWithText("Save these recovery codes").assertDoesNotExist()
        rotate()
        assertFalse(owner.state.value.busy)
    }

    @Test
    fun `server change clears codes even with the same username and token`() {
        show(enabled = true)
        reissue()
        assertCodes()
        val owner = compose.activity.owner
        compose.runOnIdle {
            fixture.sessions.value = fixture.sessions.value.copy(serverUrl = "https://other-server.example/")
        }
        compose.waitUntil(5_000) { owner.state.value.codes.isEmpty() }
        compose.onNodeWithText("Save these recovery codes").assertDoesNotExist()
    }

    @Test
    fun `rejected enable followed by cancel does not retain codes or repeat the request`() {
        show()
        fixture.rejectCodes = true
        enable()
        compose.waitUntil(5_000) { compose.activity.owner.state.value.error != null }
        compose.onNodeWithText("CANCEL").performClick()
        rotate()
        compose.onNodeWithText("Save these recovery codes").assertDoesNotExist()
        assertTrue(compose.activity.owner.state.value.codes.isEmpty())
        assertFalse(compose.activity.owner.state.value.busy)
        assertEquals(1, fixture.codeRequests.size)
    }

    @Test
    fun `sign out before a successful one-time response leaves no retained secrets`() {
        show(enabled = true)
        fixture.delayCodes = true
        reissue()
        assertTrue(fixture.requestStarted.await(5, TimeUnit.SECONDS))
        val owner = compose.activity.owner
        compose.runOnIdle { fixture.sessions.value = fixture.sessions.value.copy(token = null) }
        compose.waitUntil(5_000) { !owner.state.value.busy }
        fixture.responseAllowed.countDown()
        rotate()
        compose.onNodeWithText("Save these recovery codes").assertDoesNotExist()
        assertTrue(owner.state.value.codes.isEmpty())
    }

    @Test
    fun `finishing while awaiting a response cancels the operation and clears the owner`() {
        show(enabled = true)
        fixture.delayCodes = true
        reissue()
        assertTrue(fixture.requestStarted.await(5, TimeUnit.SECONDS))
        val owner = compose.activity.owner
        compose.activityRule.scenario.close()
        assertFalse(owner.state.value.busy)
        assertTrue(owner.state.value.codes.isEmpty())
        fixture.responseAllowed.countDown()
    }

    @Test
    fun `session replacement while awaiting response cannot publish old account codes`() {
        show(enabled = true)
        fixture.delayCodes = true
        reissue()
        assertTrue(fixture.requestStarted.await(5, TimeUnit.SECONDS))
        val owner = compose.activity.owner
        compose.runOnIdle { fixture.sessions.value = fixture.sessions.value.copy(token = "replacement-session") }
        compose.waitUntil(5_000) { !owner.state.value.busy }
        fixture.responseAllowed.countDown()
        rotate()
        compose.onNodeWithText("Save these recovery codes").assertDoesNotExist()
        assertTrue(owner.state.value.codes.isEmpty())
    }

    @Test
    fun `finishing the activity clears secrets and a fresh owner does not restore them`() {
        show(enabled = true)
        reissue()
        assertCodes()
        val owner = compose.activity.owner
        compose.activityRule.scenario.close()
        assertTrue(owner.state.value.codes.isEmpty())
        val fresh = RecoveryCodesOwner(fixture.repository, fixture.sessions, { fixture.sessions.value })
        assertNotSame(owner, fresh)
        assertTrue(fresh.state.value.codes.isEmpty())
        androidx.lifecycle.ViewModelStore().apply { put("fresh", fresh); clear() }
    }
}
