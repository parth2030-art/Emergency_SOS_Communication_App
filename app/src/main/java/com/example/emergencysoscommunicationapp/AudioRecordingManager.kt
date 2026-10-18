package com.example.emergencysoscommunicationapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Singleton manager for emergency audio recording and local file storage.
 * Operates strictly with app-private internal storage, uses standard MediaRecorder (MPEG-4/AAC),
 * provides safe resource management, 20-second maximum duration timeout, and automatic delivery integration.
 */
object AudioRecordingManager {

    private const val TAG = "AudioRecordingManager"
    const val ACTION_RECORDING_STATE_CHANGED = "com.example.emergencysoscommunicationapp.ACTION_RECORDING_STATE_CHANGED"
    const val MAX_RECORDING_DURATION_MS = 20_000L // 20 Seconds Max
    private const val MIN_STORAGE_REQUIRED_BYTES = 2 * 1024 * 1024L // 2 MB

    data class AudioRecordingItem(
        val file: File,
        val filename: String,
        val timestamp: Long,
        val formattedDate: String,
        val durationMs: Long,
        val formattedDuration: String,
        val formattedSize: String,
        val sessionId: String
    )

    @Volatile
    var isRecording: Boolean = false
        private set

    var currentRecordingFile: File? = null
        private set

    var currentRecordingSessionId: String = ""
        private set

    var recordingStartTime: Long = 0L
        private set

    private var mediaRecorder: MediaRecorder? = null
    private val autoStopHandler = Handler(Looper.getMainLooper())
    private var autoStopRunnable: Runnable? = null

    /**
     * Directory in app-private internal storage dedicated to emergency recordings.
     */
    fun getRecordingsDir(context: Context): File {
        val dir = File(context.filesDir, "emergency_recordings")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Starts emergency audio recording into a timestamped file within app-private internal storage.
     * Automatically stops after 20 seconds unless stopped earlier.
     */
    @Synchronized
    fun startRecording(context: Context, sessionId: String = ""): Result<File> {
        if (isRecording) {
            Log.w(TAG, "Recording is already in progress.")
            return currentRecordingFile?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("Already recording"))
        }

        // Verify RECORD_AUDIO permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Cannot start recording: RECORD_AUDIO permission not granted")
            AudioDeliveryManager.setMicrophoneDeniedState(context)
            return Result.failure(SecurityException("Microphone permission required for audio recording"))
        }

        val dir = getRecordingsDir(context)
        if (dir.usableSpace < MIN_STORAGE_REQUIRED_BYTES) {
            Log.e(TAG, "Insufficient internal storage space to start recording (${dir.usableSpace} bytes available)")
            AudioDeliveryManager.setRecordingFailedState(context, "Insufficient storage")
            return Result.failure(Exception("Insufficient internal storage to record audio"))
        }

        val timestamp = System.currentTimeMillis()
        val safeSession = if (sessionId.isNotEmpty()) sessionId else "manual"
        val outputFile = File(dir, "SOS_AUDIO_${safeSession}_${timestamp}.m4a")

        return try {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64000)
                setAudioSamplingRate(44100)
                setMaxDuration(MAX_RECORDING_DURATION_MS.toInt())
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        Log.d(TAG, "MediaRecorder reached 20-second max duration limit. Finalizing...")
                        stopRecording(context)
                    }
                }
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
            currentRecordingFile = outputFile
            currentRecordingSessionId = safeSession
            recordingStartTime = timestamp
            isRecording = true

            // Schedule fallback auto-stop timer for exact 20 seconds
            autoStopRunnable = Runnable {
                if (isRecording) {
                    Log.d(TAG, "Fallback timeout handler reached 20 seconds. Finalizing recording...")
                    stopRecording(context)
                }
            }
            autoStopHandler.postDelayed(autoStopRunnable!!, MAX_RECORDING_DURATION_MS)

            AudioDeliveryManager.setRecordingState(context)
            Log.d(TAG, "Emergency audio recording started successfully: ${outputFile.name} (20s limit)")
            broadcastStateChange(context)
            Result.success(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaRecorder: ${e.message}", e)
            releaseRecorder()
            outputFile.delete()
            isRecording = false
            currentRecordingFile = null
            AudioDeliveryManager.setRecordingFailedState(context, e.message ?: "Recorder error")
            Result.failure(e)
        }
    }

    /**
     * Stops the active audio recording early or upon 20s timeout, finalizes the file,
     * releases recorder resources, and automatically triggers delivery.
     */
    @Synchronized
    fun stopRecording(context: Context): File? {
        autoStopRunnable?.let { autoStopHandler.removeCallbacks(it) }
        autoStopRunnable = null

        if (!isRecording && mediaRecorder == null) {
            Log.d(TAG, "stopRecording called but no recording is active")
            return null
        }

        val file = currentRecordingFile
        val safeSession = currentRecordingSessionId
        Log.d(TAG, "Stopping emergency audio recording early or at 20s...")

        try {
            mediaRecorder?.stop()
            Log.d(TAG, "MediaRecorder stopped successfully")
        } catch (e: RuntimeException) {
            Log.w(TAG, "MediaRecorder stop failed (possibly stopped before data captured): ${e.message}")
            file?.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Exception stopping MediaRecorder: ${e.message}")
        } finally {
            releaseRecorder()
            isRecording = false
            currentRecordingFile = null
            recordingStartTime = 0L
            broadcastStateChange(context)
        }

        return if (file != null && file.exists() && file.length() > 0) {
            Log.d(TAG, "Recording saved and finalized successfully: ${file.name} (${file.length()} bytes)")
            // Trigger automatic delivery to guardians
            AudioDeliveryManager.deliverRecording(context, file, safeSession)
            file
        } else {
            Log.w(TAG, "No valid recording file was saved.")
            AudioDeliveryManager.setRecordingFailedState(context, "No audio data saved")
            null
        }
    }

    private fun releaseRecorder() {
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MediaRecorder: ${e.message}")
        }
        mediaRecorder = null
    }

    private fun broadcastStateChange(context: Context) {
        val intent = Intent(ACTION_RECORDING_STATE_CHANGED).apply {
            setPackage(context.packageName)
            putExtra("isRecording", isRecording)
        }
        context.sendBroadcast(intent)
    }

    /**
     * Lists all saved emergency recordings from app-private storage, sorted newest first.
     */
    fun getSavedRecordings(context: Context): List<AudioRecordingItem> {
        val dir = getRecordingsDir(context)
        val files = dir.listFiles { f -> f.isFile && f.extension.equals("m4a", ignoreCase = true) }
            ?: return emptyList()

        val sdf = SimpleDateFormat("MMM dd, yyyy • hh:mm:ss a", Locale.getDefault())

        return files.mapNotNull { file ->
            if (file.length() <= 0) {
                file.delete()
                null
            } else {
                val timestamp = file.lastModified()
                val formattedDate = sdf.format(Date(timestamp))
                val durationMs = getAudioDurationMs(file)
                val formattedDuration = formatDuration(durationMs)
                val formattedSize = formatFileSize(file.length())

                // Parse session id from filename if present
                val parts = file.nameWithoutExtension.split("_")
                val sessionId = if (parts.size >= 3) parts[2] else "General"

                AudioRecordingItem(
                    file = file,
                    filename = file.name,
                    timestamp = timestamp,
                    formattedDate = formattedDate,
                    durationMs = durationMs,
                    formattedDuration = formattedDuration,
                    formattedSize = formattedSize,
                    sessionId = sessionId
                )
            }
        }.sortedByDescending { it.timestamp }
    }

    /**
     * Deletes a recording file strictly within app-private storage.
     */
    fun deleteRecording(file: File): Boolean {
        return try {
            if (file.exists()) {
                val deleted = file.delete()
                Log.d(TAG, "Deleted recording file: ${file.name} (success=$deleted)")
                deleted
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting recording file ${file.name}: ${e.message}")
            false
        }
    }

    @JvmStatic
    fun getAudioDurationMs(file: File): Long {
        return getAudioDurationMs(file.absolutePath)
    }

    @JvmStatic
    fun getAudioDurationMs(filePath: String): Long {
        if (filePath.isEmpty()) return 0L
        val file = File(filePath)
        if (!file.exists() || file.length() == 0L) return 0L

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(filePath)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationStr?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun formatDuration(durationMs: Long): String {
        val totalSec = durationMs / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.getDefault(), "%02d:%02d", min, sec)
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024f * 1024f))
            bytes >= 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024f)
            else -> "$bytes B"
        }
    }
}
