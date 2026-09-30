package com.blive.tv.ui.settings

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.blive.tv.R
import com.blive.tv.ui.settings.model.SettingItem

/**
 * 设置项 Adapter（右侧内容列）。
 *
 * - CycleOption: 左右键 / 点击循环切换值
 * - Action: 点击 / CENTER 触发一次性动作
 * - Display: 纯展示，不可交互
 *
 * 左侧返回：onNavigateBack 回调给 Fragment，把焦点移回左侧分类列
 */
class SettingItemAdapter(
    private val onNavigateBack: () -> Unit
) : RecyclerView.Adapter<SettingItemAdapter.VH>() {

    private val items = mutableListOf<SettingItem>()

    fun submitList(newItems: List<SettingItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    /** 局部更新某项的显示值（如"检查更新"按钮文案变化） */
    fun updateActionValue(key: String, newValue: String) {
        val index = items.indexOfFirst { it.key == key }
        if (index < 0) return
        val old = items[index]
        if (old is SettingItem.Action) {
            items[index] = old.copy(value = newValue)
            notifyItemChanged(index)
        }
    }

    fun getItem(position: Int): SettingItem? = items.getOrNull(position)

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val label: TextView = itemView.findViewById(R.id.tv_setting_label)
        val value: TextView = itemView.findViewById(R.id.tv_setting_value)
        val arrowLeft: TextView = itemView.findViewById(R.id.tv_arrow_left)
        val arrowRight: TextView = itemView.findViewById(R.id.tv_arrow_right)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_settings_option, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        when (val item = items[position]) {
            is SettingItem.CycleOption -> bindCycleOption(holder, item, position)
            is SettingItem.Action -> bindAction(holder, item, position)
            is SettingItem.Display -> bindDisplay(holder, item)
        }
    }

    private fun bindCycleOption(holder: VH, item: SettingItem.CycleOption, position: Int) {
        holder.label.text = item.label
        holder.value.text = item.options.getOrNull(item.currentIndex) ?: ""
        holder.arrowLeft.visibility = View.VISIBLE
        holder.arrowRight.visibility = View.VISIBLE

        fun change(direction: Int) {
            val newIndex = (item.currentIndex + direction).floorMod(item.options.size)
            item.onChanged(newIndex)
        }

        holder.itemView.setOnClickListener { change(1) }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        change(-1)
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        change(1)
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }
    }

    private fun bindAction(holder: VH, item: SettingItem.Action, position: Int) {
        holder.label.text = item.label
        holder.value.text = item.value
        holder.arrowLeft.visibility = View.GONE
        holder.arrowRight.visibility = View.GONE

        holder.itemView.setOnClickListener { item.onClick() }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                item.onClick()
                return@setOnKeyListener true
            }
            false
        }
    }

    private fun bindDisplay(holder: VH, item: SettingItem.Display) {
        holder.label.text = item.label
        holder.value.text = item.value
        holder.arrowLeft.visibility = View.GONE
        holder.arrowRight.visibility = View.GONE

        holder.itemView.setOnClickListener(null)
        holder.itemView.setOnKeyListener(null)
    }

    private fun Int.floorMod(mod: Int): Int = ((this % mod) + mod) % mod
}
