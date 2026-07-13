package com.example.emergencysoscommunicationapp

import android.os.Bundle
import android.widget.TextView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

import com.google.firebase.database.*

class ReceiverMapActivity : BaseActivity() {

    private lateinit var mapView: MapView
    private lateinit var txtStatus: TextView

    private lateinit var database: DatabaseReference

    private var marker: Marker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().userAgentValue = packageName

        setContentView(R.layout.activity_receiver_map)

        enableBackButton("Live Tracking")

        mapView = findViewById(R.id.mapView)
        txtStatus = findViewById(R.id.txtStatus)

        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(18.0)

        database = FirebaseDatabase.getInstance()
            .getReference("sos_locations")
            .child("user_1")

        listenForLocation()
    }

    private fun listenForLocation() {

        database.addValueEventListener(object : ValueEventListener {

            override fun onDataChange(snapshot: DataSnapshot) {

                if (!snapshot.exists()) return

                val latitude =
                    snapshot.child("latitude")
                        .getValue(Double::class.java) ?: return

                val longitude =
                    snapshot.child("longitude")
                        .getValue(Double::class.java) ?: return

                val status =
                    snapshot.child("status")
                        .getValue(String::class.java)

                txtStatus.text = status

                val point = GeoPoint(latitude, longitude)

                if (marker == null) {

                    marker = Marker(mapView)

                    marker!!.position = point

                    marker!!.title = "Victim"

                    marker!!.setAnchor(
                        Marker.ANCHOR_CENTER,
                        Marker.ANCHOR_BOTTOM
                    )

                    mapView.overlays.add(marker)

                } else {

                    marker!!.position = point
                }

                mapView.controller.animateTo(point)

                mapView.invalidate()
            }

            override fun onCancelled(error: DatabaseError) {

                txtStatus.text = error.message
            }

        })
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }
}