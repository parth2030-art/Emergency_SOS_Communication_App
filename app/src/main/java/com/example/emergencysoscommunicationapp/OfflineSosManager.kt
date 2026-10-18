package com.example.emergencysoscommunicationapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages cached location tracking, offline fallback SMS policy, and rate-limiting.
 * Ensures emergency contacts receive updates when data connectivity fails,
 * while strictly avoiding SMS spam and duplicate alerts.
 */
object OfflineSosManager {

    private const val TAG = "OfflineSosManager"
    private const val PREFS_OFFLINE = "OFFLINE_SOS_PREFS"

    private const val KEY_CACHED_LAT = "cached_latitude"
    private const val KEY_CACHED_LNG = "cached_longitude"
    private const val KEY_CACHED_TIME = "cached_timestamp"

    private const val KEY_FALLBACK_SESSION = "fallback_session_id"
    private const val KEY_FALLBACK_COUNT = "fallback_sms_count"
    private const val KEY_LAST_FALLBACK_TIME = "last_fallback_time"

    // Throttling constants: max 3 fallback SMS per SOS session, minimum 3 minutes between sends
    private const val MAX_FALLBACKS_PER_SESSION = 3
    private const val MIN_FALLBACK_INTERVAL_MS = 180_000L // 3 minutes

    data class CachedLocation(
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long
    )

    /**
     * Caches the latest valid location coordinates and timestamp.
     */
    fun cacheLocation(context: Context, latitude: Double, longitude: Double, timestamp: Long = System.currentTimeMillis()) {
        if (!latitude.isFinite() || !longitude.isFinite()) return
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return

        try {
            context.getSharedPreferences(PREFS_OFFLINE, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CACHED_LAT, latitude.toString())
                .putString(KEY_CACHED_LNG, longitude.toString())
                .putLong(KEY_CACHED_TIME, timestamp)
                .apply()
            Log.d(TAG, "Cached latest location: lat=$latitude, lng=$longitude, time=$timestamp")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache location: ${e.message}")
        }
    }

    /**
     * Returns the latest valid cached location, if available.
     */
    fun getLastCachedLocation(context: Context): CachedLocation? {
        val prefs = context.getSharedPreferences(PREFS_OFFLINE, Context.MODE_PRIVATE)
        val latStr = prefs.getString(KEY_CACHED_LAT, null) ?: return null
        val lngStr = prefs.getString(KEY_CACHED_LNG, null) ?: return null
        val time = prefs.getLong(KEY_CACHED_TIME, 0L)

        val lat = latStr.toDoubleOrNull() ?: return null
        val lng = lngStr.toDoubleOrNull() ?: return null

        return CachedLocation(lat, lng, time)
    }

    /**
     * Evaluates whether an offline fallback SMS can be sent under current rate-limiting policies.
     */
    fun canSendFallbackSms(context: Context, currentSessionId: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_OFFLINE, Context.MODE_PRIVATE)
        val recordedSession = prefs.getString(KEY_FALLBACK_SESSION, "") ?: ""
        var count = prefs.getInt(KEY_FALLBACK_COUNT, 0)
        val lastTime = prefs.getLong(KEY_LAST_FALLBACK_TIME, 0L)
        val now = System.currentTimeMillis()

        // Reset counters if a new SOS session began
        if (recordedSession != currentSessionId) {
            count = 0
            prefs.edit()
                .putString(KEY_FALLBACK_SESSION, currentSessionId)
                .putInt(KEY_FALLBACK_COUNT, 0)
                .putLong(KEY_LAST_FALLBACK_TIME, 0L)
                .apply()
        }

        if (count >= MAX_FALLBACKS_PER_SESSION) {
            Log.d(TAG, "Fallback SMS limit reached for session ($count/$MAX_FALLBACKS_PER_SESSION)")
            return false
        }

        if (now - lastTime < MIN_FALLBACK_INTERVAL_MS) {
            val waitSec = (MIN_FALLBACK_INTERVAL_MS - (now - lastTime)) / 1000
            Log.d(TAG, "Fallback SMS throttled. Wait ${waitSec}s before next attempt.")
            return false
        }

        return true
    }

    /**
     * Dispatches an offline fallback SMS to saved emergency contacts when internet/Firebase delivery fails.
     * Includes cached GPS coordinates and timestamp, or indicates unavailable location without inventing data.
     */
    fun sendOfflineFallbackSms(context: Context, currentSessionId: String): Boolean {
        if (!SosStateManager.isSosActive(context)) {
            Log.d(TAG, "Skipping offline fallback SMS: SOS is not active")
            return false
        }

        if (!canSendFallbackSms(context, currentSessionId)) {
            return false
        }

        // Verify SEND_SMS permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Cannot send offline fallback SMS: SEND_SMS permission not granted")
            return false
        }

        val contacts = ContactStorage.getContacts(context)
        if (contacts.isEmpty()) {
            Log.w(TAG, "Cannot send offline fallback SMS: No emergency contacts saved")
            return false
        }

        val uniqueContacts = contacts.distinctBy { it.phone.replace(Regex("[^0-9+]"), "") }
        if (uniqueContacts.isEmpty()) {
            Log.w(TAG, "No valid emergency phone numbers found")
            return false
        }

        val settingsPrefs = context.getSharedPreferences("SOS_SETTINGS", Context.MODE_PRIVATE)
        val customMsg = settingsPrefs.getString("custom_msg", "I need help immediately.") ?: "I need help immediately."

        val cachedLoc = getLastCachedLocation(context)
        val message = if (cachedLoc != null) {
            val sdf = SimpleDateFormat("hh:mm:ss a, MMM dd", Locale.getDefault())
            val timeStr = sdf.format(Date(cachedLoc.timestamp))
            """
🚨 EMERGENCY SOS (OFFLINE ALERT) 🚨

$customMsg

Last Known Location [Cached at $timeStr]:
https://maps.google.com/?q=${cachedLoc.latitude},${cachedLoc.longitude}

Note: Device is currently offline. Live tracking will resume when online.
            """.trimIndent()
        } else {
            """
🚨 EMERGENCY SOS (OFFLINE ALERT) 🚨

$customMsg

Note: Device is currently offline and GPS coordinates are unavailable.
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
                Log.e(TAG, "SecurityException sending offline fallback SMS: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send offline fallback SMS: ${e.message}")
            }
        }

        if (sendSuccessCount > 0) {
            val prefs = context.getSharedPreferences(PREFS_OFFLINE, Context.MODE_PRIVATE)
            val currentCount = prefs.getInt(KEY_FALLBACK_COUNT, 0)
            prefs.edit()
                .putString(KEY_FALLBACK_SESSION, currentSessionId)
                .putInt(KEY_FALLBACK_COUNT, currentCount + 1)
                .putLong(KEY_LAST_FALLBACK_TIME, System.currentTimeMillis())
                .apply()

            Log.d(TAG, "Offline fallback SMS sent to $sendSuccessCount contact(s) (Attempt ${currentCount + 1}/$MAX_FALLBACKS_PER_SESSION)")
            return true
        }

        return false
    }

    /**
     * Resets rate-limiting state when an SOS session ends.
     */
    fun resetFallbackState(context: Context) {
        try {
            context.getSharedPreferences(PREFS_OFFLINE, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_FALLBACK_SESSION)
                .remove(KEY_FALLBACK_COUNT)
                .remove(KEY_LAST_FALLBACK_TIME)
                .apply()
            Log.d(TAG, "Offline SOS fallback state reset")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset fallback state: ${e.message}")
        }
    }
}
