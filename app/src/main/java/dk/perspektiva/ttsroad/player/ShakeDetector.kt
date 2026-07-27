package dk.perspektiva.ttsroad.player

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * Calls [onShake] when the device is shaken deliberately.
 *
 * Used to extend the sleep timer while it is fading out: a half-awake grab should not mean finding
 * a button on a bright screen. Registered only during the fade, so it costs nothing for the rest
 * of the night.
 */
class ShakeDetector(
    context: Context,
    private val onShake: () -> Unit,
) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var listening = false
    private var lastShakeAtMs = 0L

    fun start() {
        if (listening) return
        val sensor = accelerometer ?: return
        sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        listening = true
    }

    fun stop() {
        if (!listening) return
        sensorManager?.unregisterListener(this)
        listening = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        // Magnitude in g. At rest this reads ~1.0, so the threshold is well clear of rolling over.
        val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (gForce < ShakeThresholdG) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastShakeAtMs < DebounceMs) return
        lastShakeAtMs = now
        onShake()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val ShakeThresholdG = 1.9f

        /** One shake is many samples over the threshold; only the first counts. */
        const val DebounceMs = 1_000L
    }
}
