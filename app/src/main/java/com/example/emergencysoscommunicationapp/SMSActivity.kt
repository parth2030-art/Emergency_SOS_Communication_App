package com.example.emergencysoscommunicationapp

import android.os.Bundle
import android.telephony.SmsManager
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SMSActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sms)

        val etMessage =
            findViewById<EditText>(R.id.etMessage)

        val btnSend =
            findViewById<Button>(R.id.btnSend)

        btnSend.setOnClickListener {

            val message =
                etMessage.text.toString().trim()

            if (message.isEmpty()) {

                Toast.makeText(
                    this,
                    "Enter a message",
                    Toast.LENGTH_SHORT
                ).show()

                return@setOnClickListener
            }

            val contacts =
                ContactStorage.getContacts(this)

            if (contacts.isEmpty()) {

                Toast.makeText(
                    this,
                    "No contacts saved",
                    Toast.LENGTH_SHORT
                ).show()

                return@setOnClickListener
            }

            val smsManager =
                SmsManager.getDefault()

            for (contact in contacts) {

                smsManager.sendTextMessage(
                    contact.phone,
                    null,
                    message,
                    null,
                    null
                )
            }

            Toast.makeText(
                this,
                "Message Sent",
                Toast.LENGTH_LONG
            ).show()

            finish()
        }
    }
}