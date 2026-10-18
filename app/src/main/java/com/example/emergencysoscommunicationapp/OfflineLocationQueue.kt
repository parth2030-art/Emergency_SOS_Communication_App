package com.example.emergencysoscommunicationapp

import android.content.Context
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Lightweight local queue for unsynced location updates during network or Firebase downtime.
 * Persists data locally in SharedPreferences using Gson without requiring Room or heavy databases.
 * Guarantees that older locations never overwrite newer state or reactivate a stopped SOS.
 */
object OfflineLocationQueue {

    private const val TAG = "OfflineLocationQueue"
    private const val PREF_NAME = "OFFLINE_LOCATION_QUEUE_PREFS"
    private const val KEY_QUEUED_LOCATIONS = "queued_locations"
    private const val MAX_QUEUE_SIZE = 50

    data class QueuedLocation(
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long,
        val status: String,
        val sessionId: String,
        val senderUserId: String,
        val batteryLevel: Int? = null
    )

    @Synchronized
    fun enqueueLocation(context: Context, location: QueuedLocation) {
        try {
            val list = getQueuedLocations(context).toMutableList()
            list.add(location)

            // Keep only the most recent MAX_QUEUE_SIZE records to prevent unbounded growth
            val trimmedList = if (list.size > MAX_QUEUE_SIZE) {
                list.takeLast(MAX_QUEUE_SIZE)
            } else {
                list
            }

            val json = Gson().toJson(trimmedList)
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_QUEUED_LOCATIONS, json)
                .apply()

            Log.d(TAG, "Enqueued location update (queue size: ${trimmedList.size})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue location: ${e.message}")
        }
    }

    @Synchronized
    fun getQueuedLocations(context: Context): List<QueuedLocation> {
        return try {
            val json = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_QUEUED_LOCATIONS, null) ?: return emptyList()
            val type = object : TypeToken<List<QueuedLocation>>() {}.type
            Gson().fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read queued locations: ${e.message}")
            emptyList()
        }
    }

    @Synchronized
    fun hasQueuedLocations(context: Context): Boolean {
        return getQueuedLocations(context).isNotEmpty()
    }

    @Synchronized
    fun clearQueue(context: Context) {
        try {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_QUEUED_LOCATIONS)
                .apply()
            Log.d(TAG, "Offline location queue cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear queue: ${e.message}")
        }
    }

    /**
     * Attempts to flush the latest pending queued location to Firebase.
     * Enforces safety invariants:
     * 1. Never reactivates a stopped SOS session just because the device reconnects.
     * 2. Clears the queue only after Firebase confirms successful write via addOnSuccessListener.
     */
    @Synchronized
    fun syncQueueToFirebase(
        context: Context,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val queuedList = getQueuedLocations(context)
        if (queuedList.isEmpty()) {
            onComplete?.invoke(true)
            return
        }

        if (!NetworkMonitor.isInternetAvailable(context)) {
            Log.d(TAG, "Cannot sync offline queue: Internet unavailable")
            onComplete?.invoke(false)
            return
        }

        // Identify the most recent location record by timestamp
        val latest = queuedList.maxByOrNull { it.timestamp } ?: run {
            onComplete?.invoke(true)
            return
        }

        val isSosCurrentlyActive = SosStateManager.isSosActive(context)
        val activeUserId = SosStateManager.getUserId(context)
        val targetUserId = if (latest.senderUserId.isNotEmpty()) latest.senderUserId else activeUserId

        // Safety invariant: If SOS has already been stopped, do NOT post SOS_ACTIVE to Firebase
        val resolvedStatus = if (isSosCurrentlyActive) {
            latest.status
        } else {
            "SOS_STOPPED"
        }

        val locationData = mutableMapOf<String, Any>(
            "latitude" to latest.latitude,
            "longitude" to latest.longitude,
            "time" to latest.timestamp,
            "timestamp" to latest.timestamp,
            "status" to resolvedStatus,
            "sessionId" to if (isSosCurrentlyActive) latest.sessionId else "",
            "senderUserId" to targetUserId
        )
        if (latest.batteryLevel != null) {
            locationData["batteryLevel"] = latest.batteryLevel
            locationData["batteryTimestamp"] = latest.timestamp
        }

        Log.d(TAG, "Attempting to sync latest queued location to Firebase for $targetUserId (status=$resolvedStatus)")

        try {
            FirebaseDatabase.getInstance().reference
                .child("sos_locations")
                .child(targetUserId)
                .setValue(locationData)
                .addOnSuccessListener {
                    Log.d(TAG, "Successfully synced queued location to Firebase. Clearing queue.")
                    clearQueue(context)
                    onComplete?.invoke(true)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Failed to sync queued location to Firebase: ${e.message}. Keeping queue for retry.")
                    onComplete?.invoke(false)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Exception syncing queue to Firebase: ${e.message}")
            onComplete?.invoke(false)
        }
    }
}
