package com.example.emergencysoscommunicationapp

import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.firebase.database.*
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.text.SimpleDateFormat
import java.util.*

class ReceiverMapActivity : BaseActivity() {

    private lateinit var mapView: MapView
    private lateinit var txtStatus: TextView

    private lateinit var database: DatabaseReference
    private var locationListener: ValueEventListener? = null
    private var marker: Marker? = null
    private var isFirstZoom = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_receiver_map)

        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        btnBack.setOnClickListener {
            finish()
        }

        mapView = findViewById(R.id.mapView)
        txtStatus = findViewById(R.id.txtStatus)

        setupMap()

        database = FirebaseDatabase.getInstance()
            .getReference("sos_locations")
            .child("user_1")

        listenForLocation()
    }

    private fun setupMap() {
        // Enforce explicit secure HTTPS tile provider
        mapView.setTileSource(MapHelper.getOsmTileSource())
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(17.0)
    }

    private fun listenForLocation() {
        locationListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return

                val latitude = snapshot.child("latitude").getValue(Double::class.java) ?: return
                val longitude = snapshot.child("longitude").getValue(Double::class.java) ?: return
                val status = snapshot.child("status").getValue(String::class.java) ?: "UNKNOWN"
                val time = snapshot.child("time").getValue(Long::class.java) ?: System.currentTimeMillis()

                val point = GeoPoint(latitude, longitude)
                val sdf = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())
                val lastUpdatedStr = sdf.format(Date(time))

                if (status == "SOS_STOPPED") {
                    txtStatus.text = "Tracking Stopped\nLast Active: $lastUpdatedStr"
                    txtStatus.setTextColor(ContextCompat.getColor(this@ReceiverMapActivity, R.color.error_red))
                } else {
                    txtStatus.text = "Active Tracking\nLast Updated: $lastUpdatedStr"
                    txtStatus.setTextColor(ContextCompat.getColor(this@ReceiverMapActivity, R.color.success_green))
                }

                if (marker == null) {
                    marker = Marker(mapView).apply {
                        title = "Victim Live Location"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        val drawable = ContextCompat.getDrawable(this@ReceiverMapActivity, R.drawable.ic_location)
                        if (drawable != null) {
                            drawable.setTint(ContextCompat.getColor(this@ReceiverMapActivity, R.color.primary))
                            icon = drawable
                        }
                    }
                    mapView.overlays.add(marker)
                }

                marker?.position = point

                // Follow the victim marker
                if (status != "SOS_STOPPED") {
                    if (isFirstZoom) {
                        mapView.controller.setCenter(point)
                        isFirstZoom = false
                    } else {
                        mapView.controller.animateTo(point)
                    }
                }

                mapView.invalidate()
            }

            override fun onCancelled(error: DatabaseError) {
                txtStatus.text = "Sync Error: ${error.message}"
            }
        }

        database.addValueEventListener(locationListener!!)
    }

    private fun removeLocationListener() {
        if (locationListener != null) {
            database.removeEventListener(locationListener!!)
            locationListener = null
        }
    }

    // OSMDroid Lifecycle management
    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        removeLocationListener()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeLocationListener()
    }
}