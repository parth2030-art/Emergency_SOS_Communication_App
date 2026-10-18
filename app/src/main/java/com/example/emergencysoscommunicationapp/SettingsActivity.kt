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

        // 6. Auto-Record Audio on SOS Setup (Stage 5)
        val switchAutoRecordAudio = findViewById<SwitchMaterial>(R.id.switchAutoRecordAudio)
        val isAutoRecordEnabled = prefs.getBoolean("auto_record_audio_enabled", false)
        switchAutoRecordAudio.isChecked = isAutoRecordEnabled

        switchAutoRecordAudio.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                val hasMicPermission = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                if (hasMicPermission) {
                    prefs.edit().putBoolean("auto_record_audio_enabled", true).apply()
                    Toast.makeText(this, "Auto-Record Audio on SOS Enabled", Toast.LENGTH_SHORT).show()
                } else {
                    buttonView.isChecked = false
                    Toast.makeText(this, "Microphone permission required for auto-recording", Toast.LENGTH_SHORT).show()
                    androidx.core.app.ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.RECORD_AUDIO),
                        202
                    )
                }
            } else {
                prefs.edit().putBoolean("auto_record_audio_enabled", false).apply()
                Toast.makeText(this, "Auto-Record Audio Disabled", Toast.LENGTH_SHORT).show()
            }
        }

        // 7. Permissions Checker
        val txtMicStatus = findViewById<TextView>(R.id.txtMicStatus)
        val imgMicStatusDot = findViewById<ImageView>(R.id.imgMicStatusDot)
        checkPermissionsUI(txtLocationStatus, imgStatusDot, txtMicStatus, imgMicStatusDot)

        btnLocationPerm.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 202) {
            val switchAutoRecordAudio = findViewById<SwitchMaterial>(R.id.switchAutoRecordAudio)
            val prefs = getSharedPreferences("SOS_SETTINGS", MODE_PRIVATE)
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                switchAutoRecordAudio?.isChecked = true
                prefs.edit().putBoolean("auto_record_audio_enabled", true).apply()
                Toast.makeText(this, "Auto-Record Audio on SOS Enabled", Toast.LENGTH_SHORT).show()
            } else {
                switchAutoRecordAudio?.isChecked = false
                prefs.edit().putBoolean("auto_record_audio_enabled", false).apply()
                Toast.makeText(this, "Microphone permission denied", Toast.LENGTH_SHORT).show()
            }
            val txtLocationStatus = findViewById<TextView>(R.id.txtLocationStatus)
            val imgStatusDot = findViewById<ImageView>(R.id.imgStatusDot)
            val txtMicStatus = findViewById<TextView>(R.id.txtMicStatus)
            val imgMicStatusDot = findViewById<ImageView>(R.id.imgMicStatusDot)
            checkPermissionsUI(txtLocationStatus, imgStatusDot, txtMicStatus, imgMicStatusDot)
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh permissions status in case user came back from OS settings
        val txtLocationStatus = findViewById<TextView>(R.id.txtLocationStatus)
        val imgStatusDot = findViewById<ImageView>(R.id.imgStatusDot)
        val txtMicStatus = findViewById<TextView>(R.id.txtMicStatus)
        val imgMicStatusDot = findViewById<ImageView>(R.id.imgMicStatusDot)
        checkPermissionsUI(txtLocationStatus, imgStatusDot, txtMicStatus, imgMicStatusDot)

        // If mic permission was revoked in OS settings, ensure switch is turned off
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            val prefs = getSharedPreferences("SOS_SETTINGS", MODE_PRIVATE)
            if (prefs.getBoolean("auto_record_audio_enabled", false)) {
                prefs.edit().putBoolean("auto_record_audio_enabled", false).apply()
                findViewById<SwitchMaterial>(R.id.switchAutoRecordAudio)?.isChecked = false
            }
        }
    }

    private fun checkPermissionsUI(
        txtLocationStatus: TextView?,
        imgStatusDot: ImageView?,
        txtMicStatus: TextView?,
        imgMicStatusDot: ImageView?
    ) {
        val fineLocationPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineLocationPermission) {
            txtLocationStatus?.text = "Location Permission: Granted"
            imgStatusDot?.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.success_green)
            )
        } else {
            txtLocationStatus?.text = "Location Permission: Denied"
            imgStatusDot?.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.error_red)
            )
        }

        val micPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (micPermission) {
            txtMicStatus?.text = "Microphone Permission: Granted"
            imgMicStatusDot?.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.success_green)
            )
        } else {
            txtMicStatus?.text = "Microphone Permission: Denied"
            imgMicStatusDot?.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.error_red)
            )
        }
    }
}
