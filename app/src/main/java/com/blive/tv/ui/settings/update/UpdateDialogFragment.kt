package com.blive.tv.ui.settings.update

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.blive.tv.R
import com.blive.tv.data.update.UpdateInfo
import com.blive.tv.utils.MarkdownRenderer
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 更新对话框：
 * - 显示新版本信息 + Markdown 渲染的更新日志
 * - 日志区可聚焦，DPAD_UP/DOWN 滚动；右上角显示滚动位置指示器
 * - 下载中：显示进度条 + 取消按钮
 * - 下载失败：显示错误 + 重试
 * - 下载完成：自动触发系统安装界面
 */
class UpdateDialogFragment : DialogFragment() {

    private val viewModel: UpdateViewModel by activityViewModels()

    private lateinit var tvTitle: TextView
    private lateinit var tvMeta: TextView
    private lateinit var tvChangelog: TextView
    private lateinit var tvScrollIndicator: TextView
    private lateinit var changelogContainer: FrameLayout
    private lateinit var scrollChangelog: ScrollView
    private lateinit var containerProgress: LinearLayout
    private lateinit var progressDownload: ProgressBar
    private lateinit var tvProgressText: TextView
    private lateinit var tvError: TextView
    private lateinit var btnPositive: Button
    private lateinit var btnNeutral: Button
    private lateinit var btnNegative: Button

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setLayout(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_update, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvTitle = view.findViewById(R.id.tv_update_title)
        tvMeta = view.findViewById(R.id.tv_update_meta)
        tvChangelog = view.findViewById(R.id.tv_changelog)
        tvScrollIndicator = view.findViewById(R.id.tv_scroll_indicator)
        changelogContainer = view.findViewById(R.id.changelog_container)
        scrollChangelog = view.findViewById(R.id.scroll_changelog)
        containerProgress = view.findViewById(R.id.container_progress)
        progressDownload = view.findViewById(R.id.progress_download)
        tvProgressText = view.findViewById(R.id.tv_progress_text)
        tvError = view.findViewById(R.id.tv_error)
        btnPositive = view.findViewById(R.id.btn_positive)
        btnNeutral = view.findViewById(R.id.btn_neutral)
        btnNegative = view.findViewById(R.id.btn_negative)

        setupChangelogScroll()

        observeStates()
    }

    /** 日志区聚焦时 DPAD_UP/DOWN 滚动，DPAD_DOWN 到底时移到"立即更新"按钮 */
    private fun setupChangelogScroll() {
        // ScrollView 不可聚焦，DPAD 事件全部由外层 container 消费
        scrollChangelog.isFocusable = false
        scrollChangelog.isFocusableInTouchMode = false

        changelogContainer.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val scrollRange = scrollChangelog.getChildAt(0)?.height?.minus(scrollChangelog.height) ?: 0
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (scrollChangelog.scrollY > 0) {
                        scrollChangelog.smoothScrollBy(0, -SCROLL_STEP)
                        scrollChangelog.postDelayed({ updateScrollIndicator() }, SCROLL_ANIMATION_MS)
                        true
                    } else false
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (scrollChangelog.scrollY < scrollRange) {
                        scrollChangelog.smoothScrollBy(0, SCROLL_STEP)
                        scrollChangelog.postDelayed({ updateScrollIndicator() }, SCROLL_ANIMATION_MS)
                        true
                    } else {
                        // 已经到底，把焦点移到"立即更新"
                        btnPositive.requestFocus()
                        true
                    }
                }
                else -> false
            }
        }
        // 聚焦时高亮
        changelogContainer.setOnFocusChangeListener { _, hasFocus ->
            changelogContainer.isActivated = hasFocus
        }
    }

    /** 更新右上角滚动指示器 */
    private fun updateScrollIndicator() {
        scrollChangelog.post {
            val contentHeight = scrollChangelog.getChildAt(0)?.height ?: 0
            val viewHeight = scrollChangelog.height
            if (contentHeight <= viewHeight || viewHeight <= 0) {
                tvScrollIndicator.visibility = View.GONE
                return@post
            }
            val totalScrollable = contentHeight - viewHeight
            val currentScroll = scrollChangelog.scrollY
            // 用"页数"表示更直观
            val totalPages = (totalScrollable / viewHeight) + 1
            val currentPage = (currentScroll / viewHeight) + 1
            tvScrollIndicator.text = "$currentPage / $totalPages"
            tvScrollIndicator.visibility = View.VISIBLE
        }
    }

    private fun observeStates() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { observeCheckState() }
                launch { observeDownloadState() }
            }
        }
    }

    private suspend fun observeCheckState() {
        viewModel.checkState.collect { state ->
            when (state) {
                is UpdateViewModel.CheckState.UpdateAvailable -> renderAvailable(state.info)
                else -> Unit
            }
        }
    }

    private suspend fun observeDownloadState() {
        viewModel.downloadState.collect { state ->
            when (state) {
                is UpdateViewModel.DownloadState.Downloading -> renderDownloading(state)
                is UpdateViewModel.DownloadState.Downloaded -> renderDownloaded(state)
                is UpdateViewModel.DownloadState.DownloadError -> renderDownloadError(state)
                UpdateViewModel.DownloadState.Cancelled -> dismissAllowingStateLoss()
                else -> Unit
            }
        }
    }

    /** 发现新版本：显示信息 + Markdown 渲染的日志 + 三个按钮 */
    private fun renderAvailable(info: UpdateInfo) {
        tvTitle.text = getString(R.string.update_available_title) + "  v" + info.versionName
        tvMeta.text = buildString {
            append(info.source.displayName)
            val formattedTime = formatPublishTime(info.publishedAt)
            if (formattedTime.isNotEmpty()) {
                append("  ·  ").append(formattedTime)
            }
            if (info.apkSize != null && info.apkSize > 0) {
                append("  ·  ").append(formatBytes(info.apkSize))
            } else {
                append("  ·  ").append(getString(R.string.update_unknown_size))
            }
        }

        // 用 Markdown 渲染日志
        tvChangelog.text = if (info.changelog.isBlank()) {
            "（无更新日志）"
        } else {
            MarkdownRenderer.render(info.changelog)
        }

        // 等 TextView 布局完成后初始化滚动指示器
        scrollChangelog.post { updateScrollIndicator() }

        tvError.visibility = View.GONE
        containerProgress.visibility = View.GONE

        btnPositive.text = getString(R.string.update_now)
        btnPositive.visibility = View.VISIBLE
        btnNeutral.text = getString(R.string.update_later)
        btnNeutral.visibility = View.VISIBLE
        btnNegative.text = getString(R.string.update_ignore)
        btnNegative.visibility = View.VISIBLE

        btnPositive.setOnClickListener { viewModel.startDownload(info) }
        btnNeutral.setOnClickListener {
            viewModel.dismissDialog()
            dismissAllowingStateLoss()
        }
        btnNegative.setOnClickListener {
            viewModel.ignoreThisVersion(info.versionName)
            dismissAllowingStateLoss()
        }

        // 默认焦点在"立即更新"
        btnPositive.requestFocus()
    }

    /** 下载中：只显示进度 + 取消按钮 */
    private fun renderDownloading(state: UpdateViewModel.DownloadState.Downloading) {
        tvError.visibility = View.GONE
        containerProgress.visibility = View.VISIBLE

        if (state.totalBytes > 0) {
            val percent = (state.downloadedBytes * 100 / state.totalBytes).toInt().coerceIn(0, 100)
            progressDownload.isIndeterminate = false
            progressDownload.progress = percent
            tvProgressText.text = getString(R.string.update_downloading) +
                "  ${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)}  ($percent%)"
        } else {
            progressDownload.isIndeterminate = true
            tvProgressText.text = getString(R.string.update_downloading) +
                "  ${formatBytes(state.downloadedBytes)}"
        }

        btnPositive.visibility = View.GONE
        btnNeutral.text = getString(R.string.update_download_cancel)
        btnNeutral.visibility = View.VISIBLE
        btnNegative.visibility = View.GONE
        btnNeutral.setOnClickListener {
            viewModel.cancelDownload()
            dismissAllowingStateLoss()
        }
        btnNeutral.requestFocus()
    }

    /** 下载完成：显示"安装"按钮（用户可能关掉了系统安装器） */
    private fun renderDownloaded(state: UpdateViewModel.DownloadState.Downloaded) {
        containerProgress.visibility = View.VISIBLE
        progressDownload.isIndeterminate = false
        progressDownload.progress = 100
        tvProgressText.text = "已下载到本地：" + state.file.name

        tvError.visibility = View.GONE

        btnPositive.text = getString(R.string.update_install)
        btnPositive.visibility = View.VISIBLE
        btnPositive.setOnClickListener { viewModel.installDownloadedApk() }

        btnNeutral.text = getString(R.string.close)
        btnNeutral.visibility = View.VISIBLE
        btnNeutral.setOnClickListener {
            viewModel.dismissDialog()
            dismissAllowingStateLoss()
        }

        btnNegative.visibility = View.GONE
        btnPositive.requestFocus()
    }

    /** 下载失败：显示错误 + 重试/取消 */
    private fun renderDownloadError(state: UpdateViewModel.DownloadState.DownloadError) {
        containerProgress.visibility = View.GONE
        tvError.visibility = View.VISIBLE
        tvError.text = getString(R.string.update_download_failed) + "：" + state.message

        btnPositive.text = getString(R.string.update_retry)
        btnPositive.visibility = View.VISIBLE
        btnPositive.setOnClickListener { viewModel.startDownload(state.info) }

        btnNeutral.text = getString(R.string.close)
        btnNeutral.visibility = View.VISIBLE
        btnNeutral.setOnClickListener {
            viewModel.dismissDialog()
            dismissAllowingStateLoss()
        }

        btnNegative.visibility = View.GONE
        btnPositive.requestFocus()
    }

    private fun formatPublishTime(iso: String): String {
        if (iso.isBlank()) return ""
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val date = parser.parse(iso) ?: return iso.take(10)
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date)
        } catch (e: Exception) {
            iso.take(10)
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 0) return getString(R.string.update_unknown_size)
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0)
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        return String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    }

    companion object {
        const val TAG = "update_dialog"
        private const val SCROLL_STEP = 80 // px，每次 DPAD 滚动的距离
        private const val SCROLL_ANIMATION_MS = 250L // smoothScrollBy 动画时长，用于延迟更新指示器
    }
}
