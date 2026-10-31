package dk.perspektiva.ttsroad.player

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat

/** A short buzz confirming a shake bought more sleep-timer time (#500). No-op without a vibrator. */
fun vibrateExtended(context: Context) {
    val vibrator = ContextCompat.getSystemService(context, Vibrator::class.java) ?: return
    if (!vibrator.hasVibrator()) return
    runCatching { vibrator.vibrate(VibrationEffect.createOneShot(80L, VibrationEffect.DEFAULT_AMPLITUDE)) }
}
