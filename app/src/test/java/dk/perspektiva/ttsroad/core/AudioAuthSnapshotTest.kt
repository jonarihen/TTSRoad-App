package dk.perspektiva.ttsroad.core

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dk.perspektiva.ttsroad.data.AccountActionResult
import dk.perspektiva.ttsroad.data.FakeSessionStore
import dk.perspektiva.ttsroad.data.LoginResult
import dk.perspektiva.ttsroad.data.SessionEndReason
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.media.endAudioSession
import dk.perspektiva.ttsroad.media.handleAudioRejection
import dk.perspektiva.ttsroad.media.recoverAudioPlaybackIfCurrent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioAuthSnapshotTest {
    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun loggedInStore() = FakeSessionStore(
        SessionState(serverUrl = server.url("/").toString(), token = "stale-token", username = "admin"),
    )

    private fun request(url: String) = DataSpec.Builder()
        .setUri(url)
        .setHttpRequestHeaders(mapOf("authorization" to "stale", "AUTHORIZATION" to "older", "Range" to "bytes=1-"))
        .build()

    @Test
    fun `captured session cannot mix new token with old origin`() {
        var published = AudioAuthSnapshot("https://old.example", "Bearer old")
        val captured = published
        published = AudioAuthSnapshot("https://new.example", "Bearer new")
        val oldRequest = captured.resolve(request("https://old.example/audio"))
        assertEquals("Bearer old", oldRequest.httpRequestHeaders["Authorization"])
        assertSame(captured, oldRequest.customData)
        assertFalse(published.resolve(request("https://old.example/audio")).httpRequestHeaders.keys.any { it.equals("Authorization", true) })
        assertEquals("Bearer new", published.resolve(request("https://new.example/audio")).httpRequestHeaders["Authorization"])
    }

    @Test
    fun `signed out and foreign requests lose every preexisting authorization header`() {
        for (snapshot in listOf(AudioAuthSnapshot(), AudioAuthSnapshot("https://home.example", "Bearer current"))) {
            val resolved = snapshot.resolve(request("https://foreign.example/audio"))
            assertEquals(mapOf("Range" to "bytes=1-"), resolved.httpRequestHeaders)
            assertNull(resolved.customData)
        }
    }

    @Test
    fun `matching origin replaces stale authorization case insensitively`() {
        val headers = AudioAuthSnapshot("https://home.example", "Bearer current")
            .resolve(request("https://home.example/audio")).httpRequestHeaders
        assertEquals(mapOf("Range" to "bytes=1-", "Authorization" to "Bearer current"), headers)
    }

    private suspend fun delayedFailure(
        snapshot: () -> AudioAuthSnapshot,
        responseCode: Int = 401,
        whilePending: suspend (RecordedRequest) -> Unit = {},
    ): HttpDataSource.InvalidResponseCodeException = coroutineScope {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val previous = server.dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/audio/chapter.mp3") {
                    started.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    return MockResponse().setResponseCode(responseCode).setBody(
                        """{"detail":{"message":"Audio said no.","reason":"token_revoked"}}""",
                    )
                }
                return MockResponse().setBody(
                    """{"token":"fresh-token","token_type":"bearer","user":{"id":1,"username":"admin"}}""",
                )
            }
        }
        val source = ResolvingDataSource.Factory(
            DefaultHttpDataSource.Factory(),
            object : ResolvingDataSource.Resolver {
                override fun resolveDataSpec(dataSpec: DataSpec) = snapshot().resolve(dataSpec)
            },
        ).createDataSource()
        val result = async(Dispatchers.IO) {
            try {
                runCatching { source.open(DataSpec.Builder().setUri(server.url("/audio/chapter.mp3").toString()).build()) }
                    .exceptionOrNull() as? HttpDataSource.InvalidResponseCodeException
                    ?: error("Expected an audio HTTP rejection")
            } finally {
                source.close()
            }
        }
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS))
            val request = requireNotNull(server.takeRequest(10, TimeUnit.SECONDS))
            whilePending(request)
            release.countDown()
            result.await()
        } finally {
            release.countDown()
            server.dispatcher = previous
        }
    }

    @Test
    fun `delayed active stream rejection ends the session and exposes its reason`() = runTest {
        val store = loggedInStore()
        var cleared = 0
        val repository = TtsRoadRepository(store, onSessionCleared = { cleared++ })
        val session = store.current()
        val snapshot = AudioAuthSnapshot(session.serverUrl, session.authorizationHeader)
        val failure = delayedFailure({ snapshot }) { request ->
            assertEquals("Bearer stale-token", request.getHeader("Authorization"))
        }

        assertSame(snapshot, failure.dataSpec.customData)
        assertTrue(endAudioSession(repository, failure))
        assertFalse(store.current().isLoggedIn)
        assertEquals(1, store.clearTokenCalls)
        assertEquals(1, cleared)
        assertEquals(SessionEndReason.Revoked, repository.sessionEnd.value?.reason)
        assertEquals("Audio said no.", repository.sessionEnd.value?.message)
        assertFalse(endAudioSession(repository, failure))
        assertEquals(1, cleared)
    }

    @Test
    fun `an active stream rejection without a structured body still ends the session`() = runTest {
        val store = loggedInStore()
        val repository = TtsRoadRepository(store)
        val session = store.current()
        val failure = delayedFailure({ AudioAuthSnapshot(session.serverUrl, session.authorizationHeader) })
        val empty = HttpDataSource.InvalidResponseCodeException(
            401, failure.responseMessage, null, failure.headerFields, failure.dataSpec, byteArrayOf(),
        )

        assertTrue(endAudioSession(repository, empty))
        assertFalse(store.current().isLoggedIn)
        assertEquals(SessionEndReason.Unknown, repository.sessionEnd.value?.reason)
        assertTrue(repository.sessionEnd.value?.message?.isNotBlank() == true)
    }

    @Test
    fun `an active stream forbidden response keeps the existing forced signout behavior`() = runTest {
        val store = loggedInStore()
        val repository = TtsRoadRepository(store)
        val session = store.current()
        val failure = delayedFailure({ AudioAuthSnapshot(session.serverUrl, session.authorizationHeader) })
        val forbidden = HttpDataSource.InvalidResponseCodeException(
            403, failure.responseMessage, null, failure.headerFields, failure.dataSpec, failure.responseBody,
        )

        assertTrue(endAudioSession(repository, forbidden))
        assertFalse(store.current().isLoggedIn)
        assertEquals(1, store.clearTokenCalls)
    }

    @Test
    fun `delayed old stream rejection cannot end the rotated token session`() = runTest {
        val store = loggedInStore()
        var cleared = 0
        val repository = TtsRoadRepository(store, onSessionCleared = { cleared++ })
        server.enqueue(MockResponse().setBody("""{"capabilities":{"account_security":true},"limits":{}}"""))
        repository.refreshCurrentCapabilities()
        server.takeRequest()
        val session = store.current()
        var snapshot = AudioAuthSnapshot(session.serverUrl, session.authorizationHeader)
        val failure = delayedFailure({ snapshot }) { request ->
            assertEquals("Bearer stale-token", request.getHeader("Authorization"))
            assertTrue(repository.changePassword("old password", "new password") is AccountActionResult.Done)
            val fresh = store.current()
            snapshot = AudioAuthSnapshot(fresh.serverUrl, fresh.authorizationHeader)
        }

        assertEquals("Bearer stale-token", (failure.dataSpec.customData as AudioAuthSnapshot).authorizationHeader)
        assertFalse(endAudioSession(repository, failure))
        var scheduled = 0
        handleAudioRejection(repository, failure, store::current) { rejected, current ->
            assertEquals("Bearer stale-token", rejected.authorizationHeader)
            assertEquals(snapshot, current)
            scheduled++
        }
        assertEquals(1, scheduled)
        assertEquals("fresh-token", store.current().token)
        assertEquals("Bearer fresh-token", repository.authHeader)
        assertEquals(0, store.clearTokenCalls)
        assertEquals(0, cleared)
        assertNull(repository.sessionEnd.value)
    }

    @Test
    fun `delayed old stream rejection cannot end a newer login on the same server`() = runTest {
        val store = loggedInStore()
        var cleared = 0
        val repository = TtsRoadRepository(store, onSessionCleared = { cleared++ })
        val session = store.current()
        var snapshot = AudioAuthSnapshot(session.serverUrl, session.authorizationHeader)
        val failure = delayedFailure({ snapshot }) {
            assertEquals(LoginResult.Success, repository.login(session.serverUrl, "admin", "password", "Phone"))
            val fresh = store.current()
            snapshot = AudioAuthSnapshot(fresh.serverUrl, fresh.authorizationHeader)
        }

        assertFalse(endAudioSession(repository, failure))
        assertEquals("fresh-token", store.current().token)
        assertEquals(0, store.clearTokenCalls)
        assertEquals(0, cleared)
        assertNull(repository.sessionEnd.value)
    }

    @Test
    fun `delayed old server rejection cannot end a new server session with the same token`() = runTest {
        val replacement = MockWebServer()
        replacement.start()
        try {
            val store = loggedInStore()
            var cleared = 0
            val repository = TtsRoadRepository(store, onSessionCleared = { cleared++ })
            val session = store.current()
            var snapshot = AudioAuthSnapshot(session.serverUrl, session.authorizationHeader)
            val failure = delayedFailure({ snapshot }) { request ->
                assertEquals("Bearer stale-token", request.getHeader("Authorization"))
                replacement.enqueue(MockResponse().setBody(
                    """{"token":"stale-token","token_type":"bearer","user":{"id":1,"username":"admin"}}""",
                ))
                assertEquals(LoginResult.Success, repository.login(
                    replacement.url("/").toString(), "admin", "password", "Phone",
                ))
                val fresh = store.current()
                snapshot = AudioAuthSnapshot(fresh.serverUrl, fresh.authorizationHeader)
            }

            assertFalse(endAudioSession(repository, failure))
            assertEquals(replacement.url("/").toString(), store.current().serverUrl)
            assertEquals("stale-token", store.current().token)
            assertEquals(0, store.clearTokenCalls)
            assertEquals(0, cleared)
            assertNull(repository.sessionEnd.value)
        } finally {
            replacement.shutdown()
        }
    }

    private class RecoveryPlayer(items: List<MediaItem>, playing: Boolean = true) : SimpleBasePlayer(Looper.getMainLooper()) {
        var items = items
        var index = 1
        var position = 12_345L
        var playing = playing
        var prepares = 0
        var replacements = 0
        private var failed = true

        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(items.map { MediaItemData.Builder(it.mediaId).setMediaItem(it).setDurationUs(60_000_000).build() })
            .setCurrentMediaItemIndex(index)
            .setContentPositionMs(position)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (failed) Player.STATE_IDLE else Player.STATE_READY)
            .setPlayerError(if (failed) PlaybackException("Rejected", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) else null)
            .build()

        override fun handleSetMediaItems(
            mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long,
        ): ListenableFuture<*> {
            items = mediaItems
            index = startIndex
            position = startPositionMs
            replacements++
            return Futures.immediateVoidFuture()
        }

        override fun handlePrepare(): ListenableFuture<*> {
            prepares++
            failed = false
            return Futures.immediateVoidFuture()
        }

        override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
    }

    private fun recoveryItems(): List<MediaItem> = listOf("first", "chapter", "foreign").map { name ->
        val url = if (name == "foreign") "https://foreign.example/audio/$name.mp3" else server.url("/audio/$name.mp3").toString()
        MediaItem.Builder().setMediaId(name).setUri(url)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(android.net.Uri.parse(url)).build())
            .build()
    }

    private suspend fun retryRequest(url: String, snapshot: AudioAuthSnapshot, target: MockWebServer) {
        target.enqueue(MockResponse().setBody("audio"))
        val source = ResolvingDataSource.Factory(
            DefaultHttpDataSource.Factory(),
            object : ResolvingDataSource.Resolver {
                override fun resolveDataSpec(dataSpec: DataSpec) = snapshot.resolve(dataSpec)
            },
        ).createDataSource()
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                assertEquals(5L, source.open(DataSpec.Builder().setUri(url).build()))
            } finally {
                source.close()
            }
        }
        val request = requireNotNull(target.takeRequest(10, TimeUnit.SECONDS))
        assertEquals("/audio/chapter.mp3", request.path)
        assertEquals(snapshot.authorizationHeader, request.getHeader("Authorization"))
    }

    @Test
    fun `delayed stale 401 and 403 schedule recovery with the latest token`() = runTest {
        for (code in listOf(401, 403)) {
            val store = loggedInStore()
            val repository = TtsRoadRepository(store)
            val session = store.current()
            var snapshot = AudioAuthSnapshot(session.serverUrl, session.authorizationHeader)
            val failure = delayedFailure({ snapshot }, responseCode = code) {
                assertEquals(LoginResult.Success, repository.login(session.serverUrl, "admin", "password", "Phone"))
                val fresh = store.current()
                snapshot = AudioAuthSnapshot(fresh.serverUrl, fresh.authorizationHeader)
            }
            server.takeRequest()
            val player = RecoveryPlayer(recoveryItems(), playing = code == 401)
            try {
                val error = requireNotNull(player.playerError)
                var scheduled: (suspend () -> Unit)? = null
                var published: AudioAuthSnapshot? = null
                handleAudioRejection(repository, failure, store::current) { rejected, current ->
                    assertEquals(snapshot, current)
                    scheduled = {
                        recoverAudioPlaybackIfCurrent(player, error, rejected, current, store::current) { published = it }
                    }
                }

                assertTrue(scheduled != null)
                assertEquals(0, player.prepares)
                requireNotNull(scheduled).invoke()
                assertEquals(1, player.prepares)
                assertEquals(0, player.replacements)
                assertEquals(snapshot, published)
                assertNull(player.playerError)
                assertEquals(code == 401, player.playWhenReady)
                assertEquals(12_345L, player.currentPosition)
                retryRequest(player.currentMediaItem!!.localConfiguration!!.uri.toString(), snapshot, server)
                assertEquals("fresh-token", store.current().token)
                assertNull(repository.sessionEnd.value)
            } finally {
                player.release()
            }
        }
    }

    @Test
    fun `delayed old server rejection recovers on the new origin without leaking its token`() = runTest {
        val replacement = MockWebServer()
        replacement.start()
        try {
            val store = loggedInStore()
            val repository = TtsRoadRepository(store)
            val session = store.current()
            var snapshot = AudioAuthSnapshot(session.serverUrl, session.authorizationHeader)
            val failure = delayedFailure({ snapshot }, responseCode = 403) {
                replacement.enqueue(MockResponse().setBody(
                    """{"token":"new-server-token","token_type":"bearer","user":{"id":1,"username":"admin"}}""",
                ))
                assertEquals(LoginResult.Success, repository.login(replacement.url("/").toString(), "admin", "password", "Phone"))
                val fresh = store.current()
                snapshot = AudioAuthSnapshot(fresh.serverUrl, fresh.authorizationHeader)
            }
            replacement.takeRequest()
            val player = RecoveryPlayer(recoveryItems())
            try {
                val error = requireNotNull(player.playerError)
                var scheduled: (suspend () -> Unit)? = null
                var published: AudioAuthSnapshot? = null
                handleAudioRejection(repository, failure, store::current) { rejected, current ->
                    scheduled = {
                        recoverAudioPlaybackIfCurrent(player, error, rejected, current, store::current) { published = it }
                    }
                }
                assertTrue(scheduled != null)
                requireNotNull(scheduled).invoke()

                assertEquals(1, player.prepares)
                assertEquals(1, player.replacements)
                assertEquals(snapshot, published)
                assertEquals(1, player.currentMediaItemIndex)
                assertEquals(12_345L, player.currentPosition)
                assertTrue(player.playWhenReady)
                val item = player.currentMediaItem!!
                assertEquals(replacement.url("/audio/chapter.mp3").toString(), item.localConfiguration!!.uri.toString())
                assertEquals(item.localConfiguration!!.uri, item.requestMetadata.mediaUri)
                assertEquals("https://foreign.example/audio/foreign.mp3", player.getMediaItemAt(2).localConfiguration!!.uri.toString())
                assertFalse(snapshot.resolve(failure.dataSpec).httpRequestHeaders.containsKey("Authorization"))
                retryRequest(item.localConfiguration!!.uri.toString(), snapshot, replacement)
                assertEquals(1, server.requestCount)
                assertEquals("new-server-token", store.current().token)
                assertNull(repository.sessionEnd.value)
            } finally {
                player.release()
            }
        } finally {
            replacement.shutdown()
        }
    }

    @Test
    fun `scheduled recovery is discarded after another session or player error change`() = runTest {
        val expected = AudioAuthSnapshot(server.url("/").toString(), "Bearer fresh-token")
        val rejected = expected.copy(authorizationHeader = "Bearer stale-token")
        val active = SessionState(serverUrl = expected.serverUrl, token = "fresh-token")
        for (session in listOf(active.copy(token = null), active.copy(token = "newer-token"), active.copy(serverUrl = "https://other.example/"), active)) {
            val player = RecoveryPlayer(recoveryItems())
            try {
                val error = if (session == active) PlaybackException("New error", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
                    else requireNotNull(player.playerError)
                var published = 0
                recoverAudioPlaybackIfCurrent(player, error, rejected, expected, { session }) { published++ }

                assertEquals(0, published)
                assertEquals(0, player.prepares)
                assertEquals(0, player.replacements)
            } finally {
                player.release()
            }
        }
    }

    @Test
    fun `active 401 and 403 end the session with a reason and never schedule recovery`() = runTest {
        for (code in listOf(401, 403)) {
            val store = loggedInStore()
            val repository = TtsRoadRepository(store)
            val session = store.current()
            val failure = delayedFailure({ AudioAuthSnapshot(session.serverUrl, session.authorizationHeader) }, responseCode = code)
            var scheduled = 0

            handleAudioRejection(repository, failure, store::current) { _, _ -> scheduled++ }

            assertEquals(0, scheduled)
            assertFalse(store.current().isLoggedIn)
            assertEquals(1, store.clearTokenCalls)
            assertEquals(SessionEndReason.Revoked, repository.sessionEnd.value?.reason)
            assertEquals("Audio said no.", repository.sessionEnd.value?.message)
        }
    }

    @Test
    fun `signed out or unauthenticated stream rejections never schedule recovery`() = runTest {
        for (authenticated in listOf(true, false)) {
            val store = loggedInStore()
            val repository = TtsRoadRepository(store)
            val session = store.current()
            val snapshot = if (authenticated) AudioAuthSnapshot(session.serverUrl, session.authorizationHeader)
                else AudioAuthSnapshot("https://foreign.example", "Bearer foreign")
            val failure = delayedFailure({ snapshot })
            if (authenticated) store.clearToken()
            var scheduled = 0

            handleAudioRejection(repository, failure, store::current) { _, _ -> scheduled++ }

            assertEquals(0, scheduled)
            assertNull(repository.sessionEnd.value)
        }
    }

    @Test
    fun `a stream rejection without matching origin guarded credentials cannot end a session`() = runTest {
        val store = loggedInStore()
        val repository = TtsRoadRepository(store)
        val foreign = AudioAuthSnapshot("http://foreign.example/", "Bearer stale-token")
        val failure = delayedFailure({ foreign }) { request ->
            assertNull(request.getHeader("Authorization"))
        }

        assertNull(failure.dataSpec.customData)
        assertFalse(endAudioSession(repository, failure))
        assertTrue(store.current().isLoggedIn)
        assertEquals(0, store.clearTokenCalls)
        assertNull(repository.sessionEnd.value)
    }
}
