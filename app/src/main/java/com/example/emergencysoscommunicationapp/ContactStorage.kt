package com.example.emergencysoscommunicationapp

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object ContactStorage {

    private const val PREF_NAME = "SOS_CONTACTS"
    private const val KEY_CONTACTS = "contacts"

    fun saveContact(context: Context, contact: Contact) {

        val contacts = getContacts(context).toMutableList()

        contacts.add(contact)

        val json = Gson().toJson(contacts)

        context.getSharedPreferences(
            PREF_NAME,
            Context.MODE_PRIVATE
        ).edit()
            .putString(KEY_CONTACTS, json)
            .apply()
    }

    fun deleteContact(
        context: Context,
        contact: Contact
    ) {

        val contacts = getContacts(context).toMutableList()

        contacts.remove(contact)

        val json = Gson().toJson(contacts)

        context.getSharedPreferences(
            PREF_NAME,
            Context.MODE_PRIVATE
        ).edit()
            .putString(KEY_CONTACTS, json)
            .apply()
    }

    fun getContacts(context: Context): List<Contact> {

        val json = context.getSharedPreferences(
            PREF_NAME,
            Context.MODE_PRIVATE
        ).getString(KEY_CONTACTS, null)

        if (json == null)
            return emptyList()

        val type =
            object : TypeToken<List<Contact>>() {}.type

        return Gson().fromJson(json, type)
    }

    fun updateContact(
        context: Context,
        oldContact: Contact,
        newContact: Contact
    ) {

        val contacts = getContacts(context).toMutableList()

        val index = contacts.indexOf(oldContact)

        if (index != -1) {
            contacts[index] = newContact
        }

        val json = Gson().toJson(contacts)

        context.getSharedPreferences(
            PREF_NAME,
            Context.MODE_PRIVATE
        ).edit()
            .putString(KEY_CONTACTS, json)
            .apply()
    }
}