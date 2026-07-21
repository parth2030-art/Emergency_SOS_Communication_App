package com.example.emergencysoscommunicationapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        btnBack.setOnClickListener {
            finish()
        }

        val switchDarkMode = findViewById<SwitchMaterial>(R.id.switchDarkMode)
        val switchNotifications = findViewById<SwitchMaterial>(R.id.switchNotifications)
        val spinnerAlertTone = findViewById<AutoCompleteTextView>(R.id.spinnerAlertTone)
        val txtLocationStatus = findViewById<TextView>(R.id.txtLocationStatus)
        val imgStatusDot = findViewById<ImageView>(R.id.imgStatusDot)
        val btnLocationPerm = findViewById<MaterialButton>(R.id.btnLocationPerm)

        val prefs = getSharedPreferences("SOS_SETTINGS", MODE_PRIVATE)

        // 1. Dark Mode Setup
        val isDarkModeEnabled = prefs.getBoolean("dark_mode", false)
        switchDarkMode.isChecked = isDarkModeEnabled
        switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("dark_mode", isChecked).apply()
            if (isChecked) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
        }

        // 2. Notifications Setup
        val isNotificationsEnabled = prefs.getBoolean("notifications", true)
        switchNotifications.isChecked = isNotificationsEnabled
        switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("notifications", isChecked).apply()
            Toast.makeText(this, if (isChecked) "Notifications Enabled" else "Notifications Silenced", Toast.LENGTH_SHORT).show()
        }

        // 3. Siren Tone Dropdown
        val tones = arrayOf("Default Siren", "High Pitch", "Gentle", "Silent")
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, tones)
        spinnerAlertTone.setAdapter(adapter)

        val savedTone = prefs.getString("alert_tone", "Default Siren")
        spinnerAlertTone.setText(savedTone, false)
        spinnerAlertTone.setOnItemClickListener { parent, _, position, _ ->
            val selectedTone = parent.getItemAtPosition(position) as String
            prefs.edit().putString("alert_tone", selectedTone).apply()
            Toast.makeText(this, "Alert tone set to: $selectedTone", Toast.LENGTH_SHORT).show()
        }

        // 4. Permissions Checker
        checkPermissionsUI(txtLocationStatus, imgStatusDot)

        btnLocationPerm.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh permissions status in case user came back from OS settings
        val txtLocationStatus = findViewById<TextView>(R.id.txtLocationStatus)
        val imgStatusDot = findViewById<ImageView>(R.id.imgStatusDot)
        if (txtLocationStatus != null && imgStatusDot != null) {
            checkPermissionsUI(txtLocationStatus, imgStatusDot)
        }
    }

    private fun checkPermissionsUI(txtLocationStatus: TextView, imgStatusDot: ImageView) {
        val fineLocationPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineLocationPermission) {
            txtLocationStatus.text = "Location Permission: Granted"
            imgStatusDot.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.success_green)
            )
        } else {
            txtLocationStatus.text = "Location Permission: Denied"
            imgStatusDot.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.error_red)
            )
        }
    }
}
