package dk.perspektiva.ttsroad

import androidx.activity.ComponentActivity
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dk.perspektiva.ttsroad.data.AccountActionResult
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.data.TwoFactorCodes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal val LocalRecoveryCodesOwner = staticCompositionLocalOf<RecoveryCodesOwner> {
    error("Recovery codes require an activity owner")
}

internal fun ComponentActivity.recoveryCodesOwner(
    repository: TtsRoadRepository,
    sessions: Flow<SessionState>,
    currentSession: suspend () -> SessionState,
): RecoveryCodesOwner = ViewModelProvider(this, object : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(
        RecoveryCodesOwner(repository, sessions, currentSession),
    )!!
})[RecoveryCodesOwner::class.java]

internal class RecoveryCodesState(
    val codes: List<String> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
)

internal class RecoveryCodesOwner(
    private val repository: TtsRoadRepository,
    sessions: Flow<SessionState>,
    private val currentSession: suspend () -> SessionState,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow(RecoveryCodesState())
    val state = mutableState.asStateFlow()
    private var session: SessionState? = null
    private var generation = 0L
    private var operation: Job? = null

    init {
        scope.launch { sessions.collect { updateSession(it) } }
    }

    fun owns(current: SessionState): Boolean = current.isLoggedIn && sameSession(session, current)

    fun enable(code: String) = issue { repository.enableTwoFactor(code) }

    fun reissue() = issue { repository.reissueRecoveryCodes() }

    fun acknowledge() {
        if (!state.value.busy) mutableState.value = RecoveryCodesState()
    }

    private fun updateSession(current: SessionState) {
        if (sameSession(session, current)) return
        generation++
        operation?.cancel()
        operation = null
        session = current
        mutableState.value = RecoveryCodesState()
    }

    private fun issue(request: suspend () -> AccountActionResult<TwoFactorCodes>) {
        val current = session?.takeIf { it.isLoggedIn } ?: return
        if (state.value.busy || state.value.codes.isNotEmpty()) return
        val startedGeneration = generation
        mutableState.value = RecoveryCodesState(busy = true)
        operation = scope.launch {
            try {
                val liveSession = currentSession()
                if (!sameSession(current, liveSession)) {
                    updateSession(liveSession)
                    return@launch
                }
                val result = request()
                val finishedSession = currentSession()
                if (generation != startedGeneration) return@launch
                if (!sameSession(current, finishedSession)) {
                    updateSession(finishedSession)
                    return@launch
                }
                mutableState.value = when (result) {
                    is AccountActionResult.Done -> RecoveryCodesState(codes = result.value.recoveryCodes.toList())
                    is AccountActionResult.Refused -> RecoveryCodesState(error = result.message)
                    AccountActionResult.Unsupported -> RecoveryCodesState(
                        error = "This server no longer offers recovery codes.",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == startedGeneration) {
                    mutableState.value = RecoveryCodesState(error = "Could not issue recovery codes. Try again.")
                }
            } finally {
                if (generation == startedGeneration && state.value.busy) {
                    mutableState.value = RecoveryCodesState()
                }
            }
        }
    }

    override fun onCleared() {
        generation++
        scope.cancel()
        session = null
        mutableState.value = RecoveryCodesState()
    }

    private fun sameSession(first: SessionState?, second: SessionState): Boolean =
        first != null && first.serverUrl == second.serverUrl && first.username == second.username &&
            first.token == second.token
}
