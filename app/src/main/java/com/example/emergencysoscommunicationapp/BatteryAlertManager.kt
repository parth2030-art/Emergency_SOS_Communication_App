package com.example.emergencysoscommunicationapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages low-battery detection, threshold configuration, deduplication,
 * and emergency SMS alert dispatch for active SOS sessions (Option A).
 */
object BatteryAlertManager {

    private const val TAG = "BatteryAlertManager"
    private const val PREFS_NAME = "BATTERY_ALERT_PREFS"
    private const val KEY_ALERT_SENT_SESSION = "low_battery_alert_sent_session_id"
    private const val KEY_ALERT_SENT_TIMESTAMP = "low_battery_alert_sent_timestamp"

    const val DEFAULT_THRESHOLD = 15
    const val PREFS_SETTINGS = "SOS_SETTINGS"
    const val KEY_LOW_BATTERY_THRESHOLD = "low_battery_threshold"

    /**
     * Reads current battery percentage (0-100) using Android sticky Intent.ACTION_BATTERY_CHANGED.
     * Returns null if battery information cannot be determined.
     */
    fun getBatteryLevel(context: Context): Int? {
        return try {
            val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus: Intent? = context.registerReceiver(null, intentFilter)
            val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                (level * 100 / scale.toFloat()).toInt()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error determining battery level: ${e.message}")
            null
        }
    }

    /**
     * Configured low-battery threshold percentage (default 15%).
     */
    fun getLowBatteryThreshold(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_LOW_BATTERY_THRESHOLD, DEFAULT_THRESHOLD)
    }

    /**
     * Updates configured low-battery threshold percentage.
     */
    fun setLowBatteryThreshold(context: Context, threshold: Int) {
        val prefs = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_LOW_BATTERY_THRESHOLD, threshold).apply()
        Log.d(TAG, "Updated low-battery threshold to $threshold%")
    }

    /**
     * Checks if a low-battery alert was already sent for the given SOS session.
     * Persisted in SharedPreferences so app restarts will NOT trigger duplicate alerts.
     */
    fun isAlertSentForSession(context: Context, sessionId: String): Boolean {
        if (sessionId.isEmpty()) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val recordedSession = prefs.getString(KEY_ALERT_SENT_SESSION, null)
        return recordedSession == sessionId
    }

    /**
     * Marks that a low-battery alert was successfully sent for the active SOS session.
     */
    fun markAlertSentForSession(context: Context, sessionId: String) {
        if (sessionId.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_ALERT_SENT_SESSION, sessionId)
            .putLong(KEY_ALERT_SENT_TIMESTAMP, System.currentTimeMillis())
            .apply()
        Log.d(TAG, "Marked low-battery alert as sent for session: $sessionId")
    }

    /**
     * Resets alert state when an SOS session ends so the next genuine session can alert.
     */
    fun resetAlertState(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_ALERT_SENT_SESSION)
            .remove(KEY_ALERT_SENT_TIMESTAMP)
            .apply()
        Log.d(TAG, "Reset low-battery alert state")
    }

    /**
     * Evaluates battery percentage and dispatches a low-battery emergency SMS if:
     * 1. An SOS session is currently active.
     * 2. Battery is at or below the configured threshold.
     * 3. An alert has not already been sent for this session.
     */
    fun evaluateAndSendAlert(context: Context, batteryLevel: Int, sessionId: String): Boolean {
        // Condition 1: SOS must be actively in progress
        if (!SosStateManager.isSosActive(context)) {
            Log.d(TAG, "Skipping battery evaluation: No SOS session is active")
            return false
        }

        if (sessionId.isEmpty()) {
            Log.d(TAG, "Skipping battery evaluation: Session ID is empty")
            return false
        }

        // Condition 2: Battery at or below threshold
        val threshold = getLowBatteryThreshold(context)
        if (batteryLevel > threshold) {
            return false
        }

        // Condition 3: Must not have already sent for this session
        if (isAlertSentForSession(context, sessionId)) {
            Log.d(TAG, "Low-battery alert already sent for session $sessionId. Skipping duplicate.")
            return false
        }

        // Check SMS permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Cannot send low-battery SMS alert: SEND_SMS permission not granted. Will retry if permission is granted.")
            return false
        }

        val contacts = ContactStorage.getContacts(context)
        if (contacts.isEmpty()) {
            Log.w(TAG, "Cannot send low-battery SMS alert: No emergency contacts saved")
            return false
        }

        val uniqueContacts = contacts.distinctBy { it.phone.replace(Regex("[^0-9+]"), "") }
        if (uniqueContacts.isEmpty()) {
            Log.w(TAG, "No valid emergency phone numbers found")
            return false
        }

        val cachedLoc = OfflineSosManager.getLastCachedLocation(context)
        val message = if (cachedLoc != null) {
            val sdf = SimpleDateFormat("hh:mm:ss a, MMM dd", Locale.getDefault())
            val timeStr = sdf.format(Date(cachedLoc.timestamp))
            """
🚨 EMERGENCY SOS: LOW BATTERY ALERT ⚠️

My phone battery is critically low at $batteryLevel%!
SOS Session: $sessionId

Last Known Location [Recorded at $timeStr]:
https://maps.google.com/?q=${cachedLoc.latitude},${cachedLoc.longitude}

Please send help immediately. My phone may power off soon.
            """.trimIndent()
        } else {
            """
🚨 EMERGENCY SOS: LOW BATTERY ALERT ⚠️

My phone battery is critically low at $batteryLevel%!
SOS Session: $sessionId

GPS location is currently unavailable.
Please send help immediately. My phone may power off soon.
            """.trimIndent()
        }

        val smsManager = SosStateManager.getSmsManager(context)
        val parts = smsManager.divideMessage(message)

        var sendSuccessCount = 0
        for (contact in uniqueContacts) {
            try {
                smsManager.sendMultipartTextMessage(contact.phone, null, parts, null, null)
                sendSuccessCount++
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException sending low-battery SMS: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send low-battery SMS: ${e.message}")
            }
        }

        if (sendSuccessCount > 0) {
            markAlertSentForSession(context, sessionId)
            Log.d(TAG, "Low-battery SMS alert sent to $sendSuccessCount contact(s) for session $sessionId ($batteryLevel%)")
            return true
        }

        return false
    }

    /**
     * Publishes latest battery status to Firebase without overwriting location or status fields.
     */
    fun publishBatteryToFirebase(userId: String, batteryLevel: Int) {
        if (userId.isEmpty()) return
        try {
            val updates = mapOf(
                "batteryLevel" to batteryLevel,
                "batteryTimestamp" to System.currentTimeMillis()
            )
            FirebaseDatabase.getInstance().reference
                .child("sos_locations")
                .child(userId)
                .updateChildren(updates)
                .addOnSuccessListener {
                    Log.d(TAG, "Battery level $batteryLevel% published to Firebase for $userId")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Failed to publish battery to Firebase: ${e.message}")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Exception publishing battery to Firebase: ${e.message}")
        }
    }
}
