package dk.perspektiva.ttsroad.player

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The last time a sleep timer stopped playback, and when the listener last touched that timer.
 *
 * Both wall-clock epoch millis. The touch — arming it, or the half-awake shake that extends it — is
 * the last moment the app *knows* the listener was awake, which makes it a far better guess at
 * "where did I drift off" than the moment the timer finally fired (#332).
 */
data class SleepTimerStop(
    val stoppedAtMs: Long,
    val lastInteractionAtMs: Long,
)

/**
 * Keeps the newest [SleepTimerStop] across process death: the timer fires at night, and the banner
 * that uses it is read in the morning, usually by a fresh process. Plain SharedPreferences rather
 * than a JSON file, so there is no reflectively serialized model for R8 to rename.
 */
class SleepTimerStopStore(context: Context) {
    private val prefs = context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
    private val _latest = MutableStateFlow(read())
    val latest: StateFlow<SleepTimerStop?> = _latest.asStateFlow()

    fun record(stop: SleepTimerStop) {
        prefs.edit()
            .putLong(KeyStoppedAt, stop.stoppedAtMs)
            .putLong(KeyLastInteractionAt, stop.lastInteractionAtMs)
            .apply()
        _latest.value = stop
    }

    private fun read(): SleepTimerStop? {
        val stoppedAt = prefs.getLong(KeyStoppedAt, -1L)
        val interaction = prefs.getLong(KeyLastInteractionAt, -1L)
        if (stoppedAt <= 0L || interaction <= 0L) return null
        return SleepTimerStop(stoppedAtMs = stoppedAt, lastInteractionAtMs = interaction)
    }

    private companion object {
        const val PrefsName = "sleep_timer_stop"
        const val KeyStoppedAt = "stopped_at_ms"
        const val KeyLastInteractionAt = "last_interaction_at_ms"
    }
}
