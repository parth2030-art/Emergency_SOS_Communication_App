package com.example.emergencysoscommunicationapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ContactAdapter(
    private val contacts: List<Contact>,
    private val onDelete: (Contact) -> Unit,
    private val onEdit: (Contact) -> Unit
) : RecyclerView.Adapter<ContactAdapter.ContactViewHolder>() {

    class ContactViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        val txtName: TextView =
            itemView.findViewById(R.id.txtName)

        val txtPhone: TextView =
            itemView.findViewById(R.id.txtPhone)

        val btnDelete: Button =
            itemView.findViewById(R.id.btnDelete)

        val btnEdit: Button =
            itemView.findViewById(R.id.btnEdit)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ContactViewHolder {

        val view = LayoutInflater.from(parent.context)
            .inflate(
                R.layout.contact_item,
                parent,
                false
            )

        return ContactViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: ContactViewHolder,
        position: Int
    ) {

        val contact = contacts[position]

        holder.txtName.text = contact.name
        holder.txtPhone.text = contact.phone

        holder.btnEdit.setOnClickListener {
            onEdit(contact)
        }

        holder.btnDelete.setOnClickListener {
            android.widget.Toast.makeText(
                holder.itemView.context,
                "Deleting ${contact.name}",
                android.widget.Toast.LENGTH_SHORT
            ).show()

            onDelete(contact)
        }
    }

    override fun getItemCount(): Int {
        return contacts.size
    }
}