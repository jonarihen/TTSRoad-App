package dk.perspektiva.ttsroad

import dk.perspektiva.ttsroad.data.ServerLogEntry
import dk.perspektiva.ttsroad.data.ServerLogsResponse
import dk.perspektiva.ttsroad.data.SessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerLogsStateTest {
    private val admin = SessionState(
        serverUrl = "https://server.example",
        token = "test-token",
        isAdmin = true,
    )

    private data class Request(
        val level: String?,
        val fictionId: Int?,
        val beforeId: Int?,
        val response: CompletableDeferred<ServerLogsResponse?> = CompletableDeferred(),
    )

    private class Harness(scope: TestScope, session: SessionState) {
        val requests = mutableListOf<Request>()
        val state = ServerLogsState(scope) { level, fictionId, beforeId ->
            Request(level, fictionId, beforeId).also { requests += it }.response.await()
        }.apply { updateAccess(session, true) }
    }

    private fun page(id: Int, cursor: Int? = null) = ServerLogsResponse(
        logs = listOf(ServerLogEntry(id = id, level = "ERROR", message = "Line $id")),
        hasMore = cursor != null,
        nextBeforeId = cursor,
    )

    private fun assertPage(state: ServerLogsState, ids: List<Int>, cursor: Int?) {
        assertEquals(ids, state.rows.map { it.id })
        assertEquals(cursor, state.nextBeforeId)
        assertEquals(cursor != null, state.hasMore)
    }

    @Test
    fun `ALL response arriving after ERROR cannot replace rows or cursor`() = runTest {
        val harness = Harness(this, admin)
        val state = harness.state
        state.refresh()
        runCurrent()
        val all = harness.requests.single()
        state.applyFilters("ERROR", null, null)
        runCurrent()
        val errors = harness.requests.last()
        assertNull(all.level)
        assertEquals("ERROR", errors.level)
        errors.response.complete(page(200, 190))
        runCurrent()
        all.response.complete(page(100, 90))
        runCurrent()

        assertEquals("ERROR", state.level)
        assertPage(state, listOf(200), 190)
        assertFalse(state.isLoading)
        assertNull(state.error)
        state.loadMore()
        runCurrent()
        val older = harness.requests.last()
        assertEquals("ERROR", older.level)
        assertEquals(190, older.beforeId)
        older.response.complete(page(180))
        runCurrent()
        assertPage(state, listOf(200, 180), null)
    }

    @Test
    fun `stale refresh failures and successes cannot finish the active refresh`() = runTest {
        for (failure in listOf(false, true)) {
            val harness = Harness(this, admin)
            val state = harness.state
            state.refresh()
            runCurrent()
            val all = harness.requests.single()
            state.applyFilters("ERROR", null, null)
            runCurrent()
            val errors = harness.requests.last()
            if (failure) all.response.completeExceptionally(IllegalStateException("Old ALL failure"))
            else all.response.complete(page(100, 90))
            runCurrent()

            assertTrue(state.isLoading)
            assertFalse(state.isLoadingMore)
            assertFalse(state.loadedOnce)
            assertPage(state, emptyList(), null)
            assertNull(state.error)
            errors.response.complete(page(200, 190))
            runCurrent()
            assertPage(state, listOf(200), 190)
            assertFalse(state.isLoading)
            assertTrue(state.loadedOnce)
        }
    }

    @Test
    fun `old load-more cannot append to a new refresh or finish current pagination`() = runTest {
        for (failure in listOf(false, true)) {
            val harness = Harness(this, admin)
            val state = harness.state
            state.refresh()
            runCurrent()
            harness.requests.single().response.complete(page(100, 90))
            runCurrent()
            state.loadMore()
            runCurrent()
            val oldMore = harness.requests.last()
            assertEquals(90, oldMore.beforeId)
            state.refresh()
            assertTrue(state.isLoading)
            assertFalse(state.isLoadingMore)
            assertNull(state.nextBeforeId)
            runCurrent()
            val fresh = harness.requests.last()
            assertNull(fresh.beforeId)
            fresh.response.complete(page(200, 190))
            runCurrent()
            state.loadMore()
            runCurrent()
            val currentMore = harness.requests.last()
            assertEquals(190, currentMore.beforeId)
            if (failure) oldMore.response.completeExceptionally(IllegalStateException("Old page failure"))
            else oldMore.response.complete(page(80, 70))
            runCurrent()

            assertPage(state, listOf(200), 190)
            assertTrue(state.isLoadingMore)
            assertFalse(state.isLoading)
            assertNull(state.error)
            currentMore.response.complete(page(180, 170))
            runCurrent()
            assertPage(state, listOf(200, 180), 170)
            state.loadMore()
            runCurrent()
            assertEquals(170, harness.requests.last().beforeId)
            harness.requests.last().response.complete(page(160))
            runCurrent()
            assertPage(state, listOf(200, 180, 160), null)
        }
    }

    @Test
    fun `old load-more finishing before the new refresh cannot publish rows or errors`() = runTest {
        for (failure in listOf(false, true)) {
            val harness = Harness(this, admin)
            val state = harness.state
            state.refresh()
            runCurrent()
            harness.requests.single().response.complete(page(100, 90))
            runCurrent()
            state.loadMore()
            runCurrent()
            val oldMore = harness.requests.last()
            state.refresh()
            runCurrent()
            val fresh = harness.requests.last()
            if (failure) oldMore.response.completeExceptionally(IllegalStateException("Old page failure"))
            else oldMore.response.complete(page(80, 70))
            runCurrent()

            assertPage(state, listOf(100), null)
            assertTrue(state.isLoading)
            assertFalse(state.isLoadingMore)
            assertFalse(state.loadedOnce)
            assertNull(state.error)
            fresh.response.complete(page(200, 190))
            runCurrent()
            assertPage(state, listOf(200), 190)
            assertFalse(state.isLoading)
        }
    }

    @Test
    fun `fiction filter changes invalidate old pages even when severity is unchanged`() = runTest {
        val harness = Harness(this, admin)
        val state = harness.state
        state.applyFilters("ERROR", 7, "Seven")
        runCurrent()
        harness.requests.single().response.complete(page(100, 90))
        runCurrent()
        state.loadMore()
        runCurrent()
        val oldMore = harness.requests.last()
        state.applyFilters("ERROR", 8, "Eight")
        assertPage(state, emptyList(), null)
        runCurrent()
        val fresh = harness.requests.last()
        assertEquals(7, oldMore.fictionId)
        assertEquals(8, fresh.fictionId)
        fresh.response.complete(page(200, 190))
        runCurrent()
        oldMore.response.complete(page(80, 70))
        runCurrent()

        assertPage(state, listOf(200), 190)
        assertEquals("Eight", state.fictionLabel)
        state.loadMore()
        runCurrent()
        val currentMore = harness.requests.last()
        assertEquals("ERROR", currentMore.level)
        assertEquals(8, currentMore.fictionId)
        assertEquals(190, currentMore.beforeId)
        currentMore.response.complete(page(180))
        runCurrent()
        assertPage(state, listOf(200, 180), null)
    }

    @Test
    fun `revoked capability admin or session rejects refresh and page responses`() = runTest {
        val revoked = listOf(
            admin to false,
            admin.copy(isAdmin = false) to false,
            admin.copy(token = null) to true,
        )
        for ((session, allowed) in revoked) {
            for (loadMore in listOf(false, true)) {
                for (failure in listOf(false, true)) {
                    val harness = Harness(this, admin)
                    val state = harness.state
                    state.refresh()
                    runCurrent()
                    if (loadMore) {
                        harness.requests.last().response.complete(page(100, 90))
                        runCurrent()
                        state.loadMore()
                        runCurrent()
                    }
                    val old = harness.requests.last()
                    state.updateAccess(session, allowed)
                    if (failure) old.response.completeExceptionally(IllegalStateException("Revoked failure"))
                    else old.response.complete(page(80, 70))
                    runCurrent()

                    assertPage(state, emptyList(), null)
                    assertNull(state.error)
                    assertFalse(state.isLoading)
                    assertFalse(state.isLoadingMore)
                    assertFalse(state.loadedOnce)
                    val count = harness.requests.size
                    state.refresh()
                    state.loadMore()
                    runCurrent()
                    assertEquals(count, harness.requests.size)
                }
            }
        }
    }

    @Test
    fun `same-admin session changes start a new walk and cannot revive old responses`() = runTest {
        val replacements = listOf(
            admin.copy(token = "new-token"),
            admin.copy(serverUrl = "https://other.example"),
        )
        for (replacement in replacements) {
            for (loadMore in listOf(false, true)) {
                val harness = Harness(this, admin)
                val state = harness.state
                state.applyFilters("ERROR", 7, "Seven")
                runCurrent()
                if (loadMore) {
                    harness.requests.last().response.complete(page(100, 90))
                    runCurrent()
                    state.loadMore()
                    runCurrent()
                }
                val old = harness.requests.last()
                state.updateAccess(replacement, true)
                assertNull(state.level)
                assertNull(state.fictionId)
                assertPage(state, emptyList(), null)
                state.refresh()
                runCurrent()
                val fresh = harness.requests.last()
                fresh.response.complete(page(200, 190))
                runCurrent()
                old.response.complete(page(80, 70))
                runCurrent()

                assertPage(state, listOf(200), 190)
                assertFalse(state.isLoading)
                assertFalse(state.isLoadingMore)
                assertNull(state.error)
            }
        }
    }

    @Test
    fun `restoring the same access never revives a request from before revocation`() = runTest {
        val harness = Harness(this, admin)
        val state = harness.state
        state.refresh()
        runCurrent()
        val old = harness.requests.single()
        state.updateAccess(admin, false)
        state.updateAccess(admin, true)
        state.refresh()
        runCurrent()
        val fresh = harness.requests.last()
        old.response.complete(page(100, 90))
        runCurrent()
        assertPage(state, emptyList(), null)
        assertTrue(state.isLoading)
        fresh.response.complete(page(200, 190))
        runCurrent()
        assertPage(state, listOf(200), 190)
    }

    @Test
    fun `load-more is single flight and current failures preserve its retry cursor`() = runTest {
        val harness = Harness(this, admin)
        val state = harness.state
        state.refresh()
        state.loadMore()
        runCurrent()
        assertEquals(1, harness.requests.size)
        harness.requests.last().response.complete(page(100, 90))
        runCurrent()
        state.loadMore()
        state.loadMore()
        runCurrent()
        assertEquals(2, harness.requests.size)
        harness.requests.last().response.completeExceptionally(IllegalStateException("Current failure"))
        runCurrent()
        assertPage(state, listOf(100), 90)
        assertEquals("Current failure", state.error)
        assertFalse(state.isLoadingMore)
        state.loadMore()
        assertNull(state.error)
        runCurrent()
        assertEquals(90, harness.requests.last().beforeId)
        harness.requests.last().response.complete(page(80))
        runCurrent()
        assertPage(state, listOf(100, 80), null)
        state.loadMore()
        runCurrent()
        assertEquals(3, harness.requests.size)
    }

    @Test
    fun `screen disposal invalidates pending responses`() = runTest {
        val harness = Harness(this, admin)
        val state = harness.state
        state.refresh()
        runCurrent()
        state.invalidate()
        harness.requests.single().response.complete(page(100, 90))
        runCurrent()
        assertPage(state, emptyList(), null)
        assertFalse(state.isLoading)
        assertFalse(state.loadedOnce)
        assertNull(state.error)
    }
}
