package com.example.emergencysoscommunicationapp

import android.app.Application
import android.util.Log
import org.osmdroid.config.Configuration

class EmergencySosApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val sharedPrefs = getSharedPreferences("osmdroid", MODE_PRIVATE)
        val configuration = Configuration.getInstance()

        // Set policy-compliant User-Agent identifying the application first
        configuration.userAgentValue =
            "EmergencySOSCommunicationApp/1.0 (com.example.emergencysoscommunicationapp; contact: figmaparth0@gmail.com)"

        // Load OSMDroid settings safely from SharedPreferences
        configuration.load(applicationContext, sharedPrefs)

        // Sync pending offline location queue on startup if internet is available
        try {
            if (NetworkMonitor.isInternetAvailable(this) && OfflineLocationQueue.hasQueuedLocations(this)) {
                Log.d("EmergencySosApp", "Pending offline locations detected on app startup. Syncing to Firebase.")
                OfflineLocationQueue.syncQueueToFirebase(this)
            }
        } catch (e: Exception) {
            Log.e("EmergencySosApp", "Error syncing offline queue on startup: ${e.message}")
        }
    }
}
