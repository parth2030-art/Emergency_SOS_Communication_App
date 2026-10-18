package com.example.emergencysoscommunicationapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.widget.ImageButton
import android.widget.TextView
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.firebase.database.*
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import android.media.MediaPlayer
import android.widget.Toast
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.*

class ReceiverMapActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var txtStatus: TextView
    private lateinit var txtBatteryStatus: TextView

    private lateinit var database: DatabaseReference
    private var locationListener: ValueEventListener? = null

    private var victimMarker: Marker? = null
    private var guardianMarker: Marker? = null
    private var isFirstZoomDone = false

    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    private lateinit var guardianLocationCallback: LocationCallback
    private var isGuardianTrackingActive = false

    private var audioPlayer: MediaPlayer? = null
    private var isAudioPlaying = false

    private var senderUserId: String = "user_1"
    private var sosSessionId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("SOS_NAVIGATION", "ReceiverMapActivity.onCreate() called with intent: $intent, data=${intent?.data}")

        setContentView(R.layout.activity_receiver_map)

        mapView = findViewById(R.id.mapView)
        txtStatus = findViewById(R.id.txtStatus)
        txtBatteryStatus = findViewById(R.id.txtBatteryStatus)

        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        btnBack.setOnClickListener {
            Log.d("SOS_NAVIGATION", "btnBack clicked. Finishing ReceiverMapActivity intentionally.")
            finish()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Log.d("SOS_NAVIGATION", "User pressed system Back button. Finishing ReceiverMapActivity.")
                finish()
            }
        })

        txtStatus.text = "Waiting for live location..."

        readTrackingArguments(intent)
        setupMap()
        connectFirebaseListener()
        startGuardianLocationUpdates()
    }

    override fun onStart() {
        super.onStart()
        Log.d("SOS_NAVIGATION", "ReceiverMapActivity.onStart() called")
    }

    override fun onResume() {
        super.onResume()
        Log.d("SOS_NAVIGATION", "ReceiverMapActivity.onResume() called")
        mapView.onResume()
        if (locationListener == null && ::database.isInitialized) {
            connectFirebaseListener()
        }
        startGuardianLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        Log.d("SOS_NAVIGATION", "ReceiverMapActivity.onPause() called")
        mapView.onPause()
        stopGuardianLocationUpdates()
    }

    override fun onStop() {
        super.onStop()
        Log.d("SOS_NAVIGATION", "ReceiverMapActivity.onStop() called")
    }

    override fun onDestroy() {
        Log.e("SOS_NAVIGATION", "ReceiverMapActivity destroyed", Throwable())
        super.onDestroy()
        removeLocationListener()
        stopGuardianLocationUpdates()
    }

    override fun finish() {
        Log.e("SOS_NAVIGATION", "ReceiverMapActivity finishing. Call stack trace:", Throwable())
        super.finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.d("SOS_NAVIGATION", "onNewIntent: uri=${intent.data}, extras=${intent.extras}")
        readTrackingArguments(intent)
        connectFirebaseListener()
    }

    private fun readTrackingArguments(intent: Intent?): Boolean {
        if (intent == null) {
            senderUserId = "user_1"
            Log.w("SOS_NAVIGATION", "readTrackingArguments: Intent is null. Using default senderUserId=user_1")
            return false
        }

        Log.d("SOS_NAVIGATION", "Intent extras=${intent.extras}")
        Log.d("SOS_NAVIGATION", "Intent URI=${intent.data}")

        val uri = intent.data
        val extraUser = intent.getStringExtra("senderUserId")
            ?: intent.getStringExtra("userId")
            ?: uri?.getQueryParameter("senderUserId")
            ?: uri?.getQueryParameter("userId")

        val extraSession = intent.getStringExtra("sosSessionId")
            ?: uri?.getQueryParameter("sosSessionId")

        senderUserId = if (!extraUser.isNullOrBlank()) extraUser.trim() else "user_1"
        sosSessionId = if (!extraSession.isNullOrBlank()) extraSession.trim() else ""

        Log.d("SOS_NOTIFICATION", "senderUserId=$senderUserId")
        Log.d("SOS_NOTIFICATION", "sosSessionId=$sosSessionId")

        if (senderUserId.isBlank()) {
            txtStatus.text = "Waiting for live location..."
            return false
        }

        return true
    }

    private fun setupMap() {
        try {
            mapView.setTileSource(MapHelper.getOsmTileSource())
            mapView.setMultiTouchControls(true)
            mapView.controller.setZoom(17.0)
        } catch (e: Exception) {
            Log.e("SOS_NAVIGATION", "Error configuring OSMDroid MapView: ${e.message}", e)
        }
    }

    private fun connectFirebaseListener() {
        removeLocationListener()

        database = FirebaseDatabase.getInstance()
            .getReference("sos_locations")
            .child(senderUserId)

        Log.d("SOS_FIREBASE", "Listening path = ${database.path}")
        listenForLocation()
    }

    private fun listenForLocation() {
        locationListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    if (isFinishing || isDestroyed) return

                    Log.d("SOS_FIREBASE", "Listening path = ${database.path}")
                    Log.d("SOS_FIREBASE", "Snapshot = ${snapshot.value}")

                    if (!snapshot.exists()) {
                        txtStatus.text = "Waiting for live location..."
                        Log.w("SOS_FIREBASE", "No Firebase data found at ${database.path}")
                        return
                    }

                    val latRaw = snapshot.child("latitude").value
                    val latitude = when (latRaw) {
                        is Number -> latRaw.toDouble()
                        is String -> latRaw.toDoubleOrNull()
                        else -> null
                    }

                    val lngRaw = snapshot.child("longitude").value
                    val longitude = when (lngRaw) {
                        is Number -> lngRaw.toDouble()
                        is String -> lngRaw.toDoubleOrNull()
                        else -> null
                    }

                    val timeRaw = snapshot.child("timestamp").value ?: snapshot.child("time").value
                    val timestamp = when (timeRaw) {
                        is Number -> timeRaw.toLong()
                        is String -> timeRaw.toLongOrNull() ?: System.currentTimeMillis()
                        else -> System.currentTimeMillis()
                    }

                    val status = snapshot.child("status").getValue(String::class.java) ?: "UNKNOWN"

                    Log.d("SOS_FIREBASE", "Parsed location: latitude=$latitude, longitude=$longitude, status=$status, timestamp=$timestamp")

                    val validLocation = latitude != null && longitude != null
                            && latitude.isFinite() && longitude.isFinite()
                            && latitude in -90.0..90.0 && longitude in -180.0..180.0

                    if (!validLocation) {
                        txtStatus.text = "Waiting for live location..."
                        Log.e("SOS_FIREBASE", "Invalid coordinates: latRaw=$latRaw, lngRaw=$lngRaw")
                        return
                    }

                    val formatter = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())
                    val lastUpdated = formatter.format(Date(timestamp))

                    if (status.equals("SOS_STOPPED", ignoreCase = true)) {
                        txtStatus.text = "🔴 Tracking Stopped | 🔵 Your Location\nLast Active: $lastUpdated"
                        txtStatus.setTextColor(ContextCompat.getColor(this@ReceiverMapActivity, R.color.error_red))
                    } else {
                        txtStatus.text = "🔴 Victim Tracking Active | 🔵 Your Location\nUpdated: $lastUpdated"
                        txtStatus.setTextColor(ContextCompat.getColor(this@ReceiverMapActivity, R.color.success_green))
                    }

                    // Display victim phone battery level if reported
                    val batteryRaw = snapshot.child("batteryLevel").value
                    val batteryLevel = when (batteryRaw) {
                        is Number -> batteryRaw.toInt()
                        is String -> batteryRaw.toIntOrNull()
                        else -> null
                    }
                    val batteryTimeRaw = snapshot.child("batteryTimestamp").value
                    val batteryTimestamp = when (batteryTimeRaw) {
                        is Number -> batteryTimeRaw.toLong()
                        is String -> batteryTimeRaw.toLongOrNull()
                        else -> null
                    }

                    if (batteryLevel != null) {
                        txtBatteryStatus.visibility = View.VISIBLE
                        val batteryTimeStr = if (batteryTimestamp != null) {
                            " (Updated ${formatter.format(Date(batteryTimestamp))})"
                        } else {
                            ""
                        }
                        if (batteryLevel <= 15) {
                            txtBatteryStatus.text = "⚠️ Victim Phone Battery: $batteryLevel% (Critically Low!)$batteryTimeStr"
                            txtBatteryStatus.setTextColor(ContextCompat.getColor(this@ReceiverMapActivity, R.color.error_red))
                        } else {
                            txtBatteryStatus.text = "🔋 Victim Phone Battery: $batteryLevel%$batteryTimeStr"
                            txtBatteryStatus.setTextColor(ContextCompat.getColor(this@ReceiverMapActivity, R.color.primary))
                        }
                    } else {
                        txtBatteryStatus.visibility = View.GONE
                    }

                    // Parse Emergency Audio Recording if delivered to Firebase
                    val audioSnap = snapshot.child("audioRecording")
                    val layoutAudio = findViewById<View>(R.id.layoutAudioRecording)
                    val btnPlayAudio = findViewById<MaterialButton>(R.id.btnPlayAudio)
                    val txtAudioTitle = findViewById<TextView>(R.id.txtAudioTitle)
                    val txtAudioDetails = findViewById<TextView>(R.id.txtAudioDetails)

                    if (audioSnap.exists()) {
                        val downloadUrl = audioSnap.child("downloadUrl").getValue(String::class.java)
                        val audioStatus = audioSnap.child("status").getValue(String::class.java) ?: "SENT"
                        val durationMs = audioSnap.child("durationMs").getValue(Long::class.java) ?: 0L
                        val audioTimeRaw = audioSnap.child("timestamp").getValue(Long::class.java) ?: 0L

                        val durationFormatted = if (durationMs > 0) {
                            AudioRecordingManager.formatDuration(durationMs)
                        } else "00:20"

                        layoutAudio?.isVisible = true
                        txtAudioTitle?.text = "🎙️ Emergency Audio Clip ($durationFormatted)"
                        val formattedTime = if (audioTimeRaw > 0) {
                            val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
                            "Received ${sdf.format(Date(audioTimeRaw))}"
                        } else "Delivered to Guardian"
                        txtAudioDetails?.text = "$formattedTime • Status: $audioStatus"

                        btnPlayAudio?.setOnClickListener {
                            toggleAudioPlayback(downloadUrl, btnPlayAudio)
                        }
                    } else {
                        layoutAudio?.isVisible = false
                    }

                    updateVictimMarker(latitude = latitude, longitude = longitude, status = status)

                } catch (exception: Exception) {
                    Log.e("SOS_CRASH", "ReceiverMapActivity Firebase parsing failed", exception)
                    txtStatus.text = "Unable to display live location"
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("SOS_FIREBASE", "Firebase listener cancelled: ${error.message}", error.toException())
                txtStatus.text = "Unable to display live location"
            }
        }

        database.addValueEventListener(locationListener!!)
    }

    private fun toggleAudioPlayback(downloadUrl: String?, btnPlay: MaterialButton) {
        if (isAudioPlaying) {
            stopAudioPlayback(btnPlay)
        } else {
            if (downloadUrl.isNullOrEmpty()) {
                Toast.makeText(this, "Audio recording URL unavailable or pending upload", Toast.LENGTH_SHORT).show()
                return
            }
            try {
                audioPlayer?.release()
                audioPlayer = MediaPlayer().apply {
                    setDataSource(downloadUrl)
                    setOnPreparedListener {
                        start()
                        isAudioPlaying = true
                        btnPlay.text = "Stop"
                        btnPlay.setIconResource(R.drawable.ic_stop)
                    }
                    setOnCompletionListener {
                        stopAudioPlayback(btnPlay)
                    }
                    setOnErrorListener { _, _, _ ->
                        stopAudioPlayback(btnPlay)
                        Toast.makeText(this@ReceiverMapActivity, "Failed to stream emergency audio", Toast.LENGTH_SHORT).show()
                        true
                    }
                    prepareAsync()
                }
            } catch (e: Exception) {
                Log.e("ReceiverMapActivity", "Error playing emergency audio: ${e.message}", e)
                Toast.makeText(this, "Playback error: ${e.message}", Toast.LENGTH_SHORT).show()
                stopAudioPlayback(btnPlay)
            }
        }
    }

    private fun stopAudioPlayback(btnPlay: MaterialButton?) {
        try {
            if (audioPlayer?.isPlaying == true) {
                audioPlayer?.stop()
            }
            audioPlayer?.release()
        } catch (e: Exception) {
            // Ignore
        }
        audioPlayer = null
        isAudioPlaying = false
        btnPlay?.text = "Play"
        btnPlay?.setIconResource(R.drawable.ic_play)
    }

    private fun startGuardianLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w("SOS_DEBUG", "Location permissions not granted for Guardian position tracking.")
            return
        }

        if (isGuardianTrackingActive) return
        isGuardianTrackingActive = true

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateIntervalMillis(3000L)
            .setMinUpdateDistanceMeters(2.0f)
            .build()

        guardianLocationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: return
                Log.d("SOS_DEBUG", "Guardian location update received: lat=${location.latitude}, lng=${location.longitude}")
                updateGuardianMarker(location.latitude, location.longitude)
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            guardianLocationCallback,
            Looper.getMainLooper()
        )
        Log.d("SOS_DEBUG", "FusedLocationProviderClient started Guardian continuous updates.")
    }

    private fun stopGuardianLocationUpdates() {
        if (::guardianLocationCallback.isInitialized && isGuardianTrackingActive) {
            fusedLocationClient.removeLocationUpdates(guardianLocationCallback)
            isGuardianTrackingActive = false
            Log.d("SOS_DEBUG", "Guardian location updates stopped.")
        }
    }

    private fun getColoredMarkerDrawable(colorHex: String): Drawable? {
        return try {
            val drawable = ContextCompat.getDrawable(this, R.drawable.ic_location)?.mutate()
            drawable?.setTint(Color.parseColor(colorHex))
            drawable
        } catch (e: Exception) {
            null
        }
    }

    private fun updateVictimMarker(latitude: Double, longitude: Double, status: String) {
        try {
            val point = GeoPoint(latitude, longitude)

            if (victimMarker == null) {
                var iconDrawable = getColoredMarkerDrawable("#D32F2F") // Red Emergency Marker
                if (iconDrawable == null || iconDrawable.intrinsicWidth <= 0 || iconDrawable.intrinsicHeight <= 0) {
                    iconDrawable = ContextCompat.getDrawable(this, org.osmdroid.library.R.drawable.marker_default)
                }

                victimMarker = Marker(mapView).apply {
                    position = point
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "Victim Live Location"
                    if (iconDrawable != null) {
                        icon = iconDrawable
                    }
                }
                mapView.overlays.add(victimMarker)
                Log.d("SOS_MARKER", "Created Red Victim Marker at $point")
            } else {
                victimMarker?.position = point
            }

            mapView.invalidate()
            adjustMapZoomAndBounds()
        } catch (exception: Exception) {
            Log.e("SOS_MARKER", "Victim marker creation/update failed", exception)
        }
    }

    private fun updateGuardianMarker(latitude: Double, longitude: Double) {
        try {
            val point = GeoPoint(latitude, longitude)

            if (guardianMarker == null) {
                var iconDrawable = getColoredMarkerDrawable("#1976D2") // Blue Guardian Location Marker
                if (iconDrawable == null || iconDrawable.intrinsicWidth <= 0 || iconDrawable.intrinsicHeight <= 0) {
                    iconDrawable = ContextCompat.getDrawable(this, org.osmdroid.library.R.drawable.marker_default)
                }

                guardianMarker = Marker(mapView).apply {
                    position = point
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "Your Location"
                    if (iconDrawable != null) {
                        icon = iconDrawable
                    }
                }
                mapView.overlays.add(guardianMarker)
                Log.d("SOS_MARKER", "Created Blue Guardian Marker at $point")
            } else {
                guardianMarker?.position = point
            }

            mapView.invalidate()
            adjustMapZoomAndBounds()
        } catch (exception: Exception) {
            Log.e("SOS_MARKER", "Guardian marker creation/update failed", exception)
        }
    }

    private fun adjustMapZoomAndBounds() {
        if (isFirstZoomDone) return

        val victimPos = victimMarker?.position
        val guardianPos = guardianMarker?.position

        try {
            if (victimPos != null && guardianPos != null) {
                isFirstZoomDone = true
                val points = arrayListOf(victimPos, guardianPos)
                val boundingBox = BoundingBox.fromGeoPoints(points)
                mapView.zoomToBoundingBox(boundingBox, true, 120)
            } else if (victimPos != null) {
                isFirstZoomDone = true
                mapView.controller.setZoom(17.0)
                mapView.controller.setCenter(victimPos)
            } else if (guardianPos != null) {
                isFirstZoomDone = true
                mapView.controller.setZoom(17.0)
                mapView.controller.setCenter(guardianPos)
            }
        } catch (e: Exception) {
            Log.e("SOS_MARKER", "Error calculating BoundingBox or zooming: ${e.message}", e)
        }
    }

    private fun removeLocationListener() {
        val listener = locationListener ?: return

        if (::database.isInitialized) {
            Log.d("SOS_FIREBASE", "Removing listener from ${database.path}")
            database.removeEventListener(listener)
        }

        locationListener = null
    }
}
