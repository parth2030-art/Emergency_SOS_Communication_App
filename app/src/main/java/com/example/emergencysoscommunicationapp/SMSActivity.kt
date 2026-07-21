package com.example.emergencysoscommunicationapp

import android.os.Bundle
import android.telephony.SmsManager
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

            val smsManager = SmsManager.getDefault()
            val finalSms = "🚨 EMERGENCY SOS (TEST) 🚨\n\n$message\n\nLocation link will be attached on trigger."

            for (contact in contacts) {
                try {
                    smsManager.sendTextMessage(
                        contact.phone,
                        null,
                        finalSms,
                        null,
                        null
                    )
                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        "Failed: ${contact.phone}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            Toast.makeText(
                this,
                "Test Message Sent",
                Toast.LENGTH_LONG
            ).show()

            finish()
        }
    }
}