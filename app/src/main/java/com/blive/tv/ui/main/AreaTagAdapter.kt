package com.blive.tv.ui.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.blive.tv.R

data class AreaTagItem(
    val id: Int,
    val name: String
)

class AreaTagAdapter(
    private val isLevel1: Boolean,
    private val onItemClick: (Int) -> Unit,
    private val onNavigateBack: () -> Unit
) : RecyclerView.Adapter<AreaTagAdapter.ViewHolder>() {

    init {
        setHasStableIds(true)
    }

    private var items: List<AreaTagItem> = emptyList()
    private var selectedId: Int = -1

    fun updateData(newItems: List<AreaTagItem>, selectedId: Int) {
        val oldItems = this.items
        val oldSelectedId = this.selectedId
        this.items = newItems
        this.selectedId = selectedId

        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldItems.size
            override fun getNewListSize(): Int = newItems.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldItems[oldItemPosition].id == newItems[newItemPosition].id
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldItem = oldItems[oldItemPosition]
                val newItem = newItems[newItemPosition]
                val wasSelected = oldItem.id == oldSelectedId
                val isSelected = newItem.id == selectedId
                return oldItem.name == newItem.name && wasSelected == isSelected
            }
        })
        diffResult.dispatchUpdatesTo(this)
    }

    override fun getItemId(position: Int): Long {
        return items[position].id.toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layoutId = if (isLevel1) R.layout.item_area_tag_level1 else R.layout.item_area_tag_level2
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item, item.id == selectedId)
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView as TextView

        fun bind(item: AreaTagItem, isSelected: Boolean) {
            textView.text = item.name
            textView.isSelected = isSelected

            textView.setOnClickListener {
                onItemClick(item.id)
            }

            textView.setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (adapterPosition == 0) {
                        onNavigateBack()
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }
    }
}