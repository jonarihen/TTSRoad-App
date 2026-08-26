package dk.perspektiva.ttsroad

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dk.perspektiva.ttsroad.core.ServiceLocator
import dk.perspektiva.ttsroad.data.ActivityDay
import dk.perspektiva.ttsroad.data.ListeningMilestone
import dk.perspektiva.ttsroad.data.ListeningStats
import dk.perspektiva.ttsroad.data.TopListenedFiction
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.data.startOfDayMillis
import dk.perspektiva.ttsroad.player.parseServerInstant
import dk.perspektiva.ttsroad.ui.AarisCard
import dk.perspektiva.ttsroad.ui.AarisColor
import dk.perspektiva.ttsroad.ui.AarisTag
import dk.perspektiva.ttsroad.ui.MetaText
import dk.perspektiva.ttsroad.ui.ThinProgress
import java.util.Date
import kotlinx.coroutines.launch

/**
 * Listening statistics (#117).
 *
 * The web has rendered these since long before the app existed, which was backwards: the phone and
 * the car write nearly every playback row behind them and were the only clients that could not show
 * them. `jonarihen/TTSRoad#165` published the payload; this screen is the other half of the issue.
 *
 * Two sources sit here on purpose, because they answer different questions:
 *
 * - **On this device** is computed from the local playback log and needs no network. It is the only
 *   thing that can say how long you have been listening *today*, because the server holds one
 *   `last_listened_at` per chapter and counts that chapter's whole heard duration against the day it
 *   was last touched — finish this morning a chapter you have been picking at all week and the
 *   entire week lands on today.
 * - **On your account** is the server's lifetime aggregation: totals, streaks, the activity grid and
 *   the badges, the same figures the browser shows.
 *
 * Every display label the server sends is rendered rather than recomputed. `time_label`,
 * `daily_average_label`, the comparisons and the milestone strings arrive formatted on purpose:
 * re-deriving "3.2× the length of the Lord of the Rings" here would let two clients drift into
 * describing one account two different ways.
 */
@Composable
internal fun ListeningStatsScreen(
    padding: PaddingValues,
    repository: TtsRoadRepository,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val capabilities by repository.currentCapabilities.collectAsStateWithLifecycle()
    var stats by remember { mutableStateOf<ListeningStats?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            isLoading = true
            error = null
            runCatching { repository.listeningStats() }
                // A null body is the capability gate answering, which the section below already has
                // its own words for. It must not be mistaken for an account with nothing on it.
                .onSuccess { stats = it?.stats }
                .onFailure { error = it.message ?: "Could not load your listening statistics" }
            isLoading = false
        }
    }

    // Keyed on the capability so a server discovered after this screen opened still loads, and one
    // that cannot answer is never asked.
    LaunchedEffect(capabilities.listeningStats) { if (capabilities.listeningStats) load() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RecentListeningSettings(
            historyStore = remember { ServiceLocator.playbackHistory(context) },
        )

        AccountStatsSection(
            supported = capabilities.listeningStats,
            stats = stats,
            isLoading = isLoading,
            error = error,
            onRetry = ::load,
        )
    }
}

/**
 * The server-backed half, split out so its silences can be rendered in a test.
 *
 * There are three of them and they are not the same thing, which is the whole reason this is one
 * `when` rather than a null check: a server too old to publish the endpoint is a permanent fact
 * about the server, a failed request is worth a retry, and an account that has finished nothing yet
 * is neither. Collapsing them would tell someone on an old server that they had never listened to
 * anything.
 */
@Composable
internal fun AccountStatsSection(
    supported: Boolean,
    stats: ListeningStats?,
    isLoading: Boolean,
    error: String?,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current

    MetaText(text = "// On your account", color = AarisColor.Accent)
    when {
        !supported -> EmptyCard(
            "This server does not publish listening statistics. Everything above is worked out " +
                "on the phone; the totals, streaks and activity grid are the server's to count, " +
                "and this one is too old to send them.",
        )

        isLoading && stats == null && error == null -> AarisCard {
            ThinProgress(
                fraction = 1f,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                height = 2.dp,
            )
        }

        stats == null -> AarisCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetaText(
                    text = error ?: "Could not load your listening statistics",
                    color = AarisColor.Danger,
                )
                OutlinedButton(onClick = onRetry, shape = RectangleShape) {
                    Text("RETRY")
                }
            }
        }

        // Reachable, answered, and genuinely empty — worth saying plainly rather than drawing a
        // wall of zeroes and a blank calendar.
        !stats.hasData -> EmptyCard(
            "Nothing counted on this account yet. These are worked out from your saved " +
                "positions, so listening on any device — phone, car or browser — fills them in.",
        )

        else -> {
            // A refresh over a payload already on screen is a hairline, not a spinner: the numbers
            // below stay readable while it runs.
            if (isLoading) {
                ThinProgress(fraction = 1f, modifier = Modifier.fillMaxWidth(), height = 2.dp)
            }
            error?.let { MetaText(text = it, color = AarisColor.Danger) }
            ListeningTotalsCard(stats = stats)
            ListeningRhythmCard(context = context, stats = stats)
            if (stats.activityWeeks.isNotEmpty()) ActivityGridCard(stats = stats)
            if (stats.topFictions.isNotEmpty()) TopFictionsCard(fictions = stats.topFictions)
            if (stats.milestones.isNotEmpty()) MilestonesCard(milestones = stats.milestones)
        }
    }
}

/** Hours listened, what that compares to, and what it came to in chapters, books and words. */
@Composable
private fun ListeningTotalsCard(stats: ListeningStats) {
    AarisCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MetaText(text = "Time listened")
            Text(
                text = stats.timeLabel.ifBlank { "0m" },
                style = MaterialTheme.typography.headlineSmall,
            )
            // Rendered exactly as sent. The yardstick and the multiplier are the server's, so the
            // phone and the browser cannot describe one total two different ways.
            for (comparison in stats.comparisons) {
                MetaText(
                    text = "${comparison.value} ${comparison.label}",
                    color = AarisColor.Accent,
                )
                MetaText(text = comparison.detail, color = AarisColor.Dim)
            }

            HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
            SettingsItem(
                label = "Chapters finished",
                value = stats.chaptersFinishedLabel.ifBlank { stats.chaptersFinished.toString() },
            )
            SettingsItem(label = "Chapters in progress", value = stats.chaptersInProgress.toString())

            HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
            SettingsItem(label = "Books started", value = stats.booksStarted.toString())
            SettingsItem(label = "Books finished", value = stats.booksFinished.toString())

            HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
            SettingsItem(
                label = "Words heard",
                value = stats.wordsLabel.ifBlank { stats.words.toString() },
            )
            SettingsItem(
                label = "Pages",
                value = stats.pagesLabel.ifBlank { stats.pages.toString() },
            )
            if (stats.uncountedChapters > 0) {
                MetaText(
                    text = "${listeningPlural(stats.uncountedChapters, "chapter")} you have heard " +
                        "have no word count on the server yet, so those two are a floor rather " +
                        "than a total.",
                    color = AarisColor.Dim,
                )
            }
        }
    }
}

/** Streaks, the daily average and the heaviest day — the shape of the listening, not its size. */
@Composable
private fun ListeningRhythmCard(context: android.content.Context, stats: ListeningStats) {
    AarisCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsItem(label = "Current streak", value = listeningPlural(stats.currentStreak, "day"))
            SettingsItem(label = "Longest streak", value = listeningPlural(stats.longestStreak, "day"))

            HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
            SettingsItem(label = "Daily average", value = stats.dailyAverageLabel.ifBlank { "0m" })
            MetaText(
                text = "Averaged over every day since the first chapter you finished, quiet days " +
                    "included — an average that skips the days you did not listen is not an " +
                    "average of anything.",
                color = AarisColor.Dim,
            )

            stats.busiestDay?.let { day ->
                HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
                SettingsItem(
                    label = "Busiest day",
                    // Falls back to the server's own ISO date rather than hiding the row: a date
                    // this client cannot parse is still a date, and the time under it is the point.
                    value = day.startOfDayMillis()
                        ?.let { formatCalendarDate(context, it) }
                        ?: day.date,
                )
                MetaText(
                    text = "${day.timeLabel} · ${listeningPlural(day.chapters, "chapter")} finished",
                    color = AarisColor.Dim,
                )
            }

            parseServerInstant(stats.firstListenedAt)?.let {
                HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
                SettingsItem(label = "First listened", value = formatCalendarDate(context, it))
            }
            parseServerInstant(stats.lastListenedAt)?.let {
                SettingsItem(label = "Last listened", value = formatCalendarDate(context, it))
            }
        }
    }
}

/**
 * The activity calendar: one square per day, a column per week, Monday at the top.
 *
 * Quiet days are drawn rather than dropped — a calendar with the empty days removed is a bar chart
 * pretending to be a calendar. Each square carries the server's own sentence for that day as its
 * content description, which is the only thing this grid says to a screen reader.
 */
@Composable
private fun ActivityGridCard(stats: ListeningStats) {
    AarisCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MetaText(text = "Last ${listeningPlural(stats.activityWeeks.size, "week")}")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (week in stats.activityWeeks) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        for (day in week) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .background(activityLevelColor(day))
                                    .semantics { contentDescription = day.label },
                            )
                        }
                    }
                }
            }
            MetaText(
                text = "Shade is that day against the busiest day in the window, so a quiet " +
                    "quarter is not flattened by a heavy one.",
                color = AarisColor.Dim,
            )
        }
    }
}

/**
 * Five steps rather than a continuous scale: the server buckets each day into 0–4 and the strip has
 * to stay readable at eighteen density-independent pixels a square.
 *
 * [ActivityDay.future] is the tail of the current week and is drawn as *absent*, not as a quiet
 * day — nobody has failed to listen on a day that has not happened.
 */
internal fun activityLevelColor(day: ActivityDay): Color = when {
    day.future -> AarisColor.BgInput
    day.level <= 0 -> AarisColor.BgHover
    day.level == 1 -> AarisColor.Accent.copy(alpha = 0.3f)
    day.level == 2 -> AarisColor.Accent.copy(alpha = 0.5f)
    day.level == 3 -> AarisColor.Accent.copy(alpha = 0.75f)
    else -> AarisColor.Accent
}

/** Where the hours went, longest first. */
@Composable
private fun TopFictionsCard(fictions: List<TopListenedFiction>) {
    AarisCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MetaText(text = "Where the hours went")
            for (fiction in fictions) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        MetaText(
                            text = fiction.title,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        MetaText(text = fiction.timeLabel, color = AarisColor.Ink)
                    }
                    ThinProgress(
                        fraction = fiction.percent / 100f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    MetaText(
                        text = if (fiction.totalChapters > 0) {
                            "${fiction.chaptersFinished} of ${fiction.totalChapters} chapters" +
                                if (fiction.complete) " · complete" else ""
                        } else {
                            "${listeningPlural(fiction.chaptersFinished, "chapter")} finished"
                        },
                        color = AarisColor.Dim,
                    )
                }
            }
        }
    }
}

/**
 * Badges, earned and pending.
 *
 * The web draws a Lucide glyph per group and this client has no such set, so the group name carries
 * it instead. Thresholds are fixed server-side rather than relative to the library — a badge that
 * moves when an admin adds a fiction is not a badge.
 */
@Composable
private fun MilestonesCard(milestones: List<ListeningMilestone>) {
    AarisCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MetaText(text = "Milestones")
            for (milestone in milestones) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            MetaText(text = milestone.title, maxLines = 1)
                            MetaText(text = milestone.group, color = AarisColor.Dim, maxLines = 1)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        AarisTag(
                            text = milestone.detail,
                            color = if (milestone.earned) AarisColor.Ok else AarisColor.Muted,
                        )
                    }
                    if (!milestone.earned) {
                        ThinProgress(
                            fraction = milestone.progress / 100f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/** A calendar date in the phone's own format — this screen never needs the time of day. */
private fun formatCalendarDate(context: android.content.Context, epochMillis: Long): String =
    android.text.format.DateFormat.getDateFormat(context).format(Date(epochMillis))
