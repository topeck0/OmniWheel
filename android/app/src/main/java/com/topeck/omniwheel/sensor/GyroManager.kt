package com.topeck.omniwheel.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.topeck.omniwheel.network.InputSender
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Water-level gyroscope system with zero-latency direct write to InputSender.
 *
 * Uses the gravity sensor (or accelerometer as fallback) to determine
 * the phone's tilt angle relative to true horizontal. Like a physical
 * water level, it always knows where "level" is because gravity always
 * points down — no manual calibration needed.
 *
 * When inputSender is set, every sensor event immediately writes the
 * steering value directly to the sender, bypassing any polling loop.
 */
class GyroManager(context: Context) : SensorEventListener {
    companion object {
        private const val TAG = "GyroManager"
        private const val MAX_STEERING = 32767
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // Sensors: prefer GRAVITY (clean signal) over ACCELEROMETER (noisy)
    private val gravitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelSensor: Sensor? = if (gravitySensor == null)
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) else null

    val isAvailable: Boolean get() = gravitySensor != null || accelSensor != null
    val sensorName: String get() = when {
        gravitySensor != null -> "Gravity"
        accelSensor != null -> "Accelerometer"
        else -> "None"
    }

    // Configurable parameters (set from SettingsManager)
    var maxTiltDeg = 25f
    var sensitivity = 1.8f
    var deadzoneDeg = 2.0f
    var filterAlpha: Float
        get() = _filterAlphaOverride ?: if (gravitySensor != null) 0.25f else 0.08f
        set(v) { _filterAlphaOverride = v }
    private var _filterAlphaOverride: Float? = null
    var smoothAlpha = 0.22f

    // Direct input sender for zero-latency writes
    var inputSender: InputSender? = null

    // Filtered gravity values (device coordinates)
    private var gX = 0f    // device right
    private var gY = 0f    // device top
    private var gZ = 0f    // device front (out of screen)

    // Output
    @Volatile var isEnabled = false; private set
    @Volatile var tiltDegrees = 0f; private set
    @Volatile private var smoothTilt = 0f

    private fun getScreenRotation(): Int {
        return windowManager.defaultDisplay.rotation
    }

    fun enable() {
        if (isEnabled) return
        val sensor = gravitySensor ?: accelSensor ?: return

        // Reset filters
        gX = 0f; gY = 0f; gZ = 0f
        smoothTilt = 0f

        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        isEnabled = true
        Log.d(TAG, "Water-level gyro enabled ($sensorName)")
    }

    fun disable() {
        if (!isEnabled) return
        sensorManager.unregisterListener(this)
        isEnabled = false
        tiltDegrees = 0f
        smoothTilt = 0f
        // Write zero on disable
        inputSender?.steering = 0
    }

    /**
     * Returns steering value from -1.0 (full left) to 1.0 (full right).
     * Zero = phone is level (horizontal).
     */
    fun getSteeringFromTilt(): Float {
        val effectiveTilt = if (abs(smoothTilt) < deadzoneDeg) 0f else smoothTilt
        val effectiveMax = maxTiltDeg * sensitivity
        return (effectiveTilt / effectiveMax).coerceIn(-1f, 1f)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val alpha = filterAlpha
        gX += alpha * (event.values[0] - gX)
        gY += alpha * (event.values[1] - gY)
        gZ += alpha * (event.values[2] - gZ)

        // Map gravity from device coordinates to SCREEN coordinates cleanly
        val rotation = getScreenRotation()
        val rawTilt = when (rotation) {
            Surface.ROTATION_90 -> gY
            Surface.ROTATION_270 -> -gY
            Surface.ROTATION_180 -> -gX
            else -> gX
        }

        // Linear tilt component normalized against gravity (~9.81)
        val tiltDeg = (rawTilt / 9.81f).coerceIn(-1f, 1f) * maxTiltDeg
        tiltDegrees = tiltDeg
        smoothTilt += smoothAlpha * (tiltDeg - smoothTilt)

        // ZERO-LATENCY: Write steering directly to input sender from sensor event
        inputSender?.let { sender ->
            val steering = getSteeringFromTilt()
            sender.steering = (steering * MAX_STEERING).toInt().coerceIn(-MAX_STEERING, MAX_STEERING).toShort()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}