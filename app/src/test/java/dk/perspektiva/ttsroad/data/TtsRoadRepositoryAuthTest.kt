package dk.perspektiva.ttsroad.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.Connection
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException

class TtsRoadRepositoryAuthTest {
    private lateinit var server: MockWebServer

    private fun loggedInStore() = FakeSessionStore(
        SessionState(serverUrl = server.url("/").toString(), token = "stale-token", username = "admin"),
    )

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    @Test
    fun `401 on an authenticated call clears the token and flags the session as expired`() = runTest {
        val store = loggedInStore()
        val repository = TtsRoadRepository(store)
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"Invalid token"}"""))

        val thrown = runCatching { repository.library() }.exceptionOrNull()

        assertTrue("expected the 401 to propagate", thrown is HttpException)
        assertEquals(401, (thrown as HttpException).code())
        assertEquals(1, store.clearTokenCalls)
        assertNull(store.current().token)
        assertFalse(store.current().isLoggedIn)
        assertNotNull(repository.sessionEnd.value)
    }

    @Test
    fun `a successful authenticated call leaves the session alone`() = runTest {
        val store = loggedInStore()
        val repository = TtsRoadRepository(store)
        server.enqueue(MockResponse().setBody("""{"api_version":1,"fictions":[]}"""))

        assertEquals(0, repository.library().fictions.size)
        assertEquals(0, store.clearTokenCalls)
        assertTrue(store.current().isLoggedIn)
        assertNull(repository.sessionEnd.value)
    }

    @Test
    fun `a non-401 failure keeps the token so the user can retry`() = runTest {
        val store = loggedInStore()
        val repository = TtsRoadRepository(store)
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"detail":"boom"}"""))

        val thrown = runCatching { repository.chapters(fictionId = 7) }.exceptionOrNull()

        assertEquals(500, (thrown as HttpException).code())
        assertEquals(0, store.clearTokenCalls)
        assertTrue(store.current().isLoggedIn)
        assertNull(repository.sessionEnd.value)
    }

    @Test
    fun `401 on a background progress save also expires the session`() = runTest {
        val store = loggedInStore()
        val repository = TtsRoadRepository(store)
        server.enqueue(MockResponse().setResponseCode(401))

        runCatching {
            repository.saveProgress(fictionId = 1, chapterId = 2, positionSeconds = 30.0, isPlayed = false)
        }

        assertEquals(1, store.clearTokenCalls)
        assertNotNull(repository.sessionEnd.value)
    }

    @Test
    fun `login omits even a credential bound to its request`() = runTest {
        val store = loggedInStore()
        val repository = TtsRoadRepository(store)
        server.enqueue(MockResponse().setBody("""{"api_version":1,"fictions":[]}"""))
        repository.library()
        assertEquals("Bearer stale-token", server.takeRequest().getHeader("Authorization"))

        MockWebServer().use { otherServer ->
            otherServer.start()
            otherServer.enqueue(
                MockResponse().setBody(
                    """{"token":"fresh","token_type":"bearer","user":{"id":1,"username":"admin"}}""",
                ),
            )
            val api = TtsRoadRepository::class.java
                .getDeclaredMethod("api", String::class.java, SessionState::class.java)
                .apply { isAccessible = true }
                .invoke(
                    repository,
                    otherServer.url("/").toString(),
                    store.current().copy(serverUrl = otherServer.url("/").toString()),
                ) as TtsRoadApi

            api.login(LoginRequest(username = "admin", password = "correct", deviceName = "Pixel"))

            val request = otherServer.takeRequest()
            assertEquals("/api/mobile/login", request.path)
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader(NoAuthHeader))
        }

        server.enqueue(MockResponse().setBody("""{"api_version":1,"fictions":[]}"""))
        repository.library()
        assertEquals("Bearer stale-token", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a queued request keeps its captured credential while login and discovery run on both servers`() = runTest {
        val store = loggedInStore()
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val repository = TtsRoadRepository(
            store,
            httpClient = clientHolding("/api/mobile/library", blocked, release),
        )
        server.enqueue(MockResponse().setBody("""{"api_version":1,"capabilities":{}}"""))
        server.enqueue(MockResponse().setBody("""{"api_version":1,"fictions":[]}"""))

        MockWebServer().use { otherServer ->
            otherServer.start()
            otherServer.enqueue(MockResponse().setBody("""{"api_version":1,"capabilities":{}}"""))
            otherServer.enqueue(
                MockResponse().setBody(
                    """{"token":"fresh","token_type":"bearer","user":{"id":1,"username":"admin"}}""",
                ),
            )
            otherServer.enqueue(MockResponse().setBody("""{"api_version":1,"fictions":[]}"""))
            val pending = async(Dispatchers.IO) { repository.library() }
            try {
                assertTrue("A request must reach the barrier", blocked.await(5, TimeUnit.SECONDS))
                repository.capabilities(server.url("/").toString())
                repository.capabilities(otherServer.url("/").toString())
                assertEquals(
                    LoginResult.Success,
                    repository.login(otherServer.url("/").toString(), "admin", "correct", "Pixel"),
                )
                repository.library()
                assertRequest(server, "/api/mobile/capabilities", null)
                assertRequest(otherServer, "/api/mobile/capabilities", null)
                assertRequest(otherServer, "/api/mobile/login", null)
                assertRequest(otherServer, "/api/mobile/library?scope=followed", "Bearer fresh")
            } finally {
                release.countDown()
            }
            assertTrue(pending.await().fictions.isEmpty())
            assertRequest(server, "/api/mobile/library?scope=followed", "Bearer stale-token")
            assertEquals(2, server.requestCount)
            assertEquals(3, otherServer.requestCount)
            assertEquals("fresh", store.current().token)
        }
    }

    @Test
    fun `queued login and discovery stay credential free after an authenticated call`() = runTest {
        val blocked = CountDownLatch(2)
        val release = CountDownLatch(1)
        val store = loggedInStore()
        MockWebServer().use { otherServer ->
            otherServer.start()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                if (chain.request().url.port == otherServer.port) {
                    blocked.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                chain.proceed(chain.request())
            }.build()
            val repository = TtsRoadRepository(store, httpClient = client)
            otherServer.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (request.path) {
                        "/api/mobile/login" -> MockResponse().setBody(
                            """{"token":"fresh","user":{"id":1,"username":"admin"}}""",
                        )
                        else -> MockResponse().setBody("""{"api_version":1,"capabilities":{}}""")
                    }
            }
            server.enqueue(MockResponse().setBody("""{"api_version":1,"fictions":[]}"""))
            val login = async(Dispatchers.IO) {
                repository.login(otherServer.url("/").toString(), "admin", "correct", "Pixel")
            }
            val discovery = async(Dispatchers.IO) {
                repository.capabilities(otherServer.url("/").toString())
            }
            try {
                assertTrue("Both public requests must reach the barrier", blocked.await(5, TimeUnit.SECONDS))
                repository.library()
                assertRequest(server, "/api/mobile/library?scope=followed", "Bearer stale-token")
            } finally {
                release.countDown()
            }
            assertEquals(LoginResult.Success, login.await())
            discovery.await()
            val paths = (1..2).map {
                val request = otherServer.takeRequest(5, TimeUnit.SECONDS)
                assertNotNull(request)
                assertNull(request!!.getHeader("Authorization"))
                assertNull(request.getHeader(NoAuthHeader))
                request.path
            }.toSet()
            assertEquals(setOf("/api/mobile/login", "/api/mobile/capabilities"), paths)
        }
    }

    @Test
    fun `queued logout retains the old servers credential`() = runTest {
        val store = loggedInStore()
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val repository = TtsRoadRepository(
            store,
            httpClient = clientHolding("/api/mobile/logout", blocked, release),
        )
        server.enqueue(MockResponse().setBody("""{"status":"ok","revoked":true}"""))
        MockWebServer().use { otherServer ->
            otherServer.start()
            otherServer.enqueue(MockResponse().setBody("""{"api_version":1,"fictions":[]}"""))
            val logout = async(Dispatchers.IO) { repository.logout() }
            try {
                assertTrue(blocked.await(5, TimeUnit.SECONDS))
                store.saveLogin(
                    otherServer.url("/").toString(),
                    LoginResponse(token = "fresh", user = MobileUser(id = 1, username = "admin")),
                )
                repository.library()
                assertRequest(otherServer, "/api/mobile/library?scope=followed", "Bearer fresh")
            } finally {
                release.countDown()
            }
            logout.await()
            assertRequest(server, "/api/mobile/logout", "Bearer stale-token")
        }
    }

    @Test
    fun `an old servers 401 cannot clear another servers session with the same token value`() = runTest {
        val store = loggedInStore()
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val repository = TtsRoadRepository(
            store,
            httpClient = clientHolding("/api/mobile/library", blocked, release),
        )
        server.enqueue(MockResponse().setResponseCode(401))
        MockWebServer().use { otherServer ->
            otherServer.start()
            val pending = async(Dispatchers.IO) { runCatching { repository.library() }.exceptionOrNull() }
            try {
                assertTrue(blocked.await(5, TimeUnit.SECONDS))
                store.saveLogin(
                    otherServer.url("/").toString(),
                    LoginResponse(token = "stale-token", user = MobileUser(id = 1, username = "admin")),
                )
            } finally {
                release.countDown()
            }
            assertEquals(401, (pending.await() as HttpException).code())
            assertRequest(server, "/api/mobile/library?scope=followed", "Bearer stale-token")
            assertEquals(otherServer.url("/").toString(), store.current().serverUrl)
            assertTrue(store.current().isLoggedIn)
            assertEquals(0, store.clearTokenCalls)
            assertNull(repository.sessionEnd.value)
        }
    }

    @Test
    fun `a credential never follows a cross origin redirect but is kept on same origin redirects`() = runTest {
        val repository = TtsRoadRepository(loggedInStore())
        MockWebServer().use { otherServer ->
            otherServer.start()
            server.enqueue(
                MockResponse().setResponseCode(302).addHeader("Location", server.url("/same-origin")),
            )
            server.enqueue(
                MockResponse().setResponseCode(302).addHeader("Location", otherServer.url("/redirected")),
            )
            otherServer.enqueue(MockResponse().setBody("""{"api_version":1,"fictions":[]}"""))

            repository.library()

            assertRequest(server, "/api/mobile/library?scope=followed", "Bearer stale-token")
            assertRequest(server, "/same-origin", "Bearer stale-token")
            assertRequest(otherServer, "/redirected", null)
        }
    }

    @Test
    fun `a request rewritten to another origin before authentication has no credential`() = runTest {
        MockWebServer().use { otherServer ->
            otherServer.start()
            otherServer.enqueue(MockResponse().setBody("""{"api_version":1,"fictions":[]}"""))
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(otherServer.url("/rewritten")).build())
            }.build()
            val repository = TtsRoadRepository(loggedInStore(), httpClient = client)

            repository.library()

            assertRequest(otherServer, "/rewritten", null)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `request scoped APIs reuse the injected clients connection pool`() = runTest {
        val connections = mutableListOf<Connection?>()
        val client = OkHttpClient.Builder().addNetworkInterceptor { chain ->
            connections.add(chain.connection())
            chain.proceed(chain.request())
        }.build()
        val repository = TtsRoadRepository(loggedInStore(), httpClient = client)
        repeat(2) { server.enqueue(MockResponse().setBody("""{"api_version":1,"fictions":[]}""")) }

        repeat(2) { repository.library() }

        assertEquals(2, connections.size)
        assertNotNull(connections.first())
        assertSame(connections.first(), connections.last())
        assertEquals(1, client.connectionPool.connectionCount())
        repeat(2) { assertRequest(server, "/api/mobile/library?scope=followed", "Bearer stale-token") }
    }

    @Test
    fun `progress saves and equivalent normalized sessions reuse one authenticated service`() = runTest {
        val store = loggedInStore()
        val repository = TtsRoadRepository(store)
        val session = store.current()
        val service = cachedApi(repository, session.serverUrl, session)
        repeat(3) {
            server.enqueue(MockResponse().setBody("""{"status":"saved","chapter_id":2}"""))
            repository.saveProgress(1, 2, it.toDouble(), false)
            assertSame(service, cachedApi(repository, session.serverUrl, session))
            assertRequest(server, "/api/mobile/playback/progress", "Bearer stale-token")
        }
        val equivalent = session.copy(
            serverUrl = " ${session.serverUrl.trimEnd('/')} ",
            username = "renamed",
            serverName = "Renamed server",
        )
        assertSame(service, cachedApi(repository, equivalent.serverUrl, equivalent))
    }

    @Test
    fun `credential rotation replaces only the authenticated service and keeps old requests immutable`() = runTest {
        val store = loggedInStore()
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.encodedPath == "/api/mobile/library" && blocked.count > 0) {
                blocked.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            chain.proceed(chain.request())
        }.build()
        val repository = TtsRoadRepository(store, httpClient = client)
        val original = store.current()
        val oldService = cachedApi(repository, original.serverUrl, original)
        val publicService = cachedApi(repository, original.serverUrl)
        repeat(3) { server.enqueue(MockResponse().setBody("""{"api_version":1,"fictions":[]}""")) }
        val pending = async(Dispatchers.IO) { repository.library() }
        lateinit var replacement: TtsRoadApi
        try {
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            store.saveLogin(
                original.serverUrl,
                LoginResponse(token = "fresh", user = MobileUser(id = 1, username = "admin")),
            )
            val rotated = store.current()
            replacement = cachedApi(repository, rotated.serverUrl, rotated)
            assertNotSame(oldService, replacement)
            assertSame(replacement, cachedApi(repository, rotated.serverUrl, rotated))
            assertSame(publicService, cachedApi(repository, rotated.serverUrl))
            repository.library()
            assertRequest(server, "/api/mobile/library?scope=followed", "Bearer fresh")
        } finally {
            release.countDown()
        }
        pending.await()
        assertRequest(server, "/api/mobile/library?scope=followed", "Bearer stale-token")
        oldService.library()
        assertRequest(server, "/api/mobile/library?scope=followed", "Bearer stale-token")
        assertSame(replacement, cachedApi(repository, original.serverUrl, store.current()))
    }

    @Test
    fun `repeated credential changes replace rather than retain previous services`() = runTest {
        val store = loggedInStore()
        val repository = TtsRoadRepository(store)
        val session = store.current()
        var previous = cachedApi(repository, session.serverUrl, session)
        repeat(20) { index ->
            val rotated = session.copy(token = "rotated-$index")
            val replacement = cachedApi(repository, rotated.serverUrl, rotated)
            assertNotSame(previous, replacement)
            assertSame(replacement, cachedApi(repository, rotated.serverUrl, rotated))
            previous = replacement
        }
        val returned = cachedApi(repository, session.serverUrl, session)
        assertNotSame(previous, returned)
        val replacedAgain = cachedApi(repository, session.serverUrl, session.copy(token = "rotated-19"))
        assertNotSame(previous, replacedAgain)
        assertNotSame(returned, replacedAgain)
    }

    @Test
    fun `service cache is bounded by recently used servers without changing evicted services`() = runTest {
        val store = loggedInStore()
        val repository = TtsRoadRepository(store)
        val session = store.current()
        val original = cachedApi(repository, session.serverUrl, session)
        val publicOriginal = cachedApi(repository, session.serverUrl)
        val urls = (1..4).map { server.url("/server-$it/").toString() }
        val services = urls.take(3).map { url ->
            cachedApi(repository, url, session.copy(serverUrl = url))
        }
        assertSame(original, cachedApi(repository, session.serverUrl, session))
        cachedApi(repository, urls.last(), session.copy(serverUrl = urls.last()))
        assertSame(original, cachedApi(repository, session.serverUrl, session))
        assertSame(publicOriginal, cachedApi(repository, session.serverUrl))
        assertNotSame(services.first(), cachedApi(repository, urls.first(), session.copy(serverUrl = urls.first())))
        val publicReplacements = (5..9).map { server.url("/server-$it/").toString() }
        publicReplacements.forEach { cachedApi(repository, it) }
        assertNotSame(original, cachedApi(repository, session.serverUrl, session))
        assertNotSame(publicOriginal, cachedApi(repository, session.serverUrl))
        server.enqueue(MockResponse().setBody("""{"api_version":1,"fictions":[]}"""))

        original.library()

        assertRequest(server, "/api/mobile/library?scope=followed", "Bearer stale-token")
        val cache = TtsRoadRepository::class.java.getDeclaredField("apiCache")
            .apply { isAccessible = true }.get(repository) as Map<*, *>
        assertEquals(4, cache.size)
    }

    @Test
    fun `the same credential is isolated by intended origin and public services remain credential free`() = runTest {
        val store = loggedInStore()
        val repository = TtsRoadRepository(store)
        val session = store.current()
        MockWebServer().use { otherServer ->
            otherServer.start()
            val otherSession = session.copy(serverUrl = otherServer.url("/").toString())
            val original = cachedApi(repository, session.serverUrl, session)
            val mismatched = cachedApi(repository, session.serverUrl, otherSession)
            assertNotSame(original, mismatched)
            assertSame(mismatched, cachedApi(repository, session.serverUrl, otherSession))
            val otherService = cachedApi(repository, otherSession.serverUrl, otherSession)
            assertNotSame(mismatched, otherService)
            val publicService = cachedApi(repository, otherSession.serverUrl)
            assertSame(publicService, cachedApi(repository, otherSession.serverUrl))
            server.enqueue(MockResponse().setBody("""{"api_version":1,"fictions":[]}"""))
            otherServer.enqueue(MockResponse().setBody("""{"api_version":1,"fictions":[]}"""))
            otherServer.enqueue(MockResponse().setBody("""{"api_version":1,"capabilities":{}}"""))

            mismatched.library()
            otherService.library()
            publicService.capabilities()

            assertRequest(server, "/api/mobile/library?scope=followed", null)
            assertRequest(otherServer, "/api/mobile/library?scope=followed", "Bearer stale-token")
            assertRequest(otherServer, "/api/mobile/capabilities", null)
        }
    }

    @Test
    fun `concurrent service lookups share the same immutable session service`() = runTest {
        val store = loggedInStore()
        val repository = TtsRoadRepository(store)
        val session = store.current()
        val ready = CountDownLatch(8)
        val release = CountDownLatch(1)
        val lookups = List(8) {
            async(Dispatchers.IO) {
                ready.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                cachedApi(repository, session.serverUrl, session)
            }
        }
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
        }
        val first = lookups.first().await()
        lookups.forEach { assertSame(first, it.await()) }
    }

    private fun cachedApi(
        repository: TtsRoadRepository,
        baseUrl: String,
        session: SessionState? = null,
    ): TtsRoadApi = TtsRoadRepository::class.java
        .getDeclaredMethod("api", String::class.java, SessionState::class.java)
        .apply { isAccessible = true }
        .invoke(repository, baseUrl, session) as TtsRoadApi

    private fun clientHolding(
        path: String,
        blocked: CountDownLatch,
        release: CountDownLatch,
    ): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        if (chain.request().url.port == server.port && chain.request().url.encodedPath == path) {
            blocked.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }
        chain.proceed(chain.request())
    }.build()

    private fun assertRequest(server: MockWebServer, path: String, authorization: String?) {
        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("Expected $path", request)
        assertEquals(path, request!!.path)
        assertEquals(authorization, request.getHeader("Authorization"))
        assertNull(request.getHeader(NoAuthHeader))
        assertNull(request.getHeader(SlowUploadHeader))
    }

    @Test
    fun `a wrong password still reports a plain failure`() = runTest {
        val store = FakeSessionStore(SessionState())
        val repository = TtsRoadRepository(store)
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"detail":"Incorrect username or password"}"""),
        )

        val result = repository.login(
            baseUrl = server.url("/").toString(),
            username = "admin",
            password = "wrong",
            deviceName = "Pixel",
        )

        assertEquals(LoginResult.Failure("Incorrect username or password"), result)
        assertEquals(0, store.clearTokenCalls)
        assertNull(repository.sessionEnd.value)
    }

    @Test
    fun `a totp_required login is not treated as an expired session`() = runTest {
        val store = FakeSessionStore(SessionState())
        val repository = TtsRoadRepository(store)
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"detail":{"code":"totp_required"}}"""),
        )

        val result = repository.login(
            baseUrl = server.url("/").toString(),
            username = "admin",
            password = "correct",
            deviceName = "Pixel",
        )

        assertEquals(LoginResult.TotpRequired, result)
        assertEquals(0, store.clearTokenCalls)
        assertNull(repository.sessionEnd.value)
    }

    @Test
    fun `signing in again clears the expired flag`() = runTest {
        val store = loggedInStore()
        val repository = TtsRoadRepository(store)
        server.enqueue(MockResponse().setResponseCode(401))
        runCatching { repository.library() }
        assertNotNull(repository.sessionEnd.value)

        server.enqueue(
            MockResponse().setBody(
                """{"token":"fresh","token_type":"bearer","user":{"id":1,"username":"admin"}}""",
            ),
        )
        val result = repository.login(
            baseUrl = server.url("/").toString(),
            username = "admin",
            password = "correct",
            deviceName = "Pixel",
        )

        assertEquals(LoginResult.Success, result)
        assertNull(repository.sessionEnd.value)
        assertEquals("fresh", store.current().token)
    }

    @Test
    fun `logout posts the signed request and drops the bearer header`() = runTest {
        val store = loggedInStore()
        val repository = TtsRoadRepository(store)
        server.enqueue(MockResponse().setBody("""{"status":"ok","revoked":true}"""))

        repository.logout()

        assertEquals("Bearer stale-token", server.takeRequest().getHeader("Authorization"))
        assertEquals(1, store.clearTokenCalls)
        assertFalse(store.current().isLoggedIn)

        server.enqueue(MockResponse().setBody("""{"api_version":1,"capabilities":{}}"""))
        repository.capabilities(server.url("/").toString())
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `the recovered session sends the new token`() = runTest {
        val store = loggedInStore()
        val repository = TtsRoadRepository(store)
        server.enqueue(MockResponse().setResponseCode(401))
        runCatching { repository.library() }

        server.enqueue(
            MockResponse().setBody(
                """{"token":"fresh","token_type":"bearer","user":{"id":1,"username":"admin"}}""",
            ),
        )
        repository.login(
            baseUrl = server.url("/").toString(),
            username = "admin",
            password = "correct",
            deviceName = "Pixel",
        )
        server.enqueue(MockResponse().setBody("""{"api_version":1,"fictions":[]}"""))
        repository.library()

        server.takeRequest() // the 401'd library call
        server.takeRequest() // login
        val recovered = server.takeRequest()
        assertNotNull(recovered.getHeader("Authorization"))
        assertEquals("Bearer fresh", recovered.getHeader("Authorization"))
    }
}
