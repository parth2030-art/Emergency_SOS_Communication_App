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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.firebase.database.FirebaseDatabase
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
class HomeActivity : AppCompatActivity() {

    private lateinit var locationCallback: LocationCallback

    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    private var isTracking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.SEND_SMS,
                Manifest.permission.ACCESS_FINE_LOCATION
            ),
            100
        )

        val btnSOS = findViewById<Button>(R.id.btnSOS)
        val btnContacts = findViewById<ImageButton>(R.id.btnContacts)
        val btnCall = findViewById<ImageButton>(R.id.btnCall)
        val btnSMS = findViewById<ImageButton>(R.id.btnSMS)
        val btnLocation = findViewById<ImageButton>(R.id.btnLocation)
        val btnStopSOS = findViewById<Button>(R.id.btnStopSOS)

        btnSOS.setOnClickListener {
            sendSOSMessage()
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
            callEmergencyContact()
        }

        btnStopSOS.setOnClickListener {
            stopLiveLocationTracking()
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

            val message = if (location != null) {

                uploadLocationToFirebase(
                    location.latitude,
                    location.longitude
                )

                """
🚨 EMERGENCY SOS 🚨

I need help immediately.

My Current Location:
https://maps.google.com/?q=${location.latitude},${location.longitude}
            """.trimIndent()

            } else {

                """
🚨 EMERGENCY SOS 🚨

I need help immediately.

Location unavailable.
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
        val database =
            FirebaseDatabase.getInstance().reference

        val locationData = mapOf(
            "latitude" to latitude,
            "longitude" to longitude,
            "time" to System.currentTimeMillis(),
            "status" to "SOS_ACTIVE"
        )

        database.child("sos_locations")
            .child("user_1")
            .setValue(locationData)
            .addOnSuccessListener {
                Toast.makeText(
                    this,
                    "Location uploaded to Firebase",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .addOnFailureListener {
                Toast.makeText(
                    this,
                    "Firebase upload failed",
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
                5000
            ).build()

        locationCallback =
            object : LocationCallback() {
                override fun onLocationResult(
                    locationResult: LocationResult
                ) {
                    val location =
                        locationResult.lastLocation ?: return

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

    }

}