package com.example.emergencysoscommunicationapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

class LocationActivity : BaseActivity() {

    private lateinit var txtLocation: TextView
    private lateinit var btnOpenMap: Button
    private lateinit var mapView: MapView

    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    private var locationMarker: Marker? = null

    private var latitude = 0.0
    private var longitude = 0.0

    private val locationRequest =
        LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            3000L
        )
            .setMinUpdateIntervalMillis(1500L)
            .build()

    private val locationCallback =
        object : LocationCallback() {

            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)

                val location =
                    locationResult.lastLocation ?: return

                latitude = location.latitude
                longitude = location.longitude

                updateMapLocation(
                    latitude,
                    longitude,
                    location.accuracy
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().userAgentValue =
            packageName

        setContentView(R.layout.activity_location)

        enableBackButton("My Location")

        txtLocation = findViewById(R.id.txtLocation)
        btnOpenMap = findViewById(R.id.btnOpenMap)
        mapView = findViewById(R.id.mapView)

        setupMap()

        btnOpenMap.setOnClickListener {
            openLocationInExternalMap()
        }

        checkLocationPermission()
    }

    private fun setupMap() {

        mapView.setTileSource(
            TileSourceFactory.MAPNIK
        )

        mapView.setMultiTouchControls(true)

        mapView.controller.setZoom(18.0)
    }

    private fun checkLocationPermission() {

        val fineLocationPermission =
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            )

        if (
            fineLocationPermission !=
            PackageManager.PERMISSION_GRANTED
        ) {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_REQUEST
            )

        } else {
            startLiveLocationUpdates()
        }
    }

    private fun startLiveLocationUpdates() {

        if (
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun updateMapLocation(
        latitude: Double,
        longitude: Double,
        accuracy: Float
    ) {

        val currentPoint =
            GeoPoint(latitude, longitude)

        txtLocation.text =
            "Latitude: $latitude\n" +
                    "Longitude: $longitude\n" +
                    "Accuracy: ${accuracy.toInt()} metres"

        if (locationMarker == null) {

            locationMarker = Marker(mapView).apply {
                position = currentPoint
                title = "My live location"
                setAnchor(
                    Marker.ANCHOR_CENTER,
                    Marker.ANCHOR_BOTTOM
                )
            }

            mapView.overlays.add(locationMarker)

        } else {
            locationMarker?.position = currentPoint
        }

        mapView.controller.animateTo(currentPoint)

        mapView.invalidate()
    }

    private fun openLocationInExternalMap() {

        if (latitude == 0.0 && longitude == 0.0) {

            txtLocation.text =
                "Waiting for current location..."

            return
        }

        val uri = Uri.parse(
            "geo:$latitude,$longitude?q=$latitude,$longitude"
        )

        val intent = Intent(
            Intent.ACTION_VIEW,
            uri
        )

        try {
            startActivity(intent)
        } catch (exception: Exception) {

            val browserUri = Uri.parse(
                "https://www.openstreetmap.org/" +
                        "?mlat=$latitude&mlon=$longitude" +
                        "#map=18/$latitude/$longitude"
            )

            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    browserUri
                )
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (
            requestCode == LOCATION_PERMISSION_REQUEST &&
            grantResults.isNotEmpty() &&
            grantResults[0] ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startLiveLocationUpdates()
        } else {
            txtLocation.text =
                "Location permission is required."
        }
    }

    override fun onResume() {
        super.onResume()

        mapView.onResume()

        if (
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startLiveLocationUpdates()
        }
    }

    override fun onPause() {
        super.onPause()

        fusedLocationClient.removeLocationUpdates(
            locationCallback
        )

        mapView.onPause()
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 101
    }
}