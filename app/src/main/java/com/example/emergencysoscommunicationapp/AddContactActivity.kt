package com.example.emergencysoscommunicationapp

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast

class AddContactActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_contact)

        // Common Back Button from BaseActivity
        enableBackButton("Add Contact")

        val etName = findViewById<EditText>(R.id.etName)
        val etPhone = findViewById<EditText>(R.id.etPhone)
        val etRelation = findViewById<EditText>(R.id.etRelation)
        val btnSave = findViewById<Button>(R.id.btnSave)

        val oldName = intent.getStringExtra("name")
        val oldPhone = intent.getStringExtra("phone")
        val oldRelation = intent.getStringExtra("relation")

        etName.setText(oldName)
        etPhone.setText(oldPhone)
        etRelation.setText(oldRelation)

        btnSave.setOnClickListener {

            val name = etName.text.toString().trim()
            val phone = etPhone.text.toString()
                .replace(" ", "")
                .replace("-", "")
                .trim()

            if (phone.length < 10) {

                Toast.makeText(
                    this,
                    "Enter valid phone number",
                    Toast.LENGTH_SHORT
                ).show()

                return@setOnClickListener
            }
            val relation = etRelation.text.toString().trim()

            if (name.isEmpty() || phone.isEmpty()) {
                Toast.makeText(
                    this,
                    "Enter all details",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            val newContact = Contact(
                name,
                phone,
                relation
            )

            if (oldName != null && oldPhone != null) {

                val oldContact = Contact(
                    oldName,
                    oldPhone,
                    oldRelation ?: ""
                )

                ContactStorage.updateContact(
                    this,
                    oldContact,
                    newContact
                )

                Toast.makeText(
                    this,
                    "Contact Updated",
                    Toast.LENGTH_SHORT
                ).show()

            } else {

                ContactStorage.saveContact(
                    this,
                    newContact
                )

                Toast.makeText(
                    this,
                    "Contact Saved",
                    Toast.LENGTH_SHORT
                ).show()
            }

            finish()
        }
    }
}