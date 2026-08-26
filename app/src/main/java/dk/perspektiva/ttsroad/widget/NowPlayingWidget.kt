package dk.perspektiva.ttsroad.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dk.perspektiva.ttsroad.MainActivity
import dk.perspektiva.ttsroad.core.ServiceLocator
import dk.perspektiva.ttsroad.ui.AarisColor

/** Below this height the widget is one launcher cell tall and only the essentials fit. */
private val CompactCeiling = 100.dp

/**
 * "What am I listening to, and pause it" — one tap from the home screen (#150).
 *
 * Opening the app to press pause is three steps for something the launcher can do in one, and the
 * phone spends a lot of its TTSRoad time in a car mount where those steps are worse than merely
 * slow.
 *
 * Everything drawn comes from [NowPlayingStore] rather than from a live player, because the
 * launcher renders this whenever it likes — usually with this app's process long dead. The
 * decisions about what that stored note actually means live in [widgetView], not here, which is
 * also what makes them testable without a launcher.
 */
class NowPlayingWidget : GlanceAppWidget() {
    /**
     * Two shapes rather than one that clips.
     *
     * A one-cell strip has room for the book, the chapter and pause; a two-cell one also has room
     * for the time remaining and the 30-second skips. Glance builds both and the launcher picks,
     * so resizing does not require this to be redrawn from the service.
     */
    override val sizeMode = SizeMode.Responsive(
        setOf(DpSize(180.dp, 60.dp), DpSize(250.dp, 110.dp)),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = NowPlayingStore(context).read()
        // Read once, outside the composition: the widget is drawn in whatever process the launcher
        // woke, and a signed-out phone must not show the previous account's book.
        val signedIn = runCatching { ServiceLocator.tokenStore(context).current().isLoggedIn }
            .getOrDefault(false)
        val view = widgetView(snapshot, signedIn = signedIn, now = System.currentTimeMillis())
        // Fetched before the composition rather than inside it: a launcher is handed pixels, not a
        // URL, and composition is not the place to be on the network.
        val cover = (view as? WidgetView.Playback)?.let { loadCoverBitmap(context, it.coverUrl) }

        // Built here because it needs a Context. A tap on something that names a chapter should
        // land on that chapter's player, exactly as a notification tap does — but there is nothing
        // to open the player on when nothing has played, so those states open the app plainly.
        val open = if (view is WidgetView.Playback) {
            MainActivity.playerIntent(context)
        } else {
            Intent(context, MainActivity::class.java)
        }

        provideContent {
            GlanceTheme {
                WidgetBody(view, cover, open)
            }
        }
    }
}

@Composable
private fun WidgetBody(view: WidgetView, cover: Bitmap?, open: Intent) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(AarisColor.Bg)
            // The whole surface opens the app; the transport buttons below claim their own taps.
            .clickable(actionStartActivity(open))
            .padding(12.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        when (view) {
            WidgetView.SignedOut -> Message("Sign in to TTSRoad")
            WidgetView.NothingPlayed -> Message("Nothing played yet")
            is WidgetView.Playback -> PlaybackBody(view, cover)
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text = text,
        style = TextStyle(color = ColorProvider(AarisColor.Dim), fontSize = 13.sp),
    )
}

@Composable
private fun PlaybackBody(view: WidgetView.Playback, cover: Bitmap?) {
    val compact = LocalSize.current.height < CompactCeiling
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        if (cover != null) {
            Image(
                provider = ImageProvider(cover),
                // The chapter title sits beside it and says the same thing; announcing the cover
                // again would make a screen reader read the book twice before reaching the buttons.
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier.size(if (compact) 40.dp else 52.dp),
            )
            Spacer(GlanceModifier.width(10.dp))
        }
        Column(modifier = GlanceModifier.defaultWeight()) {
            view.fictionTitle?.let { fiction ->
                Text(
                    text = fiction.uppercase(),
                    maxLines = 1,
                    style = TextStyle(
                        color = ColorProvider(AarisColor.Dim),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Spacer(GlanceModifier.height(2.dp))
            }
            Text(
                text = view.chapterTitle,
                maxLines = if (compact) 1 else 2,
                style = TextStyle(
                    color = ColorProvider(Color.White),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            if (!compact) {
                Spacer(GlanceModifier.height(4.dp))
                Text(
                    // "Last heard" rather than a countdown when the note went stale: claiming time
                    // remaining on audio that stopped at an unknown moment would be a guess dressed
                    // as a fact.
                    text = if (view.wentQuiet) "Last heard" else view.remainingLabel.orEmpty(),
                    maxLines = 1,
                    style = TextStyle(color = ColorProvider(AarisColor.Dim), fontSize = 11.sp),
                )
            }
        }
        if (compact) {
            Spacer(GlanceModifier.width(8.dp))
            PlayPauseButton(view)
        }
    }
    if (!compact) {
        Spacer(GlanceModifier.height(8.dp))
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            TransportButton("−30", actionRunCallback<SkipBackAction>())
            Spacer(GlanceModifier.width(6.dp))
            PlayPauseButton(view)
            Spacer(GlanceModifier.width(6.dp))
            TransportButton("+30", actionRunCallback<SkipForwardAction>())
        }
    }
}

@Composable
private fun PlayPauseButton(view: WidgetView.Playback) {
    TransportButton(
        // Labelled for what the tap will do, not for what is happening. A stale note that still
        // claims to be playing has already been resolved to false by widgetView, so this cannot
        // offer Pause for audio that stopped hours ago.
        text = if (view.isPlaying) "Pause" else "Play",
        action = actionRunCallback<TogglePlayPauseAction>(),
        accent = true,
    )
}

@Composable
private fun TransportButton(text: String, action: Action, accent: Boolean = false) {
    Button(
        text = text,
        onClick = action,
        style = TextStyle(
            color = ColorProvider(if (accent) Color.Black else Color.White),
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
