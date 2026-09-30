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
 * 交互模式（锁定式调节）：
 * - 焦点移到设置项后按确认键锁定该设置项；锁定状态下左右键切换值
 * - 返回键解除锁定；未锁定时方向键正常移动焦点（先解锁才能移动焦点）
 * - Action（检查更新）：确认键直接触发动作，无锁定概念
 * - Display（当前版本）：只读展示，不参与焦点导航
 *
 * 值变更直接更新对应 ViewHolder 的数据与视图，不经 notify —— 在按键回调里
 * notifyDataSetChanged 会触发焦点项重绑/动画/焦点漂移级联，曾在真机上崩溃。
 */
class SettingItemAdapter : RecyclerView.Adapter<SettingItemAdapter.VH>() {

    private val items = mutableListOf<SettingItem>()

    /** 当前锁定的设置项位置，-1 表示未锁定 */
    private var lockedPosition = -1

    private var attachedRecyclerView: RecyclerView? = null

    fun getItem(position: Int): SettingItem? = items.getOrNull(position)

    fun submitList(newItems: List<SettingItem>) {
        items.clear()
        items.addAll(newItems)
        lockedPosition = -1
        notifySafely { notifyDataSetChanged() }
    }

    /** 局部更新某项的显示值（如"检查更新"按钮文案变化） */
    fun updateActionValue(key: String, newValue: String) {
        val index = items.indexOfFirst { it.key == key }
        if (index < 0) return
        val old = items[index]
        if (old is SettingItem.Action) {
            items[index] = old.copy(value = newValue)
            notifySafely { notifyItemChanged(index) }
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        attachedRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        attachedRecyclerView = null
    }

    /** RecyclerView 布局/动画进行中时延迟 notify，避免 IllegalStateException */
    private fun notifySafely(action: () -> Unit) {
        val rv = attachedRecyclerView
        if (rv != null && (rv.isComputingLayout || rv.isAnimating)) {
            rv.post(action)
        } else {
            action()
        }
    }

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
            is SettingItem.CycleOption -> bindCycleOption(holder, position)
            is SettingItem.Action -> bindAction(holder, item)
            is SettingItem.Display -> bindDisplay(holder, item)
        }
    }

    private fun bindCycleOption(holder: VH, position: Int) {
        val item = items[position] as SettingItem.CycleOption
        holder.label.text = item.label
        holder.value.text = item.options.getOrNull(item.currentIndex) ?: ""
        // ViewHolder 会被 Action/Display 复用，这里必须恢复箭头可见与可聚焦
        holder.arrowLeft.visibility = View.VISIBLE
        holder.arrowRight.visibility = View.VISIBLE
        holder.itemView.isFocusable = true
        applyLockVisuals(holder, lockedPosition == position)

        holder.itemView.setOnClickListener { toggleLock(holder, position) }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    // 确认键：锁定/解锁切换；UP 也消费，避免再触发 click
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        toggleLock(holder, position)
                    }
                    true
                }
                KeyEvent.KEYCODE_BACK -> {
                    // 锁定时返回键解除锁定（并拦截，避免对话框直接关闭）
                    if (lockedPosition == position) {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            lockedPosition = -1
                            applyLockVisuals(holder, false)
                        }
                        true
                    } else {
                        false
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    // 锁定时左右键切换值；未锁定时不拦截，焦点正常移动
                    if (event.action == KeyEvent.ACTION_DOWN && lockedPosition == position) {
                        change(holder, position, if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                        true
                    } else {
                        false
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // 锁定时禁止移动焦点，需先解锁
                    lockedPosition == position
                }
                else -> false
            }
        }
    }

    private fun toggleLock(holder: VH, position: Int) {
        lockedPosition = if (lockedPosition == position) -1 else position
        applyLockVisuals(holder, lockedPosition == position)
    }

    /** 直接更新数据与视图，不经过 notify（按键回调里 notify 是崩溃根源） */
    private fun change(holder: VH, position: Int, direction: Int) {
        val current = items.getOrNull(position) as? SettingItem.CycleOption ?: return
        val newIndex = (current.currentIndex + direction).floorMod(current.options.size)
        items[position] = current.copy(currentIndex = newIndex)
        holder.value.text = current.options[newIndex]
        current.onChanged(newIndex)
    }

    /** 锁定态视觉反馈：背景变主题色、箭头变亮；文字保持白色 */
    private fun applyLockVisuals(holder: VH, locked: Boolean) {
        val arrowColor = if (locked) LOCKED_ARROW_COLOR else UNLOCKED_ARROW_COLOR
        holder.arrowLeft.setTextColor(arrowColor)
        holder.arrowRight.setTextColor(arrowColor)
        holder.value.setTextColor(VALUE_COLOR)
        holder.itemView.setBackgroundResource(
            if (locked) R.drawable.setting_locked_background
            else R.drawable.option_selector_background
        )
    }

    private fun bindAction(holder: VH, item: SettingItem.Action) {
        holder.label.text = item.label
        holder.value.text = item.value
        holder.arrowLeft.visibility = View.GONE
        holder.arrowRight.visibility = View.GONE
        holder.itemView.isFocusable = true
        // 复用的 holder 可能带着锁定态颜色，这里重置
        applyLockVisuals(holder, false)

        holder.itemView.setOnClickListener { item.onClick() }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                item.onClick()
                true
            } else {
                false
            }
        }
    }

    private fun bindDisplay(holder: VH, item: SettingItem.Display) {
        holder.label.text = item.label
        holder.value.text = item.value
        holder.arrowLeft.visibility = View.GONE
        holder.arrowRight.visibility = View.GONE
        // 只读项不参与焦点导航
        holder.itemView.isFocusable = false
        // 复用的 holder 可能带着锁定态颜色，这里重置
        applyLockVisuals(holder, false)
        holder.itemView.setOnClickListener(null)
        holder.itemView.setOnKeyListener(null)
    }

    private fun Int.floorMod(mod: Int): Int = ((this % mod) + mod) % mod

    companion object {
        private val UNLOCKED_ARROW_COLOR = 0x80FFFFFF.toInt()
        private val LOCKED_ARROW_COLOR = 0xFFFFFFFF.toInt()
        private val VALUE_COLOR = 0xFFFFFFFF.toInt()
    }
}
