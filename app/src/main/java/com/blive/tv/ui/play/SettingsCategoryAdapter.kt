package com.blive.tv.ui.play

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.blive.tv.R

/**
 * 设置分类 chip 横向列表适配器。
 * 焦点移动实时回调（用于联动刷新选项行），点击回调用于进入选项/直接切换。
 */
class SettingsCategoryAdapter(
    private val onItemClick: (SettingCategoryChip) -> Unit,
    private val onItemFocused: (SettingCategoryChip) -> Unit
) : RecyclerView.Adapter<SettingsCategoryAdapter.ViewHolder>() {

    private val items = mutableListOf<SettingCategoryChip>()

    fun submitList(newItems: List<SettingCategoryChip>) {
        items.clear()
        items.addAll(newItems)
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    fun indexOf(categoryId: String): Int = items.indexOfFirst { it.id == categoryId }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_setting_category, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameView: TextView = itemView.findViewById(R.id.category_name)
        private val valueView: TextView = itemView.findViewById(R.id.category_value)

        fun bind(item: SettingCategoryChip) {
            nameView.text = item.name
            valueView.text = item.value
            itemView.setOnClickListener { onItemClick(item) }
            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onItemFocused(item)
            }
        }
    }
}
