package com.example.emergencysoscommunicationapp

import android.os.Bundle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class CallContactsActivity : BaseActivity() {

    private lateinit var recyclerCallContacts: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_call_contacts)

        val btnBack = findViewById<android.widget.ImageButton>(R.id.btnBack)
        btnBack.setOnClickListener {
            finish()
        }

        recyclerCallContacts =
            findViewById(R.id.recyclerCallContacts)

        recyclerCallContacts.layoutManager =
            LinearLayoutManager(this)

        recyclerCallContacts.adapter =
            CallContactAdapter(
                ContactStorage.getContacts(this)
            )
    }
}