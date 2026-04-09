package com.blive.tv.ui.main

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.blive.tv.R
import com.bumptech.glide.Glide

class LiveRoomAdapter(
    private var columnCount: Int,
    private val onFirstRowUp: (Int) -> Unit,
    private val onRoomClicked: (LiveRoom) -> Unit,
    private val onNavigateToTab: () -> Unit,
    private val onRoomFocused: (Int, Long) -> Unit
) : RecyclerView.Adapter<LiveRoomAdapter.LiveRoomViewHolder>() {
    private var liveRooms: List<LiveRoom> = emptyList()

    init {
        setHasStableIds(true)
    }

    fun getCurrentData(): List<LiveRoom> = liveRooms

    fun updateData(newLiveRooms: List<LiveRoom>) {
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = liveRooms.size
            override fun getNewListSize(): Int = newLiveRooms.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return liveRooms[oldItemPosition].roomId == newLiveRooms[newItemPosition].roomId
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return liveRooms[oldItemPosition] == newLiveRooms[newItemPosition]
            }
        }
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        liveRooms = newLiveRooms
        diffResult.dispatchUpdatesTo(this)
    }

    fun getRoomIdAt(position: Int): Long? {
        if (position !in liveRooms.indices) {
            return null
        }
        return liveRooms[position].roomId
    }

    fun getPositionByRoomId(roomId: Long): Int {
        return liveRooms.indexOfFirst { it.roomId == roomId }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LiveRoomViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_live_room, parent, false)
        return LiveRoomViewHolder(view)
    }

    override fun onBindViewHolder(holder: LiveRoomViewHolder, position: Int) {
        holder.bind(liveRooms[position])
    }

    override fun getItemId(position: Int): Long {
        return liveRooms[position].roomId
    }

    override fun getItemCount(): Int = liveRooms.size

    inner class LiveRoomViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val coverImage = view.findViewById<ImageView>(R.id.cover_image)
        private val anchorName = view.findViewById<TextView>(R.id.anchor_name)
        private val roomTitle = view.findViewById<TextView>(R.id.room_title)
        private val areaName = view.findViewById<TextView>(R.id.area_name)
        private val viewerCountContainer = view.findViewById<LinearLayout>(R.id.viewer_count_container)
        private val viewerCountText = view.findViewById<TextView>(R.id.viewer_count_text)

        init {
            itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        if (bindingAdapterPosition in 0 until columnCount) {
                            onFirstRowUp(bindingAdapterPosition)
                            return@setOnKeyListener true
                        }
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        if (bindingAdapterPosition == itemCount - 1) {
                            return@setOnKeyListener true
                        }
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        if (bindingAdapterPosition % columnCount == 0) {
                            onNavigateToTab()
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
                    onRoomClicked(liveRoom)
                }
            }

            itemView.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    return@OnFocusChangeListener
                }
                val pos = bindingAdapterPosition
                if (pos in liveRooms.indices) {
                    onRoomFocused(pos, liveRooms[pos].roomId)
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

            anchorName.text = liveRoom.anchorName
            roomTitle.text = liveRoom.roomTitle
            areaName.text = liveRoom.areaName
            val directText = liveRoom.viewerCountText.orEmpty()
            if (directText.isNotBlank()) {
                viewerCountContainer.visibility = View.VISIBLE
                viewerCountText.text = directText
            } else if (liveRoom.viewerCount > 0L) {
                viewerCountContainer.visibility = View.VISIBLE
                viewerCountText.text = formatViewerCount(liveRoom.viewerCount)
            } else {
                viewerCountContainer.visibility = View.GONE
            }
        }
    }

    private fun formatViewerCount(count: Long): String {
        if (count < 10000L) {
            return count.toString()
        }
        val value = count / 10000.0
        val formatted = String.format("%.1f", value)
        return if (formatted.endsWith(".0")) {
            "${formatted.dropLast(2)}万"
        } else {
            "${formatted}万"
        }
    }
}
