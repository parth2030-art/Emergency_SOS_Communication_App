package com.example.emergencysoscommunicationapp

import android.Manifest
import android.net.Uri
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.telephony.SmsManager
import android.widget.Button
import android.media.MediaPlayer
import com.google.android.gms.location.Priority
import android.widget.ImageButton
import android.widget.Toast
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.ScaleAnimation
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.firebase.database.FirebaseDatabase
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import android.os.Build
import androidx.core.view.isVisible
import com.google.firebase.messaging.FirebaseMessaging
import com.google.android.material.card.MaterialCardView

class HomeActivity : AppCompatActivity() {

    private lateinit var locationCallback: LocationCallback

    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    private var isTracking = false

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

        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                Toast.makeText(
                    this,
                    "FCM Token Saved",
                    Toast.LENGTH_SHORT
                ).show()

                FirebaseDatabase.getInstance().reference
                    .child("fcm_tokens")
                    .child("user_1")
                    .setValue(token)
            }

        val btnSOS = findViewById<Button>(R.id.btnSOS)
        val btnContacts = findViewById<MaterialCardView>(R.id.btnContacts)
        val btnCall = findViewById<MaterialCardView>(R.id.btnCall)
        val btnSMS = findViewById<MaterialCardView>(R.id.btnSMS)
        val btnLocation = findViewById<MaterialCardView>(R.id.btnLocation)
        val btnStopSOS = findViewById<Button>(R.id.btnStopSOS)
        val btnSettings = findViewById<ImageButton>(R.id.btnSettings)

        btnSOS.setOnClickListener {
            sendSOSMessage()
            btnStopSOS.isVisible = true
            startLiveLocationTracking()
        }

        btnSMS.setOnClickListener {
            startActivity(
                Intent(
                    this,
                    SMSActivity::class.java
                )
            )
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
            btnStopSOS.isVisible = false
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        startPulseAnimation()
        updateLocationStatusUI()
    }

    override fun onResume() {
        super.onResume()
        startPulseAnimation()
        updateLocationStatusUI()
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

    private fun sendSOSMessage() {

        val contacts = ContactStorage.getContacts(this)

        if (contacts.isEmpty()) {

            Toast.makeText(
                this,
                "No Contacts Saved",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            Toast.makeText(
                this,
                "Location Permission Required",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        val fusedLocationClient =
            LocationServices.getFusedLocationProviderClient(this)

        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            null
        ).addOnSuccessListener { location ->

            val prefs = getSharedPreferences("SOS_SETTINGS", MODE_PRIVATE)
            val customMsg = prefs.getString("custom_msg", "I need help immediately.") ?: "I need help immediately."

            val message = if (location != null) {

                uploadLocationToFirebase(
                    location.latitude,
                    location.longitude
                )

                """
🚨 EMERGENCY SOS 🚨

$customMsg

Initial Location Pin:
https://maps.google.com/?q=${location.latitude},${location.longitude}

View Live Movement in App:
emergencysos://livetrack
            """.trimIndent()

            } else {

                """
🚨 EMERGENCY SOS 🚨

$customMsg

Location unavailable. View Live Track when online:
emergencysos://livetrack
            """.trimIndent()
            }

            val smsManager = SmsManager.getDefault()

            for (contact in contacts) {

                try {

                    val parts =
                        smsManager.divideMessage(message)

                    smsManager.sendMultipartTextMessage(
                        contact.phone,
                        null,
                        parts,
                        null,
                        null
                    )

                } catch (e: Exception) {

                    Toast.makeText(
                        this,
                        "Failed: ${contact.phone}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            Toast.makeText(
                this,
                "SOS Sent To ${contacts.size} Contact(s)",
                Toast.LENGTH_LONG
            ).show()

            playAlertSound()
        }
    }

    private fun callEmergencyContact() {

        val contacts = ContactStorage.getContacts(this)

        if (contacts.isEmpty()) {

            Toast.makeText(
                this,
                "No Emergency Contact Saved",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        val phoneNumber = contacts[0].phone

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CALL_PHONE
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            Toast.makeText(
                this,
                "Call Permission Required",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        val intent = Intent(
            Intent.ACTION_DIAL,
            Uri.parse("tel:$phoneNumber")
        )

        startActivity(intent)
    }

    private fun playAlertSound() {

        val mediaPlayer =
            MediaPlayer.create(
                this,
                R.raw.alert
            )

        mediaPlayer.start()

        mediaPlayer.setOnCompletionListener {
            it.release()
        }
    }

    private fun uploadLocationToFirebase(
        latitude: Double,
        longitude: Double
    ) {
        val database = FirebaseDatabase.getInstance().reference

        val timestamp = System.currentTimeMillis()
        val locationData = mapOf(
            "latitude" to latitude,
            "longitude" to longitude,
            "time" to timestamp,
            "timestamp" to timestamp,
            "status" to "SOS_ACTIVE"
        )

        android.util.Log.d("SOS_SENDER_FIREBASE", "Uploading to path sos_locations/user_1: lat=$latitude, lng=$longitude, time=$timestamp")

        database.child("sos_locations")
            .child("user_1")
            .setValue(locationData)
            .addOnSuccessListener {
                android.util.Log.d("SOS_SENDER_FIREBASE", "Firebase upload SUCCESS: lat=$latitude, lng=$longitude")
                Toast.makeText(
                    this,
                    "Live Location updated in Firebase",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .addOnFailureListener { e ->
                android.util.Log.e("SOS_SENDER_FIREBASE", "Firebase upload FAILED: ${e.message}", e)
                Toast.makeText(
                    this,
                    "Firebase upload failed: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    private fun startLiveLocationTracking() {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(
                this,
                "Location Permission Required",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        isTracking = true

        val locationRequest =
            LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                5000L
            )
                .setMinUpdateIntervalMillis(3000L)
                .setMinUpdateDistanceMeters(5.0f)
                .build()

        locationCallback =
            object : LocationCallback() {
                override fun onLocationResult(
                    locationResult: LocationResult
                ) {
                    val location =
                        locationResult.lastLocation ?: return

                    android.util.Log.d(
                        "SOS_SENDER_LOCATION",
                        "Sender continuous location update received: lat=${location.latitude}, lng=${location.longitude}, accuracy=${location.accuracy}m"
                    )

                    uploadLocationToFirebase(
                        location.latitude,
                        location.longitude
                    )
                }
            }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )

        android.util.Log.d("SOS_SENDER_LOCATION", "Live location tracking started with interval 5000ms, minDisplacement 5m")

        Toast.makeText(
            this,
            "Live Tracking Started",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun stopLiveLocationTracking() {

        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }

        isTracking = false

        updateSOSStatus()

        android.util.Log.d("SOS_SENDER_LOCATION", "Live location tracking stopped by user")

        Toast.makeText(
            this,
            "SOS Stopped",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateSOSStatus() {

        val database = FirebaseDatabase.getInstance().reference

        database.child("sos_locations")
            .child("user_1")
            .child("status")
            .setValue("SOS_STOPPED")

        android.util.Log.d("SOS_SENDER_FIREBASE", "Set status to SOS_STOPPED on sos_locations/user_1")
    }

}