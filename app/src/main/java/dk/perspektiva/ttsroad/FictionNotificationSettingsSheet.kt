package dk.perspektiva.ttsroad

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dk.perspektiva.ttsroad.data.FictionNotificationSettings
import dk.perspektiva.ttsroad.data.FictionNotificationSettingsRequest
import dk.perspektiva.ttsroad.data.FictionNotificationSettingsResult
import dk.perspektiva.ttsroad.data.FictionNotificationStatus
import dk.perspektiva.ttsroad.data.NotificationModeBacklog
import dk.perspektiva.ttsroad.data.NotificationModeEvery
import dk.perspektiva.ttsroad.data.NotificationModeOff
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.data.fictionNotificationStatus
import dk.perspektiva.ttsroad.data.notificationModeSummary
import dk.perspektiva.ttsroad.data.notificationStatusDescription
import dk.perspektiva.ttsroad.data.remainingBacklogLabel
import dk.perspektiva.ttsroad.data.validBacklogHours
import dk.perspektiva.ttsroad.ui.AarisChoiceRow
import dk.perspektiva.ttsroad.ui.AarisColor
import dk.perspektiva.ttsroad.ui.MetaText
import dk.perspektiva.ttsroad.ui.SectionHeader
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Stable
internal class FictionNotificationSettingsState(
    private val load: suspend () -> FictionNotificationSettingsResult,
    private val update: suspend (FictionNotificationSettingsRequest) -> FictionNotificationSettingsResult,
    private val enabled: Boolean = true,
) {
    constructor(
        repository: TtsRoadRepository,
        fictionId: Int,
        enabled: Boolean = true,
    ) : this(
        load = { repository.fictionNotificationSettings(fictionId) },
        update = { repository.updateFictionNotificationSettings(fictionId, it) },
        enabled = enabled,
    )

    var settings: FictionNotificationSettings? by mutableStateOf(null)
        private set
    var loading: Boolean by mutableStateOf(false)
        private set
    var saving: Boolean by mutableStateOf(false)
        private set
    val error: String? get() = saveError ?: loadError
    var stale: Boolean by mutableStateOf(false)
        private set

    private var loadError: String? by mutableStateOf(null)
    private var saveError: String? by mutableStateOf(null)
    private val requests = Mutex()
    private val saves = Mutex()
    private val completedRequests = AtomicLong()

    suspend fun refresh() {
        if (!enabled) return
        val previous = completedRequests.get()
        requests.withLock {
            if (completedRequests.get() != previous) return
            loading = true
            try {
                accept(load(), "Could not load chapter notification settings. Try again.")
                completedRequests.incrementAndGet()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed("Could not load chapter notification settings. Try again.")
                completedRequests.incrementAndGet()
            } finally {
                loading = false
            }
        }
    }

    suspend fun save(request: FictionNotificationSettingsRequest): Boolean {
        if (!enabled || settings == null || !saves.tryLock()) return false
        saving = true
        try {
            return requests.withLock {
                try {
                    val saved = accept(
                        update(request),
                        "Could not save chapter notification settings. Try again.",
                        isSave = true,
                    )
                    completedRequests.incrementAndGet()
                    saved
                } catch (cancelled: CancellationException) {
                    stale = settings != null
                    throw cancelled
                } catch (_: Exception) {
                    failed("Could not save chapter notification settings. Try again.", isSave = true)
                    completedRequests.incrementAndGet()
                    false
                }
            }
        } finally {
            saving = false
            saves.unlock()
        }
    }

    private suspend fun accept(
        result: FictionNotificationSettingsResult,
        fallback: String,
        isSave: Boolean = false,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        return when (result) {
            is FictionNotificationSettingsResult.Loaded -> {
                settings = result.settings
                loadError = null
                if (isSave) saveError = null
                stale = false
                true
            }
            is FictionNotificationSettingsResult.Refused -> {
                failed(result.message.ifBlank { fallback }, isSave)
                false
            }
            FictionNotificationSettingsResult.Unsupported -> {
                failed("This server cannot configure chapter notifications.", isSave)
                false
            }
        }
    }

    private fun failed(message: String, isSave: Boolean = false) {
        if (isSave) saveError = message else loadError = message
        stale = settings != null
    }
}

@Composable
internal fun rememberFictionNotificationSettings(
    repository: TtsRoadRepository,
    fictionId: Int,
    session: SessionState,
    available: Boolean,
    refreshKey: Any?,
): FictionNotificationSettingsState {
    val state = remember(repository, fictionId, session.serverUrl, session.token, available) {
        FictionNotificationSettingsState(repository, fictionId, enabled = available && session.isLoggedIn)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestRefreshKey by rememberUpdatedState(refreshKey)
    LaunchedEffect(state, lifecycle) {
        if (!available || !session.isLoggedIn) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            launch {
                snapshotFlow { latestRefreshKey }.collect { state.refresh() }
            }
            launch {
                while (isActive) {
                    delay(60_000L)
                    state.refresh()
                }
            }
        }
    }
    return state
}

private data class FictionNotificationDraft(
    val saved: FictionNotificationSettings,
    val mode: String = saved.mode,
    val hours: String = saved.backlogHours.toString(),
) {
    val dirty: Boolean get() = mode != saved.mode ||
        (hours != saved.backlogHours.toString() && validBacklogHours(hours) != saved.backlogHours)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FictionNotificationSettingsSheet(
    state: FictionNotificationSettingsState,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = key(state) { rememberCoroutineScope() }
    val savedCallback by rememberUpdatedState(onSaved)
    var draft by remember(state) { mutableStateOf(state.settings?.let { FictionNotificationDraft(it) }) }
    val settings = state.settings
    val currentDraft = draft
    val mode = currentDraft?.mode
    val hours = currentDraft?.hours.orEmpty()
    val validHours = validBacklogHours(hours)
    val canEdit = currentDraft != null && !state.saving
    val canSave = canEdit && mode in listOf(NotificationModeEvery, NotificationModeOff, NotificationModeBacklog) &&
        (mode != NotificationModeBacklog || validHours != null)

    LaunchedEffect(state) { state.refresh() }
    LaunchedEffect(state, settings) {
        if (draft?.dirty != true) {
            draft = settings?.let { FictionNotificationDraft(it) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AarisColor.BgRaise,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionHeader(kicker = "NOTICE", title = "Chapter notifications")
            MetaText("Saved status")
            MetaText(notificationModeSummary(settings), color = AarisColor.Ink)
            Text(notificationStatusDescription(settings), color = AarisColor.Muted)
            settings?.remainingSeconds?.let(::remainingBacklogLabel)?.let {
                Text(it, color = AarisColor.Accent)
            }
            if (state.stale) {
                Text("Last known state. Refresh to confirm the current server settings.", color = AarisColor.Warning)
            }
            if (state.loading) {
                CircularProgressIndicator(color = AarisColor.Accent, modifier = Modifier.size(24.dp))
            }
            state.error?.let { Text(it, color = AarisColor.Danger) }
            if (state.stale || (settings == null && state.error != null)) {
                OutlinedButton(
                    onClick = { scope.launch { state.refresh() } },
                    enabled = !state.loading && !state.saving,
                    shape = RectangleShape,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("fiction-notification-retry"),
                ) { Text("RETRY") }
            }
            AarisChoiceRow(
                options = listOf(NotificationModeEvery, NotificationModeOff, NotificationModeBacklog),
                selected = mode.orEmpty(),
                label = {
                    when (it) {
                        NotificationModeEvery -> "Every chapter"
                        NotificationModeOff -> "Off"
                        else -> "Wait for a backlog"
                    }
                },
                onSelect = { selected -> draft = draft?.copy(mode = selected) },
                enabled = canEdit,
            )
            if (mode == NotificationModeBacklog) {
                AarisChoiceRow(
                    options = listOf(1.0, 2.0, 5.0),
                    selected = validHours ?: Double.NaN,
                    label = { "${it.toInt()}h" },
                    onSelect = { selected -> draft = draft?.copy(hours = selected.toInt().toString()) },
                    enabled = canEdit,
                )
                OutlinedTextField(
                    value = hours,
                    onValueChange = { draft = draft?.copy(hours = it) },
                    label = { Text("Custom hours") },
                    supportingText = {
                        Text(if (hours.isNotBlank() && validHours == null) "Enter a number over 0 and up to 1000" else "0 < hours ≤ 1000")
                    },
                    isError = hours.isNotBlank() && validHours == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    enabled = canEdit,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Ready, unplayed audio is counted at 1x speed. Get one alert at the threshold, " +
                        "then no more until you listen below it.",
                    color = AarisColor.Muted,
                )
            }
            Text(
                "Changing this setting clears this book's existing chapter notices.",
                color = AarisColor.Dim,
            )
            if (currentDraft?.dirty == true) Text("Not saved yet.", color = AarisColor.Warning)
            Button(
                onClick = {
                    val selectedMode = draft?.mode ?: return@Button
                    val threshold = if (selectedMode == NotificationModeBacklog) {
                        validBacklogHours(draft?.hours.orEmpty()) ?: return@Button
                    } else {
                        state.settings?.backlogHours?.toString()?.let(::validBacklogHours) ?: 2.0
                    }
                    scope.launch {
                        if (state.save(FictionNotificationSettingsRequest(selectedMode, threshold))) {
                            draft = state.settings?.let { FictionNotificationDraft(it) }
                            savedCallback()
                        }
                    }
                },
                enabled = canSave,
                shape = RectangleShape,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("fiction-notification-save"),
            ) { Text(if (state.saving) "SAVING…" else "SAVE") }
        }
    }
}

@Composable
internal fun FictionNotificationStatusButton(
    settings: FictionNotificationSettings?,
    stale: Boolean = false,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    val status = fictionNotificationStatus(settings)
    val label = when (status) {
        FictionNotificationStatus.Armed -> "ARMED"
        FictionNotificationStatus.Waiting -> "WAITING"
        FictionNotificationStatus.Off -> "OFF"
        FictionNotificationStatus.Every -> "EVERY CHAPTER"
        FictionNotificationStatus.Unknown -> if (loading && settings == null) "CHECKING" else "STATUS UNAVAILABLE"
    }
    val color = when (status) {
        FictionNotificationStatus.Armed -> AarisColor.Danger
        FictionNotificationStatus.Every -> AarisColor.Accent
        else -> AarisColor.Muted
    }
    val icon = when (settings?.mode) {
        NotificationModeBacklog -> Icons.Outlined.Alarm
        NotificationModeEvery -> Icons.Outlined.NotificationsActive
        NotificationModeOff -> Icons.Outlined.NotificationsOff
        else -> Icons.Outlined.NotificationsNone
    }
    OutlinedButton(
        onClick = onClick,
        shape = RectangleShape,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .testTag("fiction-notification-status")
            .semantics {
                contentDescription = "Chapter notifications"
                stateDescription = notificationStatusDescription(settings) +
                    if (stale) " Last known state; refresh to confirm." else ""
            },
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Column {
            Text(label)
            if (stale) Text("LAST KNOWN", style = MaterialTheme.typography.labelSmall)
        }
    }
}
