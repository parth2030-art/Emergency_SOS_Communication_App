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

        // 4. Shake to Activate SOS Setup
        val switchShakeSOS = findViewById<SwitchMaterial>(R.id.switchShakeSOS)
        val spinnerShakeSensitivity = findViewById<AutoCompleteTextView>(R.id.spinnerShakeSensitivity)
        val tilShakeSensitivity = findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilShakeSensitivity)

        val isShakeEnabled = prefs.getBoolean("shake_sos_enabled", false)
        switchShakeSOS.isChecked = isShakeEnabled
        tilShakeSensitivity.visibility = if (isShakeEnabled) android.view.View.VISIBLE else android.view.View.GONE

        switchShakeSOS.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("shake_sos_enabled", isChecked).apply()
            tilShakeSensitivity.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
            Toast.makeText(
                this,
                if (isChecked) "Shake SOS Enabled" else "Shake SOS Disabled",
                Toast.LENGTH_SHORT
            ).show()
        }

        val sensitivityLevels = arrayOf("Low", "Medium", "High")
        val sensitivityAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, sensitivityLevels)
        spinnerShakeSensitivity.setAdapter(sensitivityAdapter)

        val savedSensitivity = prefs.getString("shake_sensitivity", "Medium") ?: "Medium"
        spinnerShakeSensitivity.setText(savedSensitivity, false)
        spinnerShakeSensitivity.setOnItemClickListener { parent, _, position, _ ->
            val selectedLevel = parent.getItemAtPosition(position) as String
            prefs.edit().putString("shake_sensitivity", selectedLevel).apply()
            Toast.makeText(this, "Shake Sensitivity set to: $selectedLevel", Toast.LENGTH_SHORT).show()
        }

        // 5. Low-Battery Threshold Setup
        val spinnerBatteryThreshold = findViewById<AutoCompleteTextView>(R.id.spinnerBatteryThreshold)
        val thresholdOptions = arrayOf("10%", "15% (Default)", "20%", "25%")
        val thresholdValues = arrayOf(10, 15, 20, 25)
        val thresholdAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, thresholdOptions)
        spinnerBatteryThreshold.setAdapter(thresholdAdapter)

        val currentThreshold = BatteryAlertManager.getLowBatteryThreshold(this)
        val initialText = when (currentThreshold) {
            10 -> "10%"
            20 -> "20%"
            25 -> "25%"
            else -> "15% (Default)"
        }
        spinnerBatteryThreshold.setText(initialText, false)
        spinnerBatteryThreshold.setOnItemClickListener { _, _, position, _ ->
            val selectedThreshold = thresholdValues.getOrElse(position) { 15 }
            BatteryAlertManager.setLowBatteryThreshold(this, selectedThreshold)
            Toast.makeText(this, "Low Battery Alert Threshold set to $selectedThreshold%", Toast.LENGTH_SHORT).show()
        }

        // 6. Permissions Checker
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
