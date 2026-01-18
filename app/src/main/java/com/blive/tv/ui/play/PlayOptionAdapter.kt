package com.blive.tv.ui.play

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.blive.tv.R

class PlayOptionAdapter<T : Any>(
    private var items: List<T>,
    private val getName: (T) -> String,
    private val isSelected: (T) -> Boolean,
    private val onItemClick: (T) -> Unit
) : RecyclerView.Adapter<PlayOptionAdapter<T>.ViewHolder>() {

    companion object {
        private const val TAG = "PlayOptionAdapter"
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val nameText: TextView = itemView.findViewById(R.id.option_name)
        val statusText: TextView = itemView.findViewById(R.id.option_status)

        init {
            Log.d(TAG, "ViewHolder init - itemView focusable: ${itemView.isFocusable}, focusableInTouchMode: ${itemView.isFocusableInTouchMode}")
            
            itemView.setOnClickListener {
                val position = adapterPosition
                Log.d(TAG, "onClick triggered - position: $position, NO_POSITION: ${RecyclerView.NO_POSITION}")
                if (position != RecyclerView.NO_POSITION) {
                    Log.d(TAG, "onClick - calling onItemClick for item: ${getName(items[position])}")
                    onItemClick(items[position])
                } else {
                    Log.w(TAG, "onClick - invalid position")
                }
            }
            
            itemView.setOnFocusChangeListener { _, hasFocus ->
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    Log.d(TAG, "onFocusChange - position: $position, hasFocus: $hasFocus")
                    if (hasFocus) {
                        itemView.scaleX = 1.0f
                        itemView.scaleY = 1.0f
                        Log.d(TAG, "onFocusChange - focused")
                    } else {
                        itemView.scaleX = 1.0f
                        itemView.scaleY = 1.0f
                        Log.d(TAG, "onFocusChange - unfocused")
                    }
                } else {
                    Log.d(TAG, "onFocusChange - invalid position: $position, hasFocus: $hasFocus")
                }
            }
        }

        fun bind(item: T) {
            val name = getName(item)
            nameText.text = name
            statusText.visibility = if (isSelected(item)) View.VISIBLE else View.GONE
            
            if (isSelected(item)) {
                nameText.setTextColor(0xFFFF4081.toInt())
            } else {
                nameText.setTextColor(0xFFFFFFFF.toInt())
            }
            
            Log.d(TAG, "bind - item: $name, isSelected: ${isSelected(item)}")
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        Log.d(TAG, "onCreateViewHolder - viewType: $viewType")
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_play_option, parent, false)
        Log.d(TAG, "onCreateViewHolder - view created, focusable: ${view.isFocusable}, focusableInTouchMode: ${view.isFocusableInTouchMode}")
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        Log.d(TAG, "onBindViewHolder - position: $position")
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size
    
    fun updateItems(newItems: List<T>) {
        items = newItems
        notifyDataSetChanged()
    }
}
