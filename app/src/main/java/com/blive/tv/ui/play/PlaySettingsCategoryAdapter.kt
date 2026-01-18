package com.blive.tv.ui.play

import android.graphics.Color
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.blive.tv.R

class PlaySettingsCategoryAdapter(
    private var items: List<SettingsItem>,
    private val onItemClick: (SettingsItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TAG = "CategoryAdapter"
        private const val TYPE_CATEGORY = 0
        private const val TYPE_OPTION = 1
    }

    // Category ViewHolder
    inner class CategoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val nameText: TextView = itemView.findViewById(R.id.category_name)
        val valueText: TextView = itemView.findViewById(R.id.category_value)
        val arrowText: TextView = itemView.findViewById(R.id.category_arrow)

        init {
            itemView.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(items[position])
                }
            }
            
            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    itemView.scaleX = 1.02f
                    itemView.scaleY = 1.02f
                    // option_selector_background.xml 已经处理了 focus 状态，这里不需要手动设置背景
                    // 只需要处理缩放动画
                    itemView.isSelected = true
                } else {
                    itemView.scaleX = 1.0f
                    itemView.scaleY = 1.0f
                    itemView.isSelected = false
                }
            }
        }

        fun bind(category: PlaySettingsCategory) {
            nameText.text = category.name
            valueText.text = category.currentValue
            arrowText.text = if (category.isExpanded) "▼" else "▶"
            
            // 如果是展开状态，高亮显示
            if (category.isExpanded) {
                nameText.setTextColor(Color.parseColor("#FF4081"))
            } else {
                nameText.setTextColor(Color.WHITE)
            }
        }
    }

    // Option ViewHolder
    inner class OptionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val nameText: TextView = itemView.findViewById(R.id.option_name)
        val statusText: TextView = itemView.findViewById(R.id.option_status)

        init {
            itemView.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(items[position])
                }
            }
            
            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    itemView.scaleX = 1.02f
                    itemView.scaleY = 1.02f
                    // option_selector_background.xml 已经处理了 focus 状态
                    itemView.isSelected = true
                } else {
                    itemView.scaleX = 1.0f
                    itemView.scaleY = 1.0f
                    itemView.isSelected = false
                }
            }
        }

        fun bind(option: PlaySettingsOption) {
            nameText.text = option.name
            
            if (option.isSelected) {
                statusText.visibility = View.VISIBLE
                statusText.text = "✓"
                nameText.setTextColor(Color.parseColor("#FF4081"))
            } else {
                statusText.visibility = View.GONE
                nameText.setTextColor(Color.parseColor("#DDDDDD"))
            }
            
            // 缩进显示，体现层级
            // 每次bind时重置padding，避免重复叠加
            val context = itemView.context
            val density = context.resources.displayMetrics.density
            val startPadding = (40 * density).toInt() // 8dp(default) + 32dp(indent)
            val topBottomPadding = (12 * density).toInt()
            val endPadding = (8 * density).toInt()
            
            itemView.setPadding(startPadding, topBottomPadding, endPadding, topBottomPadding)
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is PlaySettingsCategory -> TYPE_CATEGORY
            is PlaySettingsOption -> TYPE_OPTION
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_CATEGORY) {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_play_option_category, parent, false)
            CategoryViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_play_option, parent, false)
            OptionViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is PlaySettingsCategory -> (holder as CategoryViewHolder).bind(item)
            is PlaySettingsOption -> (holder as OptionViewHolder).bind(item)
        }
    }

    override fun getItemCount(): Int = items.size
    
    fun updateItems(newItems: List<SettingsItem>) {
        items = newItems
        notifyDataSetChanged()
    }
    
    fun getItems(): List<SettingsItem> = items
    
    // 为了兼容旧代码，提供 helper 方法获取 Categories
    fun getCategories(): List<PlaySettingsCategory> {
        return items.filterIsInstance<PlaySettingsCategory>()
    }
}
