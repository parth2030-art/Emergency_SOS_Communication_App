package com.example.emergencysoscommunicationapp

import android.app.Application
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
    }
}
