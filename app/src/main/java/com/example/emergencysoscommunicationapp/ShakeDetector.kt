package com.example.emergencysoscommunicationapp

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * Robust accelerometer shake detector with sensitivity levels,
 * multi-peak validation, and cooldown protection against false triggers.
 */
class ShakeDetector(
    private val onShakeListener: OnShakeListener
) : SensorEventListener {

    fun interface OnShakeListener {
        fun onShake()
    }

    companion object {
        private const val TAG = "ShakeDetector"
        private const val SHAKE_WINDOW_TIME_MS = 650L
        private const val SHAKE_COOLDOWN_TIME_MS = 3500L
        private const val MIN_REQUIRED_PEAKS = 3

        const val SENSITIVITY_LOW = "Low"
        const val SENSITIVITY_MEDIUM = "Medium"
        const val SENSITIVITY_HIGH = "High"
    }

    private var shakeThresholdGravity = 2.7f // Default Medium
    private var firstPeakTimestamp: Long = 0L
    private var peakCount = 0
    private var lastShakeTriggeredTime: Long = 0L

    fun setSensitivity(level: String) {
        shakeThresholdGravity = when (level) {
            SENSITIVITY_LOW -> 3.2f
            SENSITIVITY_HIGH -> 2.1f
            else -> 2.7f
        }
        Log.d(TAG, "Shake sensitivity set to $level (threshold: $shakeThresholdGravity g)")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val now = System.currentTimeMillis()

        // Respect cooldown period after a confirmed shake sequence
        if (now - lastShakeTriggeredTime < SHAKE_COOLDOWN_TIME_MS) {
            return
        }

        val x = event.values[0] / SensorManager.GRAVITY_EARTH
        val y = event.values[1] / SensorManager.GRAVITY_EARTH
        val z = event.values[2] / SensorManager.GRAVITY_EARTH

        val gForce = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()

        if (gForce > shakeThresholdGravity) {
            if (peakCount == 0) {
                firstPeakTimestamp = now
                peakCount = 1
                Log.d(TAG, "First shake peak detected (gForce=%.2f)".format(gForce))
            } else {
                val timeSinceFirstPeak = now - firstPeakTimestamp
                if (timeSinceFirstPeak < SHAKE_WINDOW_TIME_MS) {
                    peakCount++
                    Log.d(TAG, "Additional shake peak $peakCount in window (gForce=%.2f)".format(gForce))

                    if (peakCount >= MIN_REQUIRED_PEAKS) {
                        Log.d(TAG, "Valid shake sequence confirmed ($peakCount peaks). Triggering callback.")
                        lastShakeTriggeredTime = now
                        peakCount = 0
                        firstPeakTimestamp = 0L
                        onShakeListener.onShake()
                    }
                } else {
                    // Window expired; restart peak counter with current peak
                    firstPeakTimestamp = now
                    peakCount = 1
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    fun reset() {
        peakCount = 0
        firstPeakTimestamp = 0L
    }
}
