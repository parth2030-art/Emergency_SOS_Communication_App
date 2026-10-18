package com.example.emergencysoscommunicationapp

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import com.google.firebase.database.FirebaseDatabase

/**
 * Centralized SOS State and Configuration Manager.
 * Ensures consistent SOS active state, session IDs, and user ID management
 * across all screens (HomeActivity, LocationActivity, Services, and Receivers).
 */
object SosStateManager {

    const val PREFS_SOS_STATE = "SOS_STATE_PREFS"
    const val KEY_IS_SOS_ACTIVE = "is_sos_active"
    const val KEY_SESSION_ID = "active_session_id"
    const val KEY_USER_ID = "active_user_id"
    const val DEFAULT_USER_ID = "user_1"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_SOS_STATE, Context.MODE_PRIVATE)
    }

    fun isSosActive(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_IS_SOS_ACTIVE, false)
    }

    fun getActiveSessionId(context: Context): String {
        return getPrefs(context).getString(KEY_SESSION_ID, "") ?: ""
    }

    fun getUserId(context: Context): String {
        return getPrefs(context).getString(KEY_USER_ID, DEFAULT_USER_ID) ?: DEFAULT_USER_ID
    }

    fun setCustomUserId(context: Context, userId: String) {
        val sanitized = userId.trim()
        if (sanitized.isNotEmpty()) {
            getPrefs(context).edit().putString(KEY_USER_ID, sanitized).apply()
        }
    }

    fun setSosActive(context: Context, sessionId: String, userId: String = DEFAULT_USER_ID) {
        getPrefs(context).edit()
            .putBoolean(KEY_IS_SOS_ACTIVE, true)
            .putString(KEY_SESSION_ID, sessionId)
            .putString(KEY_USER_ID, userId)
            .apply()
    }

    fun setSosInactive(context: Context) {
        getPrefs(context).edit()
            .putBoolean(KEY_IS_SOS_ACTIVE, false)
            .remove(KEY_SESSION_ID)
            .apply()
    }

    fun updateFirebaseStatus(status: String, userId: String = DEFAULT_USER_ID) {
        try {
            val database = FirebaseDatabase.getInstance().reference
            database.child("sos_locations")
                .child(userId)
                .child("status")
                .setValue(status)
                .addOnSuccessListener {
                    Log.d("SosStateManager", "Firebase status updated to $status for user $userId")
                }
                .addOnFailureListener { e ->
                    Log.e("SosStateManager", "Failed to update Firebase status: ${e.message}")
                }
        } catch (e: Exception) {
            Log.e("SosStateManager", "Exception updating Firebase status: ${e.message}")
        }
    }

    const val ACTION_SOS_STOPPED = "com.example.emergencysoscommunicationapp.ACTION_SOS_STOPPED"

    fun startSosService(context: Context, sessionId: String, userId: String = DEFAULT_USER_ID) {
        val intent = android.content.Intent(context, SosTrackingService::class.java).apply {
            action = SosTrackingService.ACTION_START_SOS
            putExtra("sessionId", sessionId)
            putExtra("userId", userId)
        }
        try {
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
            Log.d("SosStateManager", "startSosService initiated for $userId, session $sessionId")
        } catch (e: Exception) {
            Log.e("SosStateManager", "Failed to start SosTrackingService: ${e.message}")
        }
    }

    fun stopSosService(context: Context) {
        val intent = android.content.Intent(context, SosTrackingService::class.java).apply {
            action = SosTrackingService.ACTION_STOP_SOS
        }
        try {
            context.startService(intent)
            Log.d("SosStateManager", "stopSosService initiated")
        } catch (e: Exception) {
            Log.e("SosStateManager", "Failed to stop SosTrackingService: ${e.message}")
        }
    }

    fun getSmsManager(context: Context): SmsManager {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }
}
