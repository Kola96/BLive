package com.blive.tv.ui.play

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.blive.tv.R

/**
 * 设置选项 pill 横向列表适配器。
 */
class SettingsOptionAdapter(
    private val onItemClick: (SettingOptionPill) -> Unit
) : RecyclerView.Adapter<SettingsOptionAdapter.ViewHolder>() {

    private val items = mutableListOf<SettingOptionPill>()

    fun submitList(newItems: List<SettingOptionPill>) {
        items.clear()
        items.addAll(newItems)
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    fun selectedIndex(): Int = items.indexOfFirst { it.isSelected }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_setting_option, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameView: TextView = itemView.findViewById(R.id.option_name)
        private val statusView: TextView = itemView.findViewById(R.id.option_status)

        fun bind(item: SettingOptionPill) {
            nameView.text = item.label
            statusView.visibility = if (item.isSelected) View.VISIBLE else View.GONE
            itemView.isSelected = item.isSelected
            itemView.setOnClickListener { onItemClick(item) }
        }
    }
}
