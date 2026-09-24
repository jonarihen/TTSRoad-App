package dk.perspektiva.ttsroad

import dk.perspektiva.ttsroad.data.FictionNotificationSettings
import dk.perspektiva.ttsroad.data.FictionNotificationSettingsRequest
import dk.perspektiva.ttsroad.data.FictionNotificationSettingsResult
import dk.perspektiva.ttsroad.data.FictionNotificationSettingsResult.Loaded
import dk.perspektiva.ttsroad.data.FictionNotificationSettingsResult.Refused
import dk.perspektiva.ttsroad.data.FictionNotificationSettingsResult.Unsupported
import dk.perspektiva.ttsroad.data.NotificationModeBacklog
import dk.perspektiva.ttsroad.data.NotificationModeEvery
import dk.perspektiva.ttsroad.data.NotificationModeOff
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FictionNotificationSettingsStateTest {
    private val armed = FictionNotificationSettings(NotificationModeBacklog, 2.0, 3600.0, true)
    private val off = armed.copy(mode = NotificationModeOff)
    private val request = FictionNotificationSettingsRequest(NotificationModeOff, 2.0)

    @Test
    fun `starts unknown and refuses to save before any successful load`() = runTest {
        var saves = 0
        val state = FictionNotificationSettingsState(
            load = { Loaded(armed) },
            update = { saves++; Loaded(off) },
        )

        assertNull(state.settings)
        assertNull(state.error)
        assertFalse(state.loading)
        assertFalse(state.saving)
        assertFalse(state.stale)
        assertFalse(state.save(request))
        assertEquals(0, saves)

        state.refresh()
        assertEquals(armed, state.settings)
        assertTrue(state.save(request))
        assertEquals(off, state.settings)
        assertEquals(1, saves)
    }

    @Test
    fun `first load exceptions use safe errors without enabling a default save`() = runTest {
        val state = FictionNotificationSettingsState(
            load = { throw IOException("https://private.example token=secret") },
            update = { fail("Cannot save unknown settings"); Loaded(off) },
        )

        state.refresh()

        assertNull(state.settings)
        assertEquals("Could not load chapter notification settings. Try again.", state.error)
        assertFalse(state.loading)
        assertFalse(state.stale)
        assertFalse(state.save(request))
    }

    @Test
    fun `refused and unsupported first loads remain unknown until retry succeeds`() = runTest {
        var response: FictionNotificationSettingsResult = Refused("Follow this fiction first.")
        val state = FictionNotificationSettingsState(load = { response }, update = { Loaded(off) })

        state.refresh()
        assertEquals("Follow this fiction first.", state.error)
        assertNull(state.settings)
        assertFalse(state.save(request))

        response = Unsupported
        state.refresh()
        assertEquals("This server cannot configure chapter notifications.", state.error)
        assertNull(state.settings)
        assertFalse(state.save(request))

        response = Loaded(armed)
        state.refresh()
        assertEquals(armed, state.settings)
        assertNull(state.error)
        assertFalse(state.stale)
    }

    @Test
    fun `refresh failure preserves explicitly stale last known settings until recovery`() = runTest {
        var failLoad = false
        val state = FictionNotificationSettingsState(
            load = { if (failLoad) throw IOException("private address") else Loaded(armed) },
            update = { Loaded(off) },
        )
        state.refresh()
        failLoad = true

        state.refresh()

        assertEquals(armed, state.settings)
        assertTrue(state.stale)
        assertEquals("Could not load chapter notification settings. Try again.", state.error)
        assertFalse(state.loading)

        failLoad = false
        state.refresh()
        assertEquals(armed, state.settings)
        assertFalse(state.stale)
        assertNull(state.error)
    }

    @Test
    fun `failed saves never adopt the draft and a successful save clears stale errors`() = runTest {
        var response: FictionNotificationSettingsResult = Refused("Follow this fiction first.")
        var throwOnSave = false
        val state = FictionNotificationSettingsState(
            load = { Loaded(armed) },
            update = { if (throwOnSave) throw IOException("secret") else response },
        )
        state.refresh()

        assertFalse(state.save(request))
        assertEquals(armed, state.settings)
        assertEquals("Follow this fiction first.", state.error)
        assertTrue(state.stale)
        assertFalse(state.saving)

        response = Unsupported
        assertFalse(state.save(request))
        assertEquals(armed, state.settings)
        assertTrue(state.stale)

        throwOnSave = true
        assertFalse(state.save(request))
        assertEquals(armed, state.settings)
        assertEquals("Could not save chapter notification settings. Try again.", state.error)
        assertFalse(state.saving)

        throwOnSave = false
        response = Loaded(off)
        assertTrue(state.save(request))
        assertEquals(off, state.settings)
        assertNull(state.error)
        assertFalse(state.stale)
    }

    @Test
    fun `successful refresh recovers server status without clearing any failed save error`() = runTest {
        val failures: List<suspend () -> FictionNotificationSettingsResult> = listOf(
            { Refused("Follow this fiction first.") },
            { Unsupported },
            { throw IOException("private address and token") },
        )
        for (failure in failures) {
            var current = armed
            var failSave = true
            val state = FictionNotificationSettingsState(
                load = { Loaded(current) },
                update = { if (failSave) failure() else Loaded(off) },
            )
            state.refresh()
            assertFalse(state.save(request))
            val saveError = state.error
            assertTrue(!saveError.isNullOrBlank())
            assertTrue(state.stale)

            current = armed.copy(backlogArmed = false, remainingSeconds = 1800.0)
            repeat(2) { state.refresh() }

            assertEquals(current, state.settings)
            assertFalse(state.stale)
            assertEquals(saveError, state.error)
            assertFalse(state.loading)

            failSave = false
            assertTrue(state.save(request))
            assertEquals(off, state.settings)
            assertNull(state.error)
            assertFalse(state.stale)
        }
    }

    @Test
    fun `background load failures cannot replace save errors and successful save clears both`() = runTest {
        var failLoad = false
        var failSave = true
        val state = FictionNotificationSettingsState(
            load = { if (failLoad) Refused("Refresh failed.") else Loaded(armed) },
            update = { if (failSave) Refused("Save failed.") else Loaded(off) },
        )
        state.refresh()
        assertFalse(state.save(request))
        failLoad = true
        state.refresh()

        assertEquals("Save failed.", state.error)
        assertEquals(armed, state.settings)
        assertTrue(state.stale)

        failSave = false
        assertTrue(state.save(request))
        assertEquals(off, state.settings)
        assertNull(state.error)
        assertFalse(state.stale)

        state.refresh()
        assertEquals("Refresh failed.", state.error)
        assertEquals(off, state.settings)
        assertTrue(state.stale)
    }

    @Test
    fun `blank refusal messages fall back to the operation that failed`() = runTest {
        var failLoad = true
        val state = FictionNotificationSettingsState(
            load = { if (failLoad) Refused(" ") else Loaded(armed) },
            update = { Refused("") },
        )

        state.refresh()
        assertEquals("Could not load chapter notification settings. Try again.", state.error)
        failLoad = false
        state.refresh()
        assertNull(state.error)

        assertFalse(state.save(request))
        assertEquals("Could not save chapter notification settings. Try again.", state.error)
        state.refresh()
        assertEquals("Could not save chapter notification settings. Try again.", state.error)
        assertFalse(state.stale)
    }

    @Test
    fun `overlapping refreshes share one request and later refresh still loads`() = runTest {
        var loads = 0
        val response = CompletableDeferred<FictionNotificationSettingsResult>()
        val state = FictionNotificationSettingsState(
            load = { loads++; response.await() },
            update = { Loaded(off) },
        )
        val first = launch(start = CoroutineStart.UNDISPATCHED) { state.refresh() }
        val others = List(5) { launch(start = CoroutineStart.UNDISPATCHED) { state.refresh() } }

        assertEquals(1, loads)
        assertTrue(state.loading)
        response.complete(Loaded(armed))
        first.join()
        others.forEach { it.join() }
        assertEquals(1, loads)
        assertEquals(armed, state.settings)
        assertFalse(state.loading)

        state.refresh()
        assertEquals(2, loads)
    }

    @Test
    fun `pre-save get must finish before patch so it cannot overwrite the patch response`() = runTest {
        val get = CompletableDeferred<FictionNotificationSettingsResult>()
        val patch = CompletableDeferred<FictionNotificationSettingsResult>()
        var loads = 0
        var saves = 0
        val calls = mutableListOf<String>()
        val state = FictionNotificationSettingsState(
            load = {
                loads++
                if (loads == 1) Loaded(armed) else {
                    calls += "get-start"
                    get.await().also { calls += "get-end" }
                }
            },
            update = {
                saves++
                calls += "patch-start"
                patch.await().also { calls += "patch-end" }
            },
        )
        state.refresh()
        val refreshing = launch(start = CoroutineStart.UNDISPATCHED) { state.refresh() }
        val saving = async(start = CoroutineStart.UNDISPATCHED) { state.save(request) }
        val coalesced = launch(start = CoroutineStart.UNDISPATCHED) { state.refresh() }

        assertEquals(listOf("get-start"), calls)
        assertTrue(state.saving)
        assertFalse(state.save(request))
        assertEquals(0, saves)

        get.complete(Loaded(armed.copy(remainingSeconds = 1800.0)))
        refreshing.join()
        patch.complete(Loaded(off))
        assertTrue(saving.await())
        coalesced.join()

        assertEquals(listOf("get-start", "get-end", "patch-start", "patch-end"), calls)
        assertEquals(off, state.settings)
        assertEquals(2, loads)
        assertEquals(1, saves)
        assertFalse(state.loading)
        assertFalse(state.saving)
    }

    @Test
    fun `refresh during patch is coalesced rather than fetching a stale pre-save value`() = runTest {
        val patch = CompletableDeferred<FictionNotificationSettingsResult>()
        var loads = 0
        var current = armed
        val state = FictionNotificationSettingsState(
            load = { loads++; Loaded(current) },
            update = { patch.await() },
        )
        state.refresh()
        val saving = async(start = CoroutineStart.UNDISPATCHED) { state.save(request) }
        val refreshing = launch(start = CoroutineStart.UNDISPATCHED) { state.refresh() }

        assertEquals(1, loads)
        patch.complete(Loaded(off))
        assertTrue(saving.await())
        refreshing.join()
        assertEquals(off, state.settings)
        assertEquals(1, loads)

        current = off.copy(mode = NotificationModeEvery)
        state.refresh()
        assertEquals(current, state.settings)
        assertEquals(2, loads)
    }

    @Test
    fun `duplicate saves are rejected while patch is suspended`() = runTest {
        val patch = CompletableDeferred<FictionNotificationSettingsResult>()
        val requests = mutableListOf<FictionNotificationSettingsRequest>()
        val state = FictionNotificationSettingsState(
            load = { Loaded(armed) },
            update = { requests += it; patch.await() },
        )
        state.refresh()
        val saving = async(start = CoroutineStart.UNDISPATCHED) { state.save(request) }

        assertTrue(state.saving)
        assertFalse(state.save(request))
        assertFalse(state.save(request.copy(mode = NotificationModeEvery)))
        assertEquals(listOf(request), requests)

        patch.complete(Loaded(off))
        assertTrue(saving.await())
        assertFalse(state.saving)
    }

    @Test
    fun `cancelled refresh rethrows cancellation and releases request lock`() = runTest {
        val cancellation = CancellationException("cancelled")
        var cancelled = true
        val state = FictionNotificationSettingsState(
            load = { if (cancelled) throw cancellation else Loaded(armed) },
            update = { Loaded(off) },
        )

        try {
            state.refresh()
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        assertNull(state.settings)
        assertNull(state.error)
        assertFalse(state.loading)

        cancelled = false
        state.refresh()
        assertEquals(armed, state.settings)
    }

    @Test
    fun `cancelled save rethrows and marks uncertain last known settings stale`() = runTest {
        val cancellation = CancellationException("cancelled")
        var cancelled = true
        val state = FictionNotificationSettingsState(
            load = { Loaded(armed) },
            update = { if (cancelled) throw cancellation else Loaded(off) },
        )
        state.refresh()

        try {
            state.save(request)
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        assertEquals(armed, state.settings)
        assertNull(state.error)
        assertTrue(state.stale)
        assertFalse(state.saving)

        cancelled = false
        assertTrue(state.save(request))
        assertEquals(off, state.settings)
        assertFalse(state.stale)
    }

    @Test
    fun `cancelling a queued save clears saving without blocking a subsequent save`() = runTest {
        val get = CompletableDeferred<FictionNotificationSettingsResult>()
        var loads = 0
        var saves = 0
        val state = FictionNotificationSettingsState(
            load = { if (++loads == 1) Loaded(armed) else get.await() },
            update = { saves++; Loaded(off) },
        )
        state.refresh()
        val refreshing = launch(start = CoroutineStart.UNDISPATCHED) { state.refresh() }
        val saving = launch(start = CoroutineStart.UNDISPATCHED) { state.save(request) }
        assertTrue(state.saving)

        saving.cancelAndJoin()
        assertFalse(state.saving)
        assertFalse(state.stale)
        assertEquals(0, saves)
        get.complete(Loaded(armed))
        refreshing.join()

        assertTrue(state.save(request))
        assertEquals(1, saves)
    }

    @Test
    fun `disabled state makes no requests even when directly refreshed or saved`() = runTest {
        val state = FictionNotificationSettingsState(
            load = { fail("Unexpected GET"); Loaded(armed) },
            update = { fail("Unexpected PATCH"); Loaded(off) },
            enabled = false,
        )

        state.refresh()
        assertFalse(state.save(request))
        assertNull(state.settings)
        assertNull(state.error)
        assertFalse(state.loading)
        assertFalse(state.saving)
    }
}
