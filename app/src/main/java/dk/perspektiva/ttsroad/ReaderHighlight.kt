package dk.perspektiva.ttsroad

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dk.perspektiva.ttsroad.data.ReadAlongDocument
import dk.perspektiva.ttsroad.data.ReadAlongHighlight
import dk.perspektiva.ttsroad.player.ReadAlongPlaybackPosition
import dk.perspektiva.ttsroad.player.ReadAlongPlaybackSample
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun rememberReaderHighlight(
    document: ReadAlongDocument?,
    chapterId: Int,
    playbackSample: () -> ReadAlongPlaybackSample?,
): ReadAlongHighlight {
    val currentPlaybackSample by rememberUpdatedState(playbackSample)
    val lifecycleOwner = LocalLifecycleOwner.current
    var highlight by remember(document, chapterId, lifecycleOwner) {
        mutableStateOf(ReadAlongHighlight.None)
    }

    LaunchedEffect(document, chapterId, lifecycleOwner) {
        highlight = ReadAlongHighlight.None
        if (document == null || document.chapterId != chapterId || !document.hasTimings) {
            return@LaunchedEffect
        }
        val expectedMediaId = "chapter:$chapterId"
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val tracker = ReadAlongPlaybackPosition()
            try {
                while (isActive) {
                    val sample = currentPlaybackSample()
                    val positionMs = tracker.positionMs(
                        sample = sample,
                        expectedMediaId = expectedMediaId,
                        elapsedRealtimeMs = SystemClock.elapsedRealtime(),
                    )
                    highlight = positionMs?.let { document.highlightAtMillis(it) }
                        ?: ReadAlongHighlight.None
                    if (sample?.isPlaying == true && sample.mediaId == expectedMediaId) {
                        withFrameNanos { }
                    } else {
                        delay(50L)
                    }
                }
            } finally {
                highlight = ReadAlongHighlight.None
            }
        }
    }

    return highlight
}
