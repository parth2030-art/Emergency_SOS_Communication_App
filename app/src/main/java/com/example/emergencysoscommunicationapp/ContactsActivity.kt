package com.example.emergencysoscommunicationapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ContactsActivity : BaseActivity() {

    private lateinit var recyclerContacts: RecyclerView
    private lateinit var btnAddContact: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contacts)

        // Common Back Button from BaseActivity
        enableBackButton("Emergency Contacts")

        recyclerContacts = findViewById(R.id.recyclerContacts)
        btnAddContact = findViewById(R.id.btnAddContact)

        recyclerContacts.layoutManager =
            LinearLayoutManager(this)

        loadContacts()

        btnAddContact.setOnClickListener {
            startActivity(
                Intent(
                    this,
                    AddContactActivity::class.java
                )
            )
        }
    }

    override fun onResume() {
        super.onResume()
        loadContacts()
    }

    private fun loadContacts() {

        val contacts = ContactStorage
            .getContacts(this)
            .toMutableList()

        recyclerContacts.adapter =
            ContactAdapter(

                contacts,

                onDelete = { contact ->

                    ContactStorage.deleteContact(
                        this,
                        contact
                    )

                    loadContacts()
                },

                onEdit = { contact ->

                    val intent =
                        Intent(
                            this,
                            AddContactActivity::class.java
                        )

                    intent.putExtra(
                        "name",
                        contact.name
                    )

                    intent.putExtra(
                        "phone",
                        contact.phone
                    )

                    intent.putExtra(
                        "relation",
                        contact.relation
                    )

                    startActivity(intent)
                }
            )
    }
}