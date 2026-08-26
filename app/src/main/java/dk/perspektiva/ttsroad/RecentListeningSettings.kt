package dk.perspektiva.ttsroad

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dk.perspektiva.ttsroad.player.HistorySnapshotCapacity
import dk.perspektiva.ttsroad.player.PlaybackHistoryStore
import dk.perspektiva.ttsroad.player.RecentListeningSummary
import dk.perspektiva.ttsroad.player.formatListeningSpan
import dk.perspektiva.ttsroad.player.recentListeningSummary
import dk.perspektiva.ttsroad.ui.AarisCard
import dk.perspektiva.ttsroad.ui.AarisColor
import dk.perspektiva.ttsroad.ui.MetaText
import java.time.Instant
import java.time.ZoneId
import java.util.Date

/**
 * The local half of the Stats screen: what this phone heard today, from the log it already keeps.
 *
 * It sits above the server's lifetime figures rather than instead of them, because it is the one
 * question those figures cannot answer. The server holds a single `last_listened_at` per chapter,
 * so its per-day activity counts a chapter's whole heard duration against the day it was last
 * touched — finish this morning a chapter you have been picking at all week and the entire week
 * lands on today. This counts the wall clock instead, from snapshots taken while audio was actually
 * moving, and it needs no network to do it.
 *
 * The copy below is careful about what it is: a recent window, named as one, never a lifetime
 * total. That claim belongs to the section underneath it, which can back it.
 */
@Composable
internal fun RecentListeningSettings(historyStore: PlaybackHistoryStore) {
    val history by historyStore.snapshots.collectAsStateWithLifecycle()
    // Re-read the clock on resume so a phone left on this screen overnight does not go on calling
    // yesterday "today". The history itself updates every fifteen seconds of playback.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LifecycleResumeEffect(Unit) {
        now = System.currentTimeMillis()
        onPauseOrDispose { }
    }
    val summary = remember(history, now) { recentListeningSummary(history, now = now) }

    RecentListeningCard(summary = summary, now = now)
}

/**
 * The card itself, taking the finished sums rather than a store — so it renders on the JVM in a
 * layout test without a `Context`-bound history file behind it.
 */
@Composable
internal fun RecentListeningCard(summary: RecentListeningSummary, now: Long) {
    val context = LocalContext.current

    MetaText(text = "// On this device", color = AarisColor.Accent)
    AarisCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!summary.hasHistory) {
                MetaText(text = "Nothing recorded yet")
                MetaText(
                    text = "This fills in as you play. It is worked out on the phone from the " +
                        "playback log, so it needs nothing from the server and works offline.",
                    color = AarisColor.Dim,
                )
                return@Column
            }

            MetaText(text = "Today")
            Text(
                text = formatListeningSpan(summary.todayMs / 1000.0),
                style = MaterialTheme.typography.headlineSmall,
            )
            MetaText(text = todayBreakdown(summary), color = AarisColor.Dim)

            if (summary.todayFictions.isNotEmpty()) {
                HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
                for (fiction in summary.todayFictions.take(MaxFictionRows)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        MetaText(
                            text = fiction.title ?: "Untitled book",
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        MetaText(
                            text = formatListeningSpan(fiction.listenedMs / 1000.0),
                            color = AarisColor.Ink,
                        )
                    }
                }
                val hidden = summary.todayFictions.size - MaxFictionRows
                if (hidden > 0) {
                    MetaText(text = "and $hidden more", color = AarisColor.Dim)
                }
            }

            HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)

            MetaText(text = "In the kept log")
            Text(
                text = formatListeningSpan(summary.retainedMs / 1000.0),
                style = MaterialTheme.typography.titleMedium,
            )
            summary.oldestAt?.let {
                MetaText(text = listeningWindowLabel(context, it, now), color = AarisColor.Dim)
            }
            MetaText(text = keptLogExplanation(summary), color = AarisColor.Dim)
        }
    }
}

/** Rows before the list stops being a list and starts being a screen of its own. */
private const val MaxFictionRows = 4

/** "4 chapters · 2 books · 3 sittings", with the zero case saying so in words. */
internal fun todayBreakdown(summary: RecentListeningSummary): String {
    if (summary.todayChapters == 0) return "No playback recorded today yet"
    return listOf(
        listeningPlural(summary.todayChapters, "chapter"),
        listeningPlural(summary.todayFictions.size, "book"),
        listeningPlural(summary.todaySittings, "sitting"),
    ).joinToString(" · ")
}

/**
 * The paragraph that keeps this honest.
 *
 * A full log is a different claim from a partly full one: the second really does cover everything
 * since its oldest entry, while the first has been dropping the far end for a while. Saying so is
 * the difference between a window the reader can trust and a total they will assume is complete.
 */
internal fun keptLogExplanation(summary: RecentListeningSummary): String {
    val window = if (summary.atCapacity) {
        "The log is full at $HistorySnapshotCapacity positions — roughly eight hours of " +
            "listening — so anything older has already been dropped. This is a recent window, " +
            "not an all-time total; the account figures below are."
    } else {
        "The log keeps the most recent $HistorySnapshotCapacity positions, roughly eight hours " +
            "of listening, and drops the oldest as it fills. This is a recent window, not an " +
            "all-time total; the account figures below are."
    }
    return "$window Time counts only while the audio was moving, so pauses and a chapter left " +
        "running on the nightstand add nothing."
}

/** "since 21:14", "since 21:14 yesterday", or "since 21:14 on 10 Mar". */
internal fun listeningWindowLabel(
    context: Context,
    oldestAt: Long,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val time = android.text.format.DateFormat.getTimeFormat(context).format(Date(oldestAt))
    val oldestDay = Instant.ofEpochMilli(oldestAt).atZone(zone).toLocalDate()
    val currentDay = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (oldestDay) {
        currentDay -> "since $time"
        currentDay.minusDays(1) -> "since $time yesterday"
        else -> {
            val date = android.text.format.DateFormat.getDateFormat(context).format(Date(oldestAt))
            "since $time on $date"
        }
    }
}

/**
 * "1 chapter" / "4 chapters". Shared with the Stats screen's server section, which counts days,
 * weeks and chapters the same way and has no business inventing a second set of plurals.
 */
internal fun listeningPlural(count: Int, noun: String): String =
    if (count == 1) "1 $noun" else "$count ${noun}s"
