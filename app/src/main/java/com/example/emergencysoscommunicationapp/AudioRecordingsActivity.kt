package com.example.emergencysoscommunicationapp

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * Screen providing accessible recording controls (Start/Stop),
 * playback with pause/resume, and safe deletion confirmation for emergency audio clips.
 */
class AudioRecordingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AudioRecordingsActivity"
        private const val PERMISSION_REQUEST_RECORD_AUDIO = 201
    }

    private lateinit var btnStartRecord: MaterialButton
    private lateinit var btnStopRecord: MaterialButton
    private lateinit var txtLiveStatus: TextView
    private lateinit var txtLiveTimer: TextView
    private lateinit var imgLiveDot: ImageView
    private lateinit var rvRecordings: RecyclerView
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var txtRecordingsCount: TextView

    private lateinit var adapter: AudioRecordingsAdapter
    private var mediaPlayer: MediaPlayer? = null
    private var currentPlayingFile: File? = null

    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (AudioRecordingManager.isRecording) {
                val elapsed = System.currentTimeMillis() - AudioRecordingManager.recordingStartTime
                txtLiveTimer.text = AudioRecordingManager.formatDuration(elapsed)
                timerHandler.postDelayed(this, 1000)
            }
        }
    }

    private val recordingStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioRecordingManager.ACTION_RECORDING_STATE_CHANGED) {
                updateRecordingUI()
                refreshRecordingsList()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio_recordings)

        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        btnBack.setOnClickListener {
            finish()
        }

        btnStartRecord = findViewById(R.id.btnStartRecord)
        btnStopRecord = findViewById(R.id.btnStopRecord)
        txtLiveStatus = findViewById(R.id.txtLiveStatus)
        txtLiveTimer = findViewById(R.id.txtLiveTimer)
        imgLiveDot = findViewById(R.id.imgLiveDot)
        rvRecordings = findViewById(R.id.rvRecordings)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        txtRecordingsCount = findViewById(R.id.txtRecordingsCount)

        setupRecyclerView()

        btnStartRecord.setOnClickListener {
            checkPermissionAndStartRecording()
        }

        btnStopRecord.setOnClickListener {
            stopActiveRecording()
        }

        updateRecordingUI()
        refreshRecordingsList()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(AudioRecordingManager.ACTION_RECORDING_STATE_CHANGED)
        ContextCompat.registerReceiver(
            this,
            recordingStateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        updateRecordingUI()
        refreshRecordingsList()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(recordingStateReceiver)
        } catch (e: Exception) {
            // Ignore
        }
        stopPlayback()
        timerHandler.removeCallbacks(timerRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseMediaPlayer()
    }

    private fun setupRecyclerView() {
        adapter = AudioRecordingsAdapter(
            items = emptyList(),
            onPlayClicked = { item, isCurrentlyPlaying ->
                if (isCurrentlyPlaying) {
                    stopPlayback()
                } else {
                    startPlayback(item.file)
                }
            },
            onDeleteClicked = { item ->
                confirmAndDeleteRecording(item)
            }
        )
        rvRecordings.layoutManager = LinearLayoutManager(this)
        rvRecordings.adapter = adapter
    }

    private fun checkPermissionAndStartRecording() {
        val permissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (permissionGranted) {
            val sessionId = SosStateManager.getActiveSessionId(this)
            val result = AudioRecordingManager.startRecording(this, sessionId)
            if (result.isSuccess) {
                if (!SosTrackingService.isServiceRunning) {
                    val serviceIntent = Intent(this, SosTrackingService::class.java).apply {
                        action = SosTrackingService.ACTION_START_RECORDING
                    }
                    ContextCompat.startForegroundService(this, serviceIntent)
                }
                updateRecordingUI()
                Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Failed to start recording"
                Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
            }
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle("Microphone Permission Required")
                .setMessage("Emergency SOS needs microphone access to capture audio evidence during emergencies. Recordings are stored privately on your device.")
                .setPositiveButton("Grant Permission") { _, _ ->
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.RECORD_AUDIO),
                        PERMISSION_REQUEST_RECORD_AUDIO
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkPermissionAndStartRecording()
            } else {
                Toast.makeText(this, "Microphone permission denied. Cannot record audio.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopActiveRecording() {
        val savedFile = AudioRecordingManager.stopRecording(this)
        timerHandler.removeCallbacks(timerRunnable)
        updateRecordingUI()
        refreshRecordingsList()

        if (savedFile != null) {
            Toast.makeText(this, "Recording saved: ${savedFile.name}", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Recording stopped (no audio data)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateRecordingUI() {
        if (AudioRecordingManager.isRecording) {
            txtLiveStatus.text = "🔴 Recording Emergency Audio (Live)"
            txtLiveStatus.setTextColor(ContextCompat.getColor(this, R.color.error_red))
            imgLiveDot.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.error_red)
            )
            btnStartRecord.visibility = View.GONE
            btnStopRecord.visibility = View.VISIBLE

            timerHandler.removeCallbacks(timerRunnable)
            timerHandler.post(timerRunnable)
        } else {
            txtLiveStatus.text = "Microphone Ready"
            txtLiveStatus.setTextColor(ContextCompat.getColor(this, R.color.dark_gray))
            imgLiveDot.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.medium_gray)
            )
            txtLiveTimer.text = "00:00"
            btnStartRecord.visibility = View.VISIBLE
            btnStopRecord.visibility = View.GONE

            timerHandler.removeCallbacks(timerRunnable)
        }
    }

    private fun refreshRecordingsList() {
        val recordings = AudioRecordingManager.getSavedRecordings(this)
        adapter.updateItems(recordings)
        txtRecordingsCount.text = "${recordings.size} file(s)"

        if (recordings.isEmpty()) {
            layoutEmpty.visibility = View.VISIBLE
            rvRecordings.visibility = View.GONE
        } else {
            layoutEmpty.visibility = View.GONE
            rvRecordings.visibility = View.VISIBLE
        }
    }

    private fun startPlayback(file: File) {
        if (!file.exists() || file.length() <= 0) {
            Toast.makeText(this, "Recording file not found or corrupted", Toast.LENGTH_SHORT).show()
            return
        }

        stopPlayback()

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    stopPlayback()
                }
            }
            currentPlayingFile = file
            adapter.setPlayingPath(file.absolutePath)
            Log.d(TAG, "Started playback of ${file.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio playback: ${e.message}")
            Toast.makeText(this, "Unable to play audio: ${e.message}", Toast.LENGTH_SHORT).show()
            stopPlayback()
        }
    }

    private fun stopPlayback() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.reset()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping MediaPlayer: ${e.message}")
        }
        currentPlayingFile = null
        adapter.setPlayingPath(null)
    }

    private fun releaseMediaPlayer() {
        stopPlayback()
        try {
            mediaPlayer?.release()
        } catch (e: Exception) {
            // Ignore
        }
        mediaPlayer = null
    }

    private fun confirmAndDeleteRecording(item: AudioRecordingManager.AudioRecordingItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Recording?")
            .setMessage("Are you sure you want to delete this emergency recording?\n\nFile: ${item.filename}\nRecorded: ${item.formattedDate}\n\nThis action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                if (currentPlayingFile?.absolutePath == item.file.absolutePath) {
                    stopPlayback()
                }
                val success = AudioRecordingManager.deleteRecording(item.file)
                if (success) {
                    refreshRecordingsList()
                    Toast.makeText(this, "Recording deleted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Failed to delete recording", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
