package com.example.emergencysoscommunicationapp

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView adapter for listing, playing, and deleting emergency audio recordings.
 */
class AudioRecordingsAdapter(
    private var items: List<AudioRecordingManager.AudioRecordingItem>,
    private val onPlayClicked: (AudioRecordingManager.AudioRecordingItem, Boolean) -> Unit,
    private val onDeleteClicked: (AudioRecordingManager.AudioRecordingItem) -> Unit
) : RecyclerView.Adapter<AudioRecordingsAdapter.ViewHolder>() {

    var currentPlayingPath: String? = null

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtDate: TextView = view.findViewById(R.id.txtRecordingDate)
        val txtMeta: TextView = view.findViewById(R.id.txtRecordingMeta)
        val txtSession: TextView = view.findViewById(R.id.txtRecordingSession)
        val btnPlayPause: ImageButton = view.findViewById(R.id.btnPlayPause)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_audio_recording, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.txtDate.text = item.formattedDate
        holder.txtMeta.text = "${item.formattedDuration} • ${item.formattedSize}"
        holder.txtSession.text = "Session: ${item.sessionId}"

        val isPlaying = item.file.absolutePath == currentPlayingPath
        if (isPlaying) {
            holder.btnPlayPause.setImageResource(R.drawable.ic_pause)
            holder.btnPlayPause.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(holder.itemView.context, R.color.error_red)
            )
        } else {
            holder.btnPlayPause.setImageResource(R.drawable.ic_play)
            holder.btnPlayPause.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(holder.itemView.context, R.color.primary)
            )
        }

        holder.btnPlayPause.setOnClickListener {
            onPlayClicked(item, isPlaying)
        }

        holder.btnDelete.setOnClickListener {
            onDeleteClicked(item)
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<AudioRecordingManager.AudioRecordingItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun setPlayingPath(path: String?) {
        currentPlayingPath = path
        notifyDataSetChanged()
    }
}
