package com.example.emergencysoscommunicationapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.storage.FirebaseStorage
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages the automatic, secure delivery of emergency audio recordings to guardians via Firebase Storage
 * and publishes delivery metadata to Firebase Realtime Database.
 * Handles offline pending queues, upload retry, and duplicate prevention.
 */
object AudioDeliveryManager {

    private const val TAG = "AudioDeliveryManager"
    const val ACTION_DELIVERY_STATE_CHANGED = "com.example.emergencysoscommunicationapp.ACTION_DELIVERY_STATE_CHANGED"

    enum class DeliveryState {
        IDLE,
        RECORDING,
        UPLOADING,
        SENT,
        PENDING,
        FAILED
    }

    private val PREFS_DELIVERY = "SOS_AUDIO_DELIVERY_PREFS"
    private val KEY_LAST_STATUS = "last_delivery_status"
    private val KEY_LAST_MESSAGE = "last_delivery_message"
    private val KEY_PENDING_FILE_PATH = "pending_file_path"
    private val KEY_PENDING_SESSION_ID = "pending_session_id"
    private val KEY_LAST_SENT_URL = "last_sent_url"

    @Volatile
    var currentState: DeliveryState = DeliveryState.IDLE
        private set

    @Volatile
    var lastStatusMessage: String = ""
        private set

    @Volatile
    var lastSentDownloadUrl: String? = null
        private set

    private val isUploading = AtomicBoolean(false)
    private val uploadedFiles = Collections.synchronizedSet(HashSet<String>())

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_DELIVERY, Context.MODE_PRIVATE)
        val savedStatus = prefs.getString(KEY_LAST_STATUS, DeliveryState.IDLE.name) ?: DeliveryState.IDLE.name
        currentState = try {
            DeliveryState.valueOf(savedStatus)
        } catch (e: Exception) {
            DeliveryState.IDLE
        }
        lastStatusMessage = prefs.getString(KEY_LAST_MESSAGE, "") ?: ""
        lastSentDownloadUrl = prefs.getString(KEY_LAST_SENT_URL, null)
    }

    fun setRecordingState(context: Context) {
        updateState(context, DeliveryState.RECORDING, "🎙️ Recording audio (up to 20s)...")
    }

    /**
     * Called when a recording has been finalized.
     * Attempts immediate upload if online, or queues locally as PENDING if offline.
     */
    fun deliverRecording(context: Context, file: File, sessionId: String) {
        if (!file.exists() || file.length() <= 0) {
            Log.w(TAG, "Cannot deliver empty or non-existent recording file: ${file.name}")
            updateState(context, DeliveryState.FAILED, "Recording file empty or corrupted.")
            return
        }

        if (uploadedFiles.contains(file.name)) {
            Log.d(TAG, "File ${file.name} was already uploaded. Skipping duplicate delivery.")
            return
        }

        val appContext = context.applicationContext
        val userId = SosStateManager.getUserId(appContext)

        // Save pending file info to disk so it survives app restarts
        savePendingFile(appContext, file.absolutePath, sessionId)

        if (!NetworkMonitor.isInternetAvailable(appContext)) {
            Log.w(TAG, "Device is offline. Queuing emergency audio recording as PENDING.")
            updateState(appContext, DeliveryState.PENDING, "⚠️ Audio saved locally (Offline • Pending upload)")
            return
        }

        uploadAudioFile(appContext, file, sessionId, userId)
    }

    private fun uploadAudioFile(context: Context, file: File, sessionId: String, userId: String) {
        if (isUploading.getAndSet(true)) {
            Log.w(TAG, "Upload already in progress. Queued for retry.")
            return
        }

        updateState(context, DeliveryState.UPLOADING, "⏳ Uploading recording to guardians...")

        try {
            val storage = FirebaseStorage.getInstance()
            val storageRef = storage.reference
                .child("emergency_recordings")
                .child(userId)
                .child(sessionId)
                .child(file.name)

            val fileUri = Uri.fromFile(file)
            val uploadTask = storageRef.putFile(fileUri)

            uploadTask.addOnProgressListener { taskSnapshot ->
                val progress = if (taskSnapshot.totalByteCount > 0) {
                    (100.0 * taskSnapshot.bytesTransferred / taskSnapshot.totalByteCount).toInt()
                } else 0
                lastStatusMessage = "⏳ Uploading recording ($progress%)..."
                broadcastStateChange(context)
            }.addOnSuccessListener {
                Log.d(TAG, "Firebase Storage upload succeeded for ${file.name}. Retrieving download URL...")
                storageRef.downloadUrl.addOnSuccessListener { downloadUrl ->
                    publishAudioMetadata(context, file, sessionId, userId, storageRef.path, downloadUrl.toString())
                }.addOnFailureListener { e ->
                    Log.w(TAG, "Failed to get download URL, using storage path instead: ${e.message}")
                    publishAudioMetadata(context, file, sessionId, userId, storageRef.path, null)
                }
            }.addOnFailureListener { e ->
                isUploading.set(false)
                Log.e(TAG, "Firebase Storage upload failed: ${e.message}", e)
                updateState(
                    context,
                    DeliveryState.FAILED,
                    "❌ Upload failed (${e.localizedMessage ?: "Network/Storage error"}) • Tap to retry"
                )
            }
        } catch (e: Exception) {
            isUploading.set(false)
            Log.e(TAG, "Exception during upload setup: ${e.message}", e)
            updateState(context, DeliveryState.FAILED, "❌ Upload error • Tap to retry")
        }
    }

    private fun publishAudioMetadata(
        context: Context,
        file: File,
        sessionId: String,
        userId: String,
        storagePath: String,
        downloadUrl: String?
    ) {
        val durationMs = AudioRecordingManager.getAudioDurationMs(file)
        val timestamp = System.currentTimeMillis()

        val audioData = mutableMapOf<String, Any>(
            "status" to "SENT",
            "sessionId" to sessionId,
            "filename" to file.name,
            "storagePath" to storagePath,
            "timestamp" to timestamp,
            "durationMs" to durationMs,
            "fileSizeBytes" to file.length()
        )
        if (downloadUrl != null) {
            audioData["downloadUrl"] = downloadUrl
        }

        try {
            val dbRef = FirebaseDatabase.getInstance().reference
                .child("sos_locations")
                .child(userId)
                .child("audioRecording")

            dbRef.setValue(audioData)
                .addOnSuccessListener {
                    isUploading.set(false)
                    uploadedFiles.add(file.name)
                    clearPendingFile(context)
                    lastSentDownloadUrl = downloadUrl
                    context.getSharedPreferences(PREFS_DELIVERY, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_LAST_SENT_URL, downloadUrl)
                        .apply()

                    Log.d(TAG, "Emergency audio recording delivered successfully: ${file.name}")
                    updateState(context, DeliveryState.SENT, "✓ Recording Delivered to Guardians")
                }
                .addOnFailureListener { e ->
                    isUploading.set(false)
                    Log.e(TAG, "Failed to publish audio metadata to Realtime Database: ${e.message}")
                    updateState(context, DeliveryState.FAILED, "❌ Metadata sync failed • Tap to retry")
                }
        } catch (e: Exception) {
            isUploading.set(false)
            Log.e(TAG, "Exception publishing audio metadata: ${e.message}")
            updateState(context, DeliveryState.FAILED, "❌ Sync error • Tap to retry")
        }
    }

    /**
     * Retries any pending or failed upload, e.g. when network reconnects or user taps retry.
     */
    fun retryPendingUpload(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_DELIVERY, Context.MODE_PRIVATE)
        val pendingPath = prefs.getString(KEY_PENDING_FILE_PATH, null) ?: return
        val pendingSession = prefs.getString(KEY_PENDING_SESSION_ID, "") ?: ""

        val file = File(pendingPath)
        if (file.exists() && file.length() > 0) {
            Log.d(TAG, "Retrying delivery of pending recording: ${file.name}")
            deliverRecording(appContext, file, pendingSession)
        } else {
            clearPendingFile(appContext)
            updateState(appContext, DeliveryState.IDLE, "")
        }
    }

    fun setMicrophoneDeniedState(context: Context) {
        updateState(context, DeliveryState.FAILED, "⚠️ Mic Permission Denied • Audio Not Recorded")
    }

    fun setRecordingFailedState(context: Context, errorReason: String) {
        updateState(context, DeliveryState.FAILED, "⚠️ Recording Failed: $errorReason")
    }

    fun resetState(context: Context) {
        updateState(context, DeliveryState.IDLE, "")
    }

    private fun updateState(context: Context, state: DeliveryState, message: String) {
        currentState = state
        lastStatusMessage = message

        context.getSharedPreferences(PREFS_DELIVERY, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_STATUS, state.name)
            .putString(KEY_LAST_MESSAGE, message)
            .apply()

        broadcastStateChange(context)
    }

    private fun broadcastStateChange(context: Context) {
        val intent = Intent(ACTION_DELIVERY_STATE_CHANGED).apply {
            setPackage(context.packageName)
            putExtra("state", currentState.name)
            putExtra("message", lastStatusMessage)
        }
        context.sendBroadcast(intent)
    }

    private fun savePendingFile(context: Context, path: String, sessionId: String) {
        context.getSharedPreferences(PREFS_DELIVERY, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_FILE_PATH, path)
            .putString(KEY_PENDING_SESSION_ID, sessionId)
            .apply()
    }

    private fun clearPendingFile(context: Context) {
        context.getSharedPreferences(PREFS_DELIVERY, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PENDING_FILE_PATH)
            .remove(KEY_PENDING_SESSION_ID)
            .apply()
    }
}
