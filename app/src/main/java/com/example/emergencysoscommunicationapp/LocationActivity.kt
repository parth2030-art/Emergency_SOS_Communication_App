package com.example.emergencysoscommunicationapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.firebase.database.FirebaseDatabase
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.text.SimpleDateFormat
import java.util.*

class LocationActivity : BaseActivity() {

    private lateinit var mapView: MapView
    private lateinit var cardTrackingStatus: MaterialCardView
    private lateinit var txtTrackingStatus: TextView
    private lateinit var txtLastUpdated: TextView
    private lateinit var imgTrackingDot: ImageView
    private lateinit var btnStopSharing: MaterialButton

    private var locationMarker: Marker? = null

    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    private var isTracking = false
    private var latitude = 0.0
    private var longitude = 0.0

    // High accuracy updates: 5 seconds interval, 3 seconds fastest, 5 meters minimum displacement
    private val locationRequest =
        LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            5000L
        )
            .setMinUpdateIntervalMillis(3000L)
            .setMinUpdateDistanceMeters(5.0f)
            .build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            super.onLocationResult(locationResult)
            val location = locationResult.lastLocation ?: return

            latitude = location.latitude
            longitude = location.longitude

            android.util.Log.d("SOS_LOCATION_ACTIVITY", "Sender location callback: lat=$latitude, lng=$longitude, accuracy=${location.accuracy}m")

            updateMapLocation(latitude, longitude)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_location)

        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        btnBack.setOnClickListener {
            finish()
        }

        mapView = findViewById(R.id.mapView)
        cardTrackingStatus = findViewById(R.id.cardTrackingStatus)
        txtTrackingStatus = findViewById(R.id.txtTrackingStatus)
        txtLastUpdated = findViewById(R.id.txtLastUpdated)
        imgTrackingDot = findViewById(R.id.imgTrackingDot)
        btnStopSharing = findViewById(R.id.btnStopSharing)

        setupMap()

        btnStopSharing.setOnClickListener {
            stopLiveLocationSharing()
        }

        checkLocationPermission()
    }

    private fun setupMap() {
        // Enforce explicit secure HTTPS tile provider
        mapView.setTileSource(MapHelper.getOsmTileSource())
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(17.0)
    }

    private fun checkLocationPermission() {
        val fineLocationPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (fineLocationPermission != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Location permission is required for live tracking", Toast.LENGTH_SHORT).show()
        } else {
            startLiveLocationUpdates()
        }
    }

    private fun startLiveLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        isTracking = true
        txtTrackingStatus.text = "Status: Active"
        txtTrackingStatus.setTextColor(ContextCompat.getColor(this, R.color.success_green))
        imgTrackingDot.imageTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.success_green)
        )

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun stopLiveLocationSharing() {
        if (isTracking) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            isTracking = false
        }

        txtTrackingStatus.text = "Status: Inactive"
        txtTrackingStatus.setTextColor(ContextCompat.getColor(this, R.color.error_red))
        imgTrackingDot.imageTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.error_red)
        )

        // Upload status stopped to Firebase
        val database = FirebaseDatabase.getInstance().getReference("sos_locations").child("user_1")
        database.child("status").setValue("SOS_STOPPED")

        Toast.makeText(this, "Live Location Sharing Stopped", Toast.LENGTH_SHORT).show()
        
        // Finish activity after successfully stopping
        finish()
    }

    private fun updateMapLocation(latitude: Double, longitude: Double) {
        val point = GeoPoint(latitude, longitude)

        // Update UI
        val sdf = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())
        txtLastUpdated.text = "Last Updated: ${sdf.format(Date())}"

        // Update Map Marker (single marker, moved instead of recreated)
        if (locationMarker == null) {
            locationMarker = Marker(mapView).apply {
                title = "My Live Location"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                val drawable = ContextCompat.getDrawable(this@LocationActivity, R.drawable.ic_location)
                if (drawable != null) {
                    drawable.setTint(ContextCompat.getColor(this@LocationActivity, R.color.primary))
                    icon = drawable
                }
            }
            mapView.overlays.add(locationMarker)
            mapView.controller.setCenter(point)
            android.util.Log.d("SOS_LOCATION_ACTIVITY", "Created marker at initial point ($latitude, $longitude)")
        } else {
            android.util.Log.d("SOS_LOCATION_ACTIVITY", "Moving marker from ${locationMarker?.position} to $point")
            locationMarker?.position = point
            mapView.controller.animateTo(point)
        }

        mapView.invalidate()

        // Upload updates to Firebase Realtime Database
        if (isTracking) {
            val database = FirebaseDatabase.getInstance().getReference("sos_locations").child("user_1")
            val timestamp = System.currentTimeMillis()
            val locationData = mapOf(
                "latitude" to latitude,
                "longitude" to longitude,
                "time" to timestamp,
                "timestamp" to timestamp,
                "status" to "SOS_ACTIVE"
            )
            database.setValue(locationData).addOnSuccessListener {
                android.util.Log.d("SOS_LOCATION_ACTIVITY", "Location uploaded to Firebase: lat=$latitude, lng=$longitude")
            }
        }
    }

    // OSMDroid Lifecycle management
    override fun onResume() {
        super.onResume()
        mapView.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLiveLocationUpdates()
        }
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        if (isTracking) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }
}