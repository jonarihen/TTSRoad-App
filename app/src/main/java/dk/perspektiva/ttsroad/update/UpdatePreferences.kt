package dk.perspektiva.ttsroad.update

import android.content.Context
import androidx.core.content.edit

/**
 * What the person has told the updater (#363, #369): whether to look for updates on launch at all,
 * a version they chose to skip, and how long LATER keeps the prompt away.
 */
internal data class UpdatePromptChoices(
    val autoCheck: Boolean = true,
    val skippedVersion: String? = null,
    val snoozedUntilMs: Long = 0L,
)

/** LATER means "not today", not "not until the next cold start". */
internal const val UpdateSnoozeMs = 24L * 60 * 60 * 1000

/**
 * Whether an automatic check that found [versionName] should open the update dialog. A manual
 * check always does — asking is the opposite of being nagged.
 */
internal fun shouldAnnounceUpdate(versionName: String, choices: UpdatePromptChoices, nowMs: Long): Boolean =
    versionName != choices.skippedVersion && nowMs >= choices.snoozedUntilMs

internal object UpdatePreferences {
    private const val File = "ttsroad_updates"
    private const val AutoCheck = "auto_check"
    private const val SkippedVersion = "skipped_version"
    private const val SnoozedUntil = "snoozed_until_ms"

    private fun prefs(context: Context) = context.getSharedPreferences(File, Context.MODE_PRIVATE)

    fun read(context: Context): UpdatePromptChoices = prefs(context).let {
        UpdatePromptChoices(
            autoCheck = it.getBoolean(AutoCheck, true),
            skippedVersion = it.getString(SkippedVersion, null),
            snoozedUntilMs = it.getLong(SnoozedUntil, 0L),
        )
    }

    fun setAutoCheck(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(AutoCheck, enabled) }
    }

    fun skip(context: Context, versionName: String) {
        prefs(context).edit { putString(SkippedVersion, versionName) }
    }

    fun snooze(context: Context, nowMs: Long = System.currentTimeMillis()) {
        prefs(context).edit { putLong(SnoozedUntil, nowMs + UpdateSnoozeMs) }
    }
}
