package dk.perspektiva.ttsroad.data

/** In-memory [SessionStore] so repository tests need no Android context or DataStore. */
internal class FakeSessionStore(@Volatile private var state: SessionState) : SessionStore {
    var clearTokenCalls = 0
        private set

    override suspend fun current(): SessionState = state

    override suspend fun saveLogin(baseUrl: String, response: LoginResponse) {
        state = SessionState(
            serverUrl = normalizeBaseUrl(baseUrl),
            token = response.token,
            username = response.user.username,
            isAdmin = response.user.isAdmin,
            serverName = response.server?.name ?: "TTSRoad",
            deviceId = response.deviceId,
            expiresAt = response.expiresAt,
            userId = response.user.id,
        )
    }

    override suspend fun useAddress(owner: SessionState, address: String, serverIdentity: String): Boolean {
        if (!state.isLoggedIn || state.serverUrl != owner.serverUrl || state.token != owner.token) return false
        val next = normalizeBaseUrl(address)
        state = state.copy(
            serverUrl = next,
            alternateUrls = alternatesAfterSwitch(state.serverUrl, state.alternateUrls, next),
            serverIdentity = serverIdentity,
        )
        return true
    }

    override suspend fun rememberAddress(owner: SessionState, address: String, serverIdentity: String): Boolean {
        if (!state.isLoggedIn || state.serverUrl != owner.serverUrl || state.token != owner.token) return false
        state = state.copy(
            alternateUrls = alternatesAfterRemember(state.serverUrl, state.alternateUrls, normalizeBaseUrl(address)),
            serverIdentity = serverIdentity,
        )
        return true
    }

    override suspend fun forgetAddress(address: String) {
        state = state.copy(alternateUrls = alternatesAfterForget(state.alternateUrls, address))
    }

    /** Swap the live session out from under an in-flight call, as a sign-out or another sign-in would. */
    fun replace(next: SessionState) {
        state = next
    }

    /** [current] for a caller that cannot suspend, such as a MockWebServer dispatcher. */
    fun currentBlocking(): SessionState = state

    override suspend fun clearToken() {
        clearTokenCalls++
        state = state.copy(token = null, username = null, isAdmin = false, deviceId = null, expiresAt = null)
    }
}
