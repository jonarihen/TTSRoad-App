package dk.perspektiva.ttsroad.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A setting changed while its PATCH could not go out must not be reverted by the next pull, which
 * would otherwise adopt the value the user had changed away from (#453).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountPreferenceSyncTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: TtsRoadRepository
    private lateinit var playback: PlaybackPreferences
    private lateinit var unsynced: UnsyncedPreferenceKeys
    private lateinit var sync: AccountPreferenceSync

    @Before
    fun setUp() = runBlocking {
        server = MockWebServer().apply { start() }
        val session = SessionState(serverUrl = server.url("/").toString(), token = "t0ken", username = "reader")
        repository = TtsRoadRepository(
            object : SessionStore {
                override suspend fun current() = session
                override suspend fun saveLogin(baseUrl: String, response: LoginResponse) = Unit
                override suspend fun clearToken() = Unit
            },
        )
        server.enqueue(
            json(
                """{"api_version": 1, "server": {"name": "TTSRoad", "version": "1.5.0"},
                   "capabilities": {"player_preferences": true}, "limits": {}}""",
            ),
        )
        repository.refreshCurrentCapabilities()
        server.takeRequest()

        val context = RuntimeEnvironment.getApplication()
        playback = PlaybackPreferences(context)
        unsynced = UnsyncedPreferenceKeys(context)
        sync = AccountPreferenceSync(
            repository = repository,
            playbackPreferences = playback,
            readerPreferences = InMemoryReaderPreferences(),
            chapterListPreferences = ChapterListPreferences(context),
            unsynced = unsynced,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String) = MockResponse().setBody(body).setHeader("Content-Type", "application/json")

    @Test
    fun `a setting changed offline survives the next pull and is pushed then`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        sync.setAutoMarkPlayed(false)
        server.takeRequest()
        assertEquals(setOf(AccountPreferenceKeys.AutoMarkPlayed), unsynced.current())

        server.enqueue(json("""{"preferences": {"auto_mark_played": true, "reader_theme": "sepia"}}"""))
        server.enqueue(json("""{"preferences": {"auto_mark_played": false, "reader_theme": "sepia"}}"""))
        val reconciled = sync.pull()

        assertEquals("GET", server.takeRequest().method)
        val push = server.takeRequest()
        assertEquals("PATCH", push.method)
        // Only the owed key: everything else on this phone may be the stale copy.
        assertEquals("""{"auto_mark_played":false}""", push.body.readUtf8())
        assertFalse(playback.current().autoMarkPlayed)
        assertEquals(false, reconciled?.autoMarkPlayed)
        // The keys the phone did not owe are still adopted.
        assertEquals(ReaderTheme.Paper, reconciled?.readerTheme)
        assertTrue(unsynced.current().isEmpty())

        // Delivered, so the account is authoritative again.
        server.enqueue(json("""{"preferences": {"auto_mark_played": true}}"""))
        sync.pull()
        assertTrue(playback.current().autoMarkPlayed)
    }

    @Test
    fun `a pull that cannot deliver the owed key keeps it and the local value`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        sync.setAutoMarkPlayed(false)

        server.enqueue(json("""{"preferences": {"auto_mark_played": true}}"""))
        server.enqueue(MockResponse().setResponseCode(503))
        sync.pull()

        assertFalse(playback.current().autoMarkPlayed)
        assertEquals(setOf(AccountPreferenceKeys.AutoMarkPlayed), unsynced.current())
    }
}
