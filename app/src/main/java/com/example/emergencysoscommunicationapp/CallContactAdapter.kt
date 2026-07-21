package com.example.emergencysoscommunicationapp

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class CallContactAdapter(
    private val contacts: List<Contact>
) : RecyclerView.Adapter<CallContactAdapter.ViewHolder>() {

    class ViewHolder(view: View)
        : RecyclerView.ViewHolder(view) {

        val txtName: TextView =
            view.findViewById(R.id.txtName)

        val txtPhone: TextView =
            view.findViewById(R.id.txtPhone)

        val txtRelation: TextView =
            view.findViewById(R.id.txtRelation)

        val txtAvatarInitial: TextView =
            view.findViewById(R.id.txtAvatarInitial)

        val btnCall: ImageButton =
            view.findViewById(R.id.btnCall)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ViewHolder {

        val view =
            LayoutInflater.from(parent.context)
                .inflate(
                    R.layout.call_contact_item,
                    parent,
                    false
                )

        return ViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
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

            holder.itemView.context
                .startActivity(intent)
        }
    }

    override fun getItemCount(): Int {
        return contacts.size
    }
}