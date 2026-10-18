package com.example.emergencysoscommunicationapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText

class SMSActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sms)

        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        btnBack.setOnClickListener {
            finish()
        }

        val etMessage = findViewById<TextInputEditText>(R.id.etMessage)
        val txtPreview = findViewById<TextView>(R.id.txtPreview)
        val btnSend = findViewById<Button>(R.id.btnSend)

        val prefs = getSharedPreferences("SOS_SETTINGS", MODE_PRIVATE)
        val savedMsg = prefs.getString("custom_msg", "I need help immediately.")
        etMessage.setText(savedMsg)

        fun updatePreview(text: String) {
            val previewText = "🚨 EMERGENCY SOS 🚨\n\n$text\n\nMy Current Location:\nhttps://maps.google.com/?q=0.0,0.0"
            txtPreview.text = previewText
        }

        updatePreview(savedMsg ?: "I need help immediately.")

        etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updatePreview(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnSend.setOnClickListener {

            val message = etMessage.text.toString().trim()

            if (message.isEmpty()) {
                Toast.makeText(
                    this,
                    "Enter a message",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            // Check SEND_SMS permission before attempting to send
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(
                    this,
                    "SMS Permission is required to send messages",
                    Toast.LENGTH_LONG
                ).show()
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.SEND_SMS),
                    101
                )
                return@setOnClickListener
            }

            val contacts = ContactStorage.getContacts(this)

            if (contacts.isEmpty()) {
                Toast.makeText(
                    this,
                    "No contacts saved",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            // Save the custom message to settings
            prefs.edit().putString("custom_msg", message).apply()

            val smsManager = SosStateManager.getSmsManager(this)
            val finalSms = "🚨 EMERGENCY SOS (TEST) 🚨\n\n$message\n\nLocation link will be attached on trigger."
            val parts = smsManager.divideMessage(finalSms)

            var sentCount = 0
            for (contact in contacts) {
                try {
                    smsManager.sendMultipartTextMessage(
                        contact.phone,
                        null,
                        parts,
                        null,
                        null
                    )
                    sentCount++
                } catch (e: SecurityException) {
                    Log.e("SOS_NOTIFICATION", "SecurityException sending SMS to ${contact.phone}: ${e.message}")
                    Toast.makeText(
                        this,
                        "SMS Permission Denied",
                        Toast.LENGTH_SHORT
                    ).show()
                    break
                } catch (e: Exception) {
                    Log.e("SOS_NOTIFICATION", "Failed to send SMS to ${contact.phone}: ${e.message}")
                    Toast.makeText(
                        this,
                        "Failed: ${contact.phone}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            if (sentCount > 0) {
                Toast.makeText(
                    this,
                    "Test Message Sent to $sentCount contact(s)",
                    Toast.LENGTH_LONG
                ).show()
            }

            finish()
        }
    }
}