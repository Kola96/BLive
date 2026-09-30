package com.blive.tv.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.blive.tv.R
import com.blive.tv.ui.settings.model.SettingsCategory

/**
 * 设置页左侧分类列表 Adapter。
 *
 * 交互：
 * - DPAD_UP/DOWN 在 RecyclerView 内移动焦点
 * - onCategoryFocused: 焦点变化即触发（无需 CENTER），通知外部切换右侧内容
 * - 选中分类项显示高亮背景（isActivated = true）
 */
class CategoryAdapter(
    private val categories: List<SettingsCategory>,
    private val onCategoryFocused: (SettingsCategory) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.VH>() {

    /** 当前获得焦点的分类（同时也是"选中"分类） */
    var focusedCategory: SettingsCategory = categories.first()
        private set

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val label: TextView = itemView.findViewById(R.id.tv_category_label)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_settings_category, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = categories.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val category = categories[position]
        holder.label.text = category.label

        // 焦点变化 → 切换分类 + 更新右侧内容
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                if (focusedCategory != category) {
                    val previousCategory = focusedCategory
                    focusedCategory = category
                    // 只刷新受影响的两个 item，避免全量重绑导致焦点丢失
                    val prevIndex = categories.indexOf(previousCategory)
                    val newIndex = categories.indexOf(category)
                    if (prevIndex >= 0) notifyItemChanged(prevIndex)
                    if (newIndex >= 0) notifyItemChanged(newIndex)
                    onCategoryFocused(category)
                }
            }
        }

        // 选中态（焦点所在项）：isActivated 驱动 selector 背景
        holder.itemView.isActivated = (category == focusedCategory)
    }
}
