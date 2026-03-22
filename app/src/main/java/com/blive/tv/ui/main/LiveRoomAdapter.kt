package com.blive.tv.ui.main

import android.content.Intent
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.blive.tv.R
import com.blive.tv.ui.play.LivePlayActivity
import com.bumptech.glide.Glide

class LiveRoomAdapter(
    private val onFirstRowUp: (Int) -> Unit,
    private val onRoomClicked: (Long) -> Unit
) : RecyclerView.Adapter<LiveRoomAdapter.LiveRoomViewHolder>() {
    private var liveRooms: List<LiveRoom> = emptyList()

    fun updateData(newLiveRooms: List<LiveRoom>) {
        liveRooms = newLiveRooms
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LiveRoomViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_live_room, parent, false)
        return LiveRoomViewHolder(view)
    }

    override fun onBindViewHolder(holder: LiveRoomViewHolder, position: Int) {
        holder.bind(liveRooms[position])
    }

    override fun getItemCount(): Int = liveRooms.size

    inner class LiveRoomViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val coverImage = view.findViewById<ImageView>(R.id.cover_image)
        private val anchorAvatar = view.findViewById<ImageView>(R.id.anchor_avatar)
        private val anchorName = view.findViewById<TextView>(R.id.anchor_name)
        private val roomTitle = view.findViewById<TextView>(R.id.room_title)
        private val areaName = view.findViewById<TextView>(R.id.area_name)

        init {
            itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        if (bindingAdapterPosition in 0..3) {
                            onFirstRowUp(bindingAdapterPosition)
                            return@setOnKeyListener true
                        }
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        if (bindingAdapterPosition == itemCount - 1) {
                            return@setOnKeyListener true
                        }
                    }
                }
                false
            }

            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos in liveRooms.indices) {
                    val liveRoom = liveRooms[pos]
                    onRoomClicked(liveRoom.roomId)
                    val intent = Intent(itemView.context, LivePlayActivity::class.java)
                    intent.putExtra("room_id", liveRoom.roomId)
                    itemView.context.startActivity(intent)
                }
            }
        }

        fun bind(liveRoom: LiveRoom) {
            if (liveRoom.coverUrl.isNotEmpty()) {
                Glide.with(itemView.context)
                    .load(liveRoom.coverUrl)
                    .placeholder(android.R.drawable.ic_media_play)
                    .error(android.R.drawable.ic_media_play)
                    .into(coverImage)
            } else {
                coverImage.setImageResource(android.R.drawable.ic_media_play)
            }

            if (liveRoom.anchorAvatar.isNotEmpty()) {
                Glide.with(itemView.context)
                    .load(liveRoom.anchorAvatar)
                    .placeholder(android.R.drawable.ic_media_play)
                    .error(android.R.drawable.ic_media_play)
                    .circleCrop()
                    .into(anchorAvatar)
            } else {
                Glide.with(itemView.context)
                    .load(android.R.drawable.ic_media_play)
                    .circleCrop()
                    .into(anchorAvatar)
            }

            anchorName.text = liveRoom.anchorName
            roomTitle.text = liveRoom.roomTitle
            areaName.text = liveRoom.areaName
        }
    }
}
