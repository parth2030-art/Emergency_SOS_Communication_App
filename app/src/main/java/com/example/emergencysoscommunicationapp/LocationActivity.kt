package com.example.emergencysoscommunicationapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices

class LocationActivity : BaseActivity() {

    private lateinit var txtLocation: TextView
    private lateinit var btnOpenMap: Button

    private var latitude = 0.0
    private var longitude = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_location)

        enableBackButton("My Location")

        txtLocation = findViewById(R.id.txtLocation)
        btnOpenMap = findViewById(R.id.btnOpenMap)

        getCurrentLocation()

        btnOpenMap.setOnClickListener {

            if (latitude != 0.0 && longitude != 0.0) {

                val uri = Uri.parse(
                    "https://maps.google.com/?q=$latitude,$longitude"
                )

                val intent = Intent(
                    Intent.ACTION_VIEW,
                    uri
                )

                startActivity(intent)

            }
        }
    }

    private fun getCurrentLocation() {

        if (
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION
                ),
                101
            )
            return
        }

        val fusedLocationClient =
            LocationServices.getFusedLocationProviderClient(this)

        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->

                if (location != null) {

                    latitude = location.latitude
                    longitude = location.longitude

                    txtLocation.text =
                        "Latitude: $latitude\n\nLongitude: $longitude"

                } else {

                    txtLocation.text =
                        "Unable to get location.\nEnable GPS and try again."
                }
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
            requestCode == 101 &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            getCurrentLocation()
        }
    }
}