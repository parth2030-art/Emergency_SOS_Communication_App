package com.example.emergencysoscommunicationapp

import android.app.Application
import org.osmdroid.config.Configuration

class EmergencySosApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val configuration = Configuration.getInstance()

        // Load OSMDroid settings from SharedPreferences
        configuration.load(
            applicationContext,
            getSharedPreferences("osmdroid", MODE_PRIVATE)
        )

        // Set a policy-compliant User-Agent including package details and contact information
        configuration.userAgentValue =
            "EmergencySOSCommunicationApp/1.0 (com.example.emergencysoscommunicationapp; contact: parth2030@example.com)"
    }
}
