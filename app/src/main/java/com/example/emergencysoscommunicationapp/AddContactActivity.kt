package com.example.emergencysoscommunicationapp

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import com.google.android.material.textfield.TextInputEditText

class AddContactActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_contact)

        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        btnBack.setOnClickListener {
            finish()
        }

        val etName = findViewById<TextInputEditText>(R.id.etName)
        val etPhone = findViewById<TextInputEditText>(R.id.etPhone)
        val etRelation = findViewById<AutoCompleteTextView>(R.id.etRelation)
        val btnSave = findViewById<Button>(R.id.btnSave)
        //new line added

        // Set up the Relationship Dropdown Menu
        val relations = arrayOf("Family", "Friend", "Spouse", "Work", "Other")
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, relations)
        etRelation.setAdapter(adapter)

        val oldName = intent.getStringExtra("name")
        val oldPhone = intent.getStringExtra("phone")
        val oldRelation = intent.getStringExtra("relation")

        etName.setText(oldName)
        etPhone.setText(oldPhone)
        if (oldRelation != null) {
            etRelation.setText(oldRelation, false)
        }

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