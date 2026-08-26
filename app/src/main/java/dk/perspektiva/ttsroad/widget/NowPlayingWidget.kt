package dk.perspektiva.ttsroad.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.action.actionStartActivity
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.Button
import dk.perspektiva.ttsroad.MainActivity
import dk.perspektiva.ttsroad.core.ServiceLocator
import dk.perspektiva.ttsroad.ui.AarisColor

/**
 * "What am I listening to, and pause it" — one tap from the home screen (#150).
 *
 * Opening the app to press pause is three steps for something the launcher can do in one, and the
 * phone spends a lot of its TTSRoad time in a car mount where those steps are worse than merely
 * slow.
 *
 * Everything drawn comes from [NowPlayingStore] rather than from a live player, because the
 * launcher renders this whenever it likes — usually with this app's process long dead. The
 * decisions about what that stored note actually means live in [widgetView], not here.
 */
class NowPlayingWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = NowPlayingStore(context).read()
        // Read once, outside the composition: the widget is drawn in whatever process the launcher
        // woke, and a signed-out phone must not show the previous account's book.
        val signedIn = runCatching { ServiceLocator.tokenStore(context).current().isLoggedIn }
            .getOrDefault(false)
        val view = widgetView(snapshot, signedIn = signedIn, now = System.currentTimeMillis())

        provideContent {
            GlanceTheme {
                WidgetBody(view)
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun WidgetBody(view: WidgetView) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(AarisColor.Bg)
            // The whole surface opens the app; the transport buttons below claim their own taps.
            .clickable(actionStartActivity<MainActivity>())
            .padding(12.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        when (view) {
            WidgetView.SignedOut -> Message("Sign in to TTSRoad")
            WidgetView.NothingPlayed -> Message("Nothing played yet")
            is WidgetView.Playback -> PlaybackBody(view)
        }
    }
}

@androidx.compose.runtime.Composable
private fun Message(text: String) {
    Text(
        text = text,
        style = TextStyle(color = androidx.glance.unit.ColorProvider(AarisColor.Dim), fontSize = 13.sp),
    )
}

@androidx.compose.runtime.Composable
private fun PlaybackBody(view: WidgetView.Playback) {
    view.fictionTitle?.let { fiction ->
        Text(
            text = fiction.uppercase(),
            maxLines = 1,
            style = TextStyle(
                color = androidx.glance.unit.ColorProvider(AarisColor.Dim),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        Spacer(GlanceModifier.height(2.dp))
    }
    Text(
        text = view.chapterTitle,
        maxLines = 2,
        style = TextStyle(
            color = androidx.glance.unit.ColorProvider(Color.White),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        ),
    )
    Spacer(GlanceModifier.height(4.dp))
    Text(
        // "Last heard" rather than a countdown when the note went stale: claiming time remaining on
        // audio that stopped at an unknown moment would be a guess dressed as a fact.
        text = if (view.wentQuiet) "Last heard" else view.remainingLabel.orEmpty(),
        maxLines = 1,
        style = TextStyle(
            color = androidx.glance.unit.ColorProvider(AarisColor.Dim),
            fontSize = 11.sp,
        ),
    )
    Spacer(GlanceModifier.height(8.dp))
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        TransportButton("−30", actionRunCallback<SkipBackAction>())
        Spacer(GlanceModifier.width(6.dp))
        TransportButton(
            text = if (view.isPlaying) "Pause" else "Play",
            action = actionRunCallback<TogglePlayPauseAction>(),
            accent = true,
        )
        Spacer(GlanceModifier.width(6.dp))
        TransportButton("+30", actionRunCallback<SkipForwardAction>())
    }
}

@androidx.compose.runtime.Composable
private fun TransportButton(
    text: String,
    action: androidx.glance.action.Action,
    accent: Boolean = false,
) {
    Button(
        text = text,
        onClick = action,
        style = TextStyle(
            color = androidx.glance.unit.ColorProvider(if (accent) Color.Black else Color.White),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        ),
        modifier = GlanceModifier.background(if (accent) AarisColor.Accent else AarisColor.Line),
    )
}

/** The receiver the launcher actually instantiates. */
class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()
}
