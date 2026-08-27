package dk.perspektiva.ttsroad.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

private class FakeDatesSessionStore(
    private var state: SessionState = SessionState(),
) : SessionStore {
    override suspend fun current(): SessionState = state

    override suspend fun saveLogin(baseUrl: String, response: LoginResponse) {
        state = SessionState(
            serverUrl = normalizeBaseUrl(baseUrl),
            token = response.token,
            username = response.user.username,
        )
    }

    override suspend fun clearToken() {
        state = state.copy(token = null)
    }
}

/**
 * The two timestamps the browse order rests on (#164).
 *
 * The backend has serialised `created_at` and `updated_at` on every fiction since before this app
 * existed; the client simply never decoded them, which is why the shelf could not be ordered by
 * anything. Now that a sort depends on them, both directions are worth pinning: that a current
 * server's values arrive, and that an older server's *silence* arrives as null rather than as a
 * parse failure. The second is the one that would break quietly — a payload missing a key is not
 * an error, it is the default, and nothing else in the app would notice until a shelf sorted itself
 * into an order nobody asked for.
 */
class FictionDatesRepositoryTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private suspend fun repository(): TtsRoadRepository {
        val store = FakeDatesSessionStore()
        store.saveLogin(
            server.url("/").toString(),
            LoginResponse(token = "t0ken", user = MobileUser(id = 1, username = "reader")),
        )
        return TtsRoadRepository(store)
    }

    private fun json(body: String) =
        MockResponse().setBody(body).setHeader("Content-Type", "application/json")

    @Test
    fun `both timestamps are decoded as the server spells them`() = runTest {
        val repository = repository()
        server.enqueue(
            json(
                """
                {"fictions": [{"id": 1, "title": "Ashes",
                               "created_at": "2026-07-01T09:15:00Z",
                               "updated_at": "2026-08-26T23:00:00Z",
                               "last_polled_at": "2026-08-27T06:00:00Z"}]}
                """,
            ),
        )

        val fiction = repository.library().fictions.single()

        assertEquals("2026-07-01T09:15:00Z", fiction.createdAt)
        assertEquals("2026-08-26T23:00:00Z", fiction.updatedAt)
        assertEquals("2026-08-27T06:00:00Z", fiction.lastPolledAt)
    }

    @Test
    fun `a server that sends neither still parses, and says nothing rather than guessing`() =
        runTest {
            val repository = repository()
            server.enqueue(json("""{"fictions": [{"id": 1, "title": "Ashes"}]}"""))

            val fiction = repository.library().fictions.single()

            assertNull(fiction.createdAt)
            assertNull(fiction.updatedAt)
        }

    @Test
    fun `an explicit null is the same answer as an absent key`() = runTest {
        // The backend's `_utc_iso` returns None for a column that was never set, and FastAPI
        // serialises that as a present `null` rather than dropping the key. Both shapes reach this
        // client and both have to mean "unknown", or a shelf would sort differently depending on
        // which one a given deployment happened to produce.
        val repository = repository()
        server.enqueue(
            json(
                """
                {"fictions": [{"id": 1, "title": "Ashes",
                               "created_at": null, "updated_at": null}]}
                """,
            ),
        )

        val fiction = repository.library().fictions.single()

        assertNull(fiction.createdAt)
        assertNull(fiction.updatedAt)
    }

    @Test
    fun `a decoded shelf orders by the dates it was given`() = runTest {
        // The end-to-end shape of #164: what the server sent, ordered the way the browse grid
        // orders it. Parsing and comparing are tested apart; this is the seam between them.
        val repository = repository()
        server.enqueue(
            json(
                """
                {"fictions": [
                  {"id": 1, "title": "Ashes",  "updated_at": "2026-07-01T09:15:00Z"},
                  {"id": 2, "title": "Embers", "updated_at": "2026-08-26T23:00:00Z"},
                  {"id": 3, "title": "Cinders"}
                ]}
                """,
            ),
        )

        val ordered = repository.library().fictions.sortedForBrowsing(FictionSort.RecentlyUpdated)

        assertEquals(listOf(2, 1, 3), ordered.map { it.id })
    }
}
