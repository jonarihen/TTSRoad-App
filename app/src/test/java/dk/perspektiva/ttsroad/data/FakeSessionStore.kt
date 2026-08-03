package dk.perspektiva.ttsroad.data

/** In-memory [SessionStore] so repository tests need no Android context or DataStore. */
internal class FakeSessionStore(private var state: SessionState) : SessionStore {
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
        )
    }

    override suspend fun clearToken() {
        clearTokenCalls++
        state = state.copy(token = null, username = null, isAdmin = false, deviceId = null, expiresAt = null)
    }
}
