package com.example.emergencysoscommunicationapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.gms.location.*
import com.google.android.material.card.MaterialCardView
import com.google.firebase.database.FirebaseDatabase

class HomeActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_SOS_STATE = "SOS_STATE_PREFS"
        private const val KEY_IS_SOS_ACTIVE = "is_sos_active"
        private const val KEY_SESSION_ID = "active_session_id"
    }

    private lateinit var locationCallback: LocationCallback

    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    @Volatile
    private var isSosActive = false
    @Volatile
    private var isSosStarting = false
    private var isLocationTrackingStarted = false
    private var currentSessionId: String = ""

    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                200
            )
        }

        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.SEND_SMS,
                Manifest.permission.ACCESS_FINE_LOCATION
            ),
            100
        )

        // Register multi-device FCM Token in Firebase Realtime Database under users/user_1/devices/{deviceId}
        FcmTokenManager.registerDeviceToken(this, userId = "user_1", role = "victim")

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

    override fun onResume() {
        super.onResume()
        restoreSosStateUI()
        startPulseAnimation()
        updateLocationStatusUI()
    }

    private fun restoreSosStateUI() {
        val prefs = getSharedPreferences(PREFS_SOS_STATE, MODE_PRIVATE)
        isSosActive = prefs.getBoolean(KEY_IS_SOS_ACTIVE, false)
        currentSessionId = prefs.getString(KEY_SESSION_ID, "") ?: ""

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
        val prefs = getSharedPreferences(PREFS_SOS_STATE, MODE_PRIVATE)
        val activeInPrefs = prefs.getBoolean(KEY_IS_SOS_ACTIVE, false)

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

        prefs.edit()
            .putBoolean(KEY_IS_SOS_ACTIVE, true)
            .putString(KEY_SESSION_ID, currentSessionId)
            .apply()

        isSosActive = true
        isSosStarting = false

        Log.d("SOS_DEBUG", "SOS session created: $currentSessionId. Starting dispatch.")

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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Location Permission Required", Toast.LENGTH_SHORT).show()
            return
        }

        val uniqueContacts = contacts.distinctBy { normalizePhone(it.phone) }

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                val prefs = getSharedPreferences("SOS_SETTINGS", MODE_PRIVATE)
                val customMsg = prefs.getString("custom_msg", "I need help immediately.") ?: "I need help immediately."

                val message = if (location != null) {
                    uploadLocationToFirebase(location.latitude, location.longitude)
                    """
🚨 EMERGENCY SOS 🚨

$customMsg

Initial Location Pin:
https://maps.google.com/?q=${location.latitude},${location.longitude}

View Live Movement in App:
emergencysos://livetrack?senderUserId=user_1&sosSessionId=$currentSessionId
                    """.trimIndent()
                } else {
                    """
🚨 EMERGENCY SOS 🚨

$customMsg

Location unavailable. View Live Track when online:
emergencysos://livetrack?senderUserId=user_1&sosSessionId=$currentSessionId
                    """.trimIndent()
                }

                val smsManager = SmsManager.getDefault()
                for (contact in uniqueContacts) {
                    try {
                        val parts = smsManager.divideMessage(message)
                        smsManager.sendMultipartTextMessage(contact.phone, null, parts, null, null)
                        Log.d("SOS_NOTIFICATION", "SMS sent successfully to unique contact: ${contact.phone}")
                    } catch (e: Exception) {
                        Log.e("SOS_NOTIFICATION", "Failed to send SMS to ${contact.phone}: ${e.message}")
                    }
                }

                Toast.makeText(this, "SOS Sent To ${uniqueContacts.size} Contact(s)", Toast.LENGTH_LONG).show()
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

        val locationData = mapOf(
            "latitude" to latitude,
            "longitude" to longitude,
            "time" to timestamp,
            "timestamp" to timestamp,
            "status" to if (isSosActive) "SOS_ACTIVE" else "SOS_STOPPED",
            "sessionId" to currentSessionId,
            "senderUserId" to "user_1"
        )

        Log.d("SOS_FIREBASE", "Sender location update to path=sos_locations/user_1: lat=$latitude, lng=$longitude, status=${locationData["status"]}")

        database.child("sos_locations")
            .child("user_1")
            .setValue(locationData)
            .addOnSuccessListener {
                Log.d("SOS_FIREBASE", "Firebase location update SUCCESS")
            }
            .addOnFailureListener { e ->
                Log.e("SOS_FIREBASE", "Firebase location update FAILED: ${e.message}")
            }
    }

    private fun startLiveLocationTracking() {
        if (isLocationTrackingStarted) {
            Log.d("SOS_DEBUG", "Location tracking updates already active. Skipping duplicate request.")
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        isLocationTrackingStarted = true

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            5000L
        )
            .setMinUpdateIntervalMillis(3000L)
            .setMinUpdateDistanceMeters(5.0f)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: return
                Log.d("SOS_DEBUG", "Sender continuous update: lat=${location.latitude}, lng=${location.longitude}")
                uploadLocationToFirebase(location.latitude, location.longitude)
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )

        Log.d("SOS_DEBUG", "FusedLocationProviderClient started continuous tracking updates.")
    }

    private fun stopLiveLocationTracking() {
        if (::locationCallback.isInitialized && isLocationTrackingStarted) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            isLocationTrackingStarted = false
        }

        isSosActive = false
        isSosStarting = false

        stopSosAlarm()

        getSharedPreferences(PREFS_SOS_STATE, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IS_SOS_ACTIVE, false)
            .remove(KEY_SESSION_ID)
            .apply()

        updateSOSStatusInFirebase()

        val btnSOS = findViewById<Button>(R.id.btnSOS)
        val btnStopSOS = findViewById<Button>(R.id.btnStopSOS)

        btnSOS?.isEnabled = true
        btnStopSOS?.isVisible = false

        Log.d("SOS_DEBUG", "SOS session stopped successfully.")
        Toast.makeText(this, "SOS Stopped", Toast.LENGTH_SHORT).show()
    }

    private fun updateSOSStatusInFirebase() {
        val database = FirebaseDatabase.getInstance().reference
        database.child("sos_locations")
            .child("user_1")
            .child("status")
            .setValue("SOS_STOPPED")
        Log.d("SOS_FIREBASE", "Firebase status set to SOS_STOPPED")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSosAlarm()
    }
}