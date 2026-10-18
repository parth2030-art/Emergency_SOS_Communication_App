package com.example.emergencysoscommunicationapp

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telephony.SmsManager
import android.util.Log
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.ScaleAnimation
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.gms.location.*
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_SOS_STATE = "SOS_STATE_PREFS"
        private const val KEY_IS_SOS_ACTIVE = "is_sos_active"
        private const val KEY_SESSION_ID = "active_session_id"
    }

    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    @Volatile
    private var isSosActive = false
    @Volatile
    private var isSosStarting = false
    private var currentSessionId: String = ""

    private var mediaPlayer: MediaPlayer? = null

    private var sensorManager: SensorManager? = null
    private var shakeDetector: ShakeDetector? = null
    private var countdownDialog: AlertDialog? = null
    private var countdownTimer: CountDownTimer? = null
    private var isReceiverRegistered = false

    private val sosStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == SosStateManager.ACTION_SOS_STOPPED) {
                Log.d("HomeActivity", "Received ACTION_SOS_STOPPED broadcast. Restoring UI.")
                restoreSosStateUI()
                stopSosAlarm()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        val requiredPermissions = mutableListOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                100
            )
        }

        // Initialize shake detector
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        shakeDetector = ShakeDetector {
            runOnUiThread {
                handleShakeTrigger()
            }
        }

        // Register multi-device FCM Token in Firebase Realtime Database
        val userId = SosStateManager.getUserId(this)
        FcmTokenManager.registerDeviceToken(this, userId = userId, role = "victim")

        val btnSOS = findViewById<Button>(R.id.btnSOS)
        val btnContacts = findViewById<MaterialCardView>(R.id.btnContacts)
        val btnCall = findViewById<MaterialCardView>(R.id.btnCall)
        val btnSMS = findViewById<MaterialCardView>(R.id.btnSMS)
        val btnLocation = findViewById<MaterialCardView>(R.id.btnLocation)
        val btnStopSOS = findViewById<Button>(R.id.btnStopSOS)
        val btnSettings = findViewById<ImageButton>(R.id.btnSettings)

        btnSOS.setOnClickListener {
            Log.d("SOS_DEBUG", "SOS Button clicked by user")
            activateSosOnce()
        }

        btnSMS.setOnClickListener {
            startActivity(Intent(this, SMSActivity::class.java))
        }

        btnContacts.setOnClickListener {
            startActivity(Intent(this, ContactsActivity::class.java))
        }

        btnLocation.setOnClickListener {
            startActivity(Intent(this, LocationActivity::class.java))
        }

        btnCall.setOnClickListener {
            startActivity(Intent(this, CallContactsActivity::class.java))
        }

        btnStopSOS.setOnClickListener {
            stopLiveLocationTracking()
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        restoreSosStateUI()
        startPulseAnimation()
        updateLocationStatusUI()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            updateLocationStatusUI()
        }
    }

    override fun onResume() {
        super.onResume()
        restoreSosStateUI()
        startPulseAnimation()
        updateLocationStatusUI()

        // Register broadcast receiver to catch STOP SOS actions from the Foreground Service notification
        val filter = IntentFilter(SosStateManager.ACTION_SOS_STOPPED)
        try {
            ContextCompat.registerReceiver(
                this,
                sosStoppedReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            isReceiverRegistered = true
        } catch (e: Exception) {
            Log.e("HomeActivity", "Error registering sosStoppedReceiver: ${e.message}")
        }

        // If tracking was stopped from notification or LocationActivity, clean up siren
        if (!SosStateManager.isSosActive(this)) {
            stopSosAlarm()
        }

        // Register shake detector if enabled in settings
        val prefs = getSharedPreferences("SOS_SETTINGS", MODE_PRIVATE)
        val isShakeEnabled = prefs.getBoolean("shake_sos_enabled", false)
        val sensitivityStr = prefs.getString("shake_sensitivity", "Medium") ?: "Medium"
        shakeDetector?.setSensitivity(sensitivityStr)

        if (isShakeEnabled) {
            sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensor ->
                sensorManager?.registerListener(shakeDetector, sensor, SensorManager.SENSOR_DELAY_UI)
            }
        }

        // Sync pending offline locations if internet is available
        if (NetworkMonitor.isInternetAvailable(this) && OfflineLocationQueue.hasQueuedLocations(this)) {
            Log.d("HomeActivity", "Pending offline locations detected on resume. Syncing to Firebase.")
            OfflineLocationQueue.syncQueueToFirebase(this)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(shakeDetector)
        shakeDetector?.reset()

        countdownTimer?.cancel()
        countdownTimer = null
        if (countdownDialog?.isShowing == true) {
            countdownDialog?.dismiss()
        }
        countdownDialog = null

        if (isReceiverRegistered) {
            try {
                unregisterReceiver(sosStoppedReceiver)
                isReceiverRegistered = false
            } catch (e: Exception) {
                Log.w("HomeActivity", "sosStoppedReceiver was not registered: ${e.message}")
            }
        }
    }

    private fun restoreSosStateUI() {
        isSosActive = SosStateManager.isSosActive(this)
        currentSessionId = SosStateManager.getActiveSessionId(this)

        val btnSOS = findViewById<Button>(R.id.btnSOS)
        val btnStopSOS = findViewById<Button>(R.id.btnStopSOS)

        if (isSosActive) {
            btnStopSOS?.isVisible = true
            btnSOS?.isEnabled = false
        } else {
            btnStopSOS?.isVisible = false
            btnSOS?.isEnabled = true
        }
    }

    @Synchronized
    private fun activateSosOnce() {
        val activeInPrefs = SosStateManager.isSosActive(this)

        if (isSosActive || isSosStarting || activeInPrefs) {
            Log.w("SOS_DEBUG", "SOS Activation ignored: Already active or starting.")
            return
        }

        isSosStarting = true
        val btnSOS = findViewById<Button>(R.id.btnSOS)
        val btnStopSOS = findViewById<Button>(R.id.btnStopSOS)

        btnSOS?.isEnabled = false
        btnStopSOS?.isVisible = true

        currentSessionId = "sos_${System.currentTimeMillis()}"
        val userId = SosStateManager.getUserId(this)

        SosStateManager.setSosActive(this, currentSessionId, userId)

        isSosActive = true
        isSosStarting = false

        Log.d("SOS_DEBUG", "SOS session created: $currentSessionId for user $userId. Starting dispatch.")

        // 1. Send SMS to unique emergency contacts
        sendEmergencySmsOnce()

        // 2. Start local alarm sound ONCE on victim device
        startSosAlarmOnce()

        // 3. Start continuous location updates (uploads to Firebase, triggering Cloud Function for FCM notifications)
        startLiveLocationTracking()
    }

    private fun normalizePhone(phone: String): String {
        return phone.replace(Regex("[^0-9+]"), "")
    }

    private fun sendEmergencySmsOnce() {
        val contacts = ContactStorage.getContacts(this)

        if (contacts.isEmpty()) {
            Toast.makeText(this, "No Contacts Saved", Toast.LENGTH_SHORT).show()
            Log.w("SOS_NOTIFICATION", "No contacts available to send SMS")
            return
        }

        // Check SEND_SMS permission explicitly before proceeding
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "SMS Permission Required to Send SOS", Toast.LENGTH_LONG).show()
            Log.e("SOS_NOTIFICATION", "SEND_SMS permission not granted")
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Location Permission Required", Toast.LENGTH_SHORT).show()
            return
        }

        val uniqueContacts = contacts.distinctBy { normalizePhone(it.phone) }
        val userId = SosStateManager.getUserId(this)

        val dispatchSmsWithLocation = { location: android.location.Location? ->
            val prefs = getSharedPreferences("SOS_SETTINGS", MODE_PRIVATE)
            val customMsg = prefs.getString("custom_msg", "I need help immediately.") ?: "I need help immediately."

            val cached = OfflineSosManager.getLastCachedLocation(this)
            val message = if (location != null) {
                OfflineSosManager.cacheLocation(this, location.latitude, location.longitude)
                uploadLocationToFirebase(location.latitude, location.longitude)
                """
🚨 EMERGENCY SOS 🚨

$customMsg

Initial Location Pin:
https://maps.google.com/?q=${location.latitude},${location.longitude}

View Live Movement in App:
emergencysos://livetrack?senderUserId=$userId&sosSessionId=$currentSessionId
                """.trimIndent()
            } else if (cached != null) {
                val sdf = SimpleDateFormat("hh:mm:ss a, MMM dd", Locale.getDefault())
                val cachedTime = sdf.format(Date(cached.timestamp))
                """
🚨 EMERGENCY SOS 🚨

$customMsg

Last Known Location [Cached at $cachedTime]:
https://maps.google.com/?q=${cached.latitude},${cached.longitude}

View Live Movement in App:
emergencysos://livetrack?senderUserId=$userId&sosSessionId=$currentSessionId
                """.trimIndent()
            } else {
                """
🚨 EMERGENCY SOS 🚨

$customMsg

Location unavailable. View Live Track when online:
emergencysos://livetrack?senderUserId=$userId&sosSessionId=$currentSessionId
                """.trimIndent()
            }

            val smsManager = SosStateManager.getSmsManager(this)
            val parts = smsManager.divideMessage(message)
            for (contact in uniqueContacts) {
                try {
                    smsManager.sendMultipartTextMessage(contact.phone, null, parts, null, null)
                    Log.d("SOS_NOTIFICATION", "SMS sent successfully to unique contact: ${contact.phone}")
                } catch (e: SecurityException) {
                    Log.e("SOS_NOTIFICATION", "SecurityException sending SMS to ${contact.phone}: ${e.message}")
                    Toast.makeText(this, "SMS Permission Denied", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e("SOS_NOTIFICATION", "Failed to send SMS to ${contact.phone}: ${e.message}")
                }
            }

            Toast.makeText(this, "SOS Sent To ${uniqueContacts.size} Contact(s)", Toast.LENGTH_LONG).show()
        }

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                dispatchSmsWithLocation(location)
            }
            .addOnFailureListener { e ->
                Log.w("SOS_NOTIFICATION", "getCurrentLocation failed: ${e.message}. Falling back to cached/empty location.")
                dispatchSmsWithLocation(null)
            }
    }

    private fun startPulseAnimation() {
        val pulseRing = findViewById<View>(R.id.sosPulseRing) ?: return
        val scaleX = ScaleAnimation(
            1f, 1.25f, 1f, 1.25f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 1500
            repeatCount = Animation.INFINITE
            repeatMode = Animation.RESTART
        }
        val fade = AlphaAnimation(0.4f, 0f).apply {
            duration = 1500
            repeatCount = Animation.INFINITE
            repeatMode = Animation.RESTART
        }
        val animSet = AnimationSet(true).apply {
            addAnimation(scaleX)
            addAnimation(fade)
        }
        pulseRing.startAnimation(animSet)
    }

    private fun updateLocationStatusUI() {
        val txtLocationStatus = findViewById<TextView>(R.id.txtLocationStatus) ?: return
        val imgStatusDot = findViewById<ImageView>(R.id.imgStatusDot) ?: return

        val isGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (isGranted) {
            txtLocationStatus.text = "Location Enabled"
            imgStatusDot.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.success_green)
            )
        } else {
            txtLocationStatus.text = "Location Disabled"
            imgStatusDot.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.error_red)
            )
        }
    }

    private fun startSosAlarmOnce() {
        if (mediaPlayer?.isPlaying == true) {
            Log.d("SOS_SOUND", "Alarm already playing. Skipping duplicate start.")
            return
        }

        try {
            mediaPlayer = MediaPlayer.create(applicationContext, R.raw.alert).apply {
                isLooping = false
                start()
            }
            Log.d("SOS_SOUND", "Started local SOS alarm sound once on victim device.")
        } catch (e: Exception) {
            Log.e("SOS_SOUND", "Error starting SOS alarm sound: ${e.message}")
        }
    }

    private fun stopSosAlarm() {
        try {
            mediaPlayer?.run {
                if (isPlaying) stop()
                release()
            }
        } catch (e: Exception) {
            Log.e("SOS_SOUND", "Error stopping SOS alarm sound: ${e.message}")
        }
        mediaPlayer = null
        Log.d("SOS_SOUND", "Stopped SOS alarm sound.")
    }

    /**
     * Silent location upload to Firebase Realtime Database.
     * Triggers Firebase Cloud Function to send FCM notifications to guardians.
     */
    private fun uploadLocationToFirebase(latitude: Double, longitude: Double) {
        val database = FirebaseDatabase.getInstance().reference
        val timestamp = System.currentTimeMillis()
        val userId = SosStateManager.getUserId(this)

        val batteryLevel = BatteryAlertManager.getBatteryLevel(this)
        val locationData = mutableMapOf<String, Any>(
            "latitude" to latitude,
            "longitude" to longitude,
            "time" to timestamp,
            "timestamp" to timestamp,
            "status" to if (isSosActive) "SOS_ACTIVE" else "SOS_STOPPED",
            "sessionId" to currentSessionId,
            "senderUserId" to userId
        )
        if (batteryLevel != null) {
            locationData["batteryLevel"] = batteryLevel
            locationData["batteryTimestamp"] = timestamp
        }

        Log.d("SOS_FIREBASE", "Sender location update to path=sos_locations/$userId: lat=$latitude, lng=$longitude, status=${locationData["status"]}")

        database.child("sos_locations")
            .child(userId)
            .setValue(locationData)
            .addOnSuccessListener {
                Log.d("SOS_FIREBASE", "Firebase location update SUCCESS")
            }
            .addOnFailureListener { e ->
                Log.e("SOS_FIREBASE", "Firebase location update FAILED: ${e.message}. Enqueueing locally.")
                OfflineLocationQueue.enqueueLocation(
                    this,
                    OfflineLocationQueue.QueuedLocation(
                        latitude = latitude,
                        longitude = longitude,
                        timestamp = timestamp,
                        status = if (isSosActive) "SOS_ACTIVE" else "SOS_STOPPED",
                        sessionId = currentSessionId,
                        senderUserId = userId,
                        batteryLevel = batteryLevel
                    )
                )
            }
    }

    private fun startLiveLocationTracking() {
        val userId = SosStateManager.getUserId(this)
        SosStateManager.startSosService(this, currentSessionId, userId)
        Log.d("SOS_DEBUG", "Delegated live location tracking to SosTrackingService.")
    }

    private fun stopLiveLocationTracking() {
        SosStateManager.stopSosService(this)

        isSosActive = false
        isSosStarting = false

        stopSosAlarm()

        val userId = SosStateManager.getUserId(this)
        SosStateManager.setSosInactive(this)
        SosStateManager.updateFirebaseStatus("SOS_STOPPED", userId)

        restoreSosStateUI()

        Log.d("SOS_DEBUG", "SOS session stopped successfully.")
        Toast.makeText(this, "SOS Stopped", Toast.LENGTH_SHORT).show()
    }

    private fun vibrateForShakeAlert() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(500)
                }
            }
        } catch (e: Exception) {
            Log.w("HomeActivity", "Vibration error: ${e.message}")
        }
    }

    private fun handleShakeTrigger() {
        if (isSosActive || isSosStarting || SosStateManager.isSosActive(this)) {
            Log.d("HomeActivity", "Shake detected but SOS is already active or starting.")
            return
        }

        if (countdownDialog?.isShowing == true) {
            Log.d("HomeActivity", "Countdown dialog already showing.")
            return
        }

        vibrateForShakeAlert()

        var secondsRemaining = 5

        val dialogBuilder = MaterialAlertDialogBuilder(this)
            .setTitle("⚠️ Shake Detected: Emergency SOS")
            .setMessage("Emergency SOS will activate in $secondsRemaining seconds.\n\nCancel if this was an accidental trigger.")
            .setCancelable(false)
            .setNegativeButton("CANCEL (False Alarm)") { dialog, _ ->
                countdownTimer?.cancel()
                countdownTimer = null
                dialog.dismiss()
                Toast.makeText(this, "SOS Cancelled", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("ACTIVATE NOW") { dialog, _ ->
                countdownTimer?.cancel()
                countdownTimer = null
                dialog.dismiss()
                activateSosOnce()
            }

        countdownDialog = dialogBuilder.create()
        countdownDialog?.show()

        countdownTimer = object : CountDownTimer(5000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                secondsRemaining = (millisUntilFinished / 1000).toInt() + 1
                if (countdownDialog?.isShowing == true) {
                    countdownDialog?.setMessage("Emergency SOS will activate in $secondsRemaining seconds.\n\nCancel if this was an accidental trigger.")
                }
            }

            override fun onFinish() {
                if (countdownDialog?.isShowing == true) {
                    countdownDialog?.dismiss()
                }
                countdownDialog = null
                countdownTimer = null
                if (!isSosActive && !isSosStarting && !SosStateManager.isSosActive(this@HomeActivity)) {
                    activateSosOnce()
                }
            }
        }.start()
    }

    override fun onDestroy() {
        countdownTimer?.cancel()
        countdownTimer = null
        if (countdownDialog?.isShowing == true) {
            countdownDialog?.dismiss()
        }
        countdownDialog = null
        stopSosAlarm()
        super.onDestroy()
    }
}