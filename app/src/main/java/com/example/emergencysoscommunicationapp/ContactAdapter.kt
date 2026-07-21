package com.example.emergencysoscommunicationapp

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
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

        val txtRelation: TextView =
            itemView.findViewById(R.id.txtRelation)

        val txtAvatarInitial: TextView =
            itemView.findViewById(R.id.txtAvatarInitial)

        val btnDelete: ImageButton =
            itemView.findViewById(R.id.btnDelete)

        val btnEdit: ImageButton =
            itemView.findViewById(R.id.btnEdit)

        val btnCall: ImageButton =
            itemView.findViewById(R.id.btnCall)
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
        holder.txtRelation.text = contact.relation.ifEmpty { "Relation" }

        // Avatar Initial
        val initial = if (contact.name.isNotBlank()) {
            val parts = contact.name.trim().split("\\s+".toRegex())
            if (parts.size >= 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                "${parts[0][0]}${parts[1][0]}".uppercase()
            } else if (parts.isNotEmpty() && parts[0].isNotEmpty()) {
                "${parts[0][0]}".uppercase()
            } else {
                "?"
            }
        } else {
            "?"
        }
        holder.txtAvatarInitial.text = initial

        holder.btnCall.setOnClickListener {
            val intent = Intent(
                Intent.ACTION_DIAL,
                Uri.parse("tel:${contact.phone}")
            )
            holder.itemView.context.startActivity(intent)
        }

        holder.btnEdit.setOnClickListener {
            onEdit(contact)
        }

        holder.btnDelete.setOnClickListener {
            onDelete(contact)
        }
    }

    override fun getItemCount(): Int {
        return contacts.size
    }
}