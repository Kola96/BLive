package com.blive.tv.ui.settings.update

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blive.tv.data.update.UpdateChecker
import com.blive.tv.data.update.UpdateInfo
import com.blive.tv.data.update.UpdateRepository
import com.blive.tv.utils.ApkDownloader
import com.blive.tv.utils.ApkInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 更新检查 / 下载 / 安装 的状态机。
 *
 * 流程：
 *   Idle → Checking → UpdateAvailable / NoUpdate / CheckError
 *   UpdateAvailable → Downloading → Downloaded / DownloadError
 *   Downloaded → Installing → Idle
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    /** 检查更新的状态 */
    sealed class CheckState {
        object Idle : CheckState()
        object Checking : CheckState()
        /** 发现新版本 */
        data class UpdateAvailable(val info: UpdateInfo) : CheckState()
        /** 已是最新 */
        object NoUpdate : CheckState()
        /** 检查失败 */
        data class CheckError(val message: String) : CheckState()
    }

    /** 下载的状态 */
    sealed class DownloadState {
        object Idle : DownloadState()
        /** 下载中 */
        data class Downloading(
            val info: UpdateInfo,
            val downloadedBytes: Long,
            val totalBytes: Long
        ) : DownloadState()
        /** 下载完成 */
        data class Downloaded(val info: UpdateInfo, val file: File) : DownloadState()
        /** 下载失败 */
        data class DownloadError(val info: UpdateInfo, val message: String) : DownloadState()
        /** 已取消 */
        object Cancelled : DownloadState()
    }

    /** UI 一次性事件 */
    sealed class UpdateEvent {
        /** 跳系统"允许安装未知来源"设置页 */
        object RequestInstallPermission : UpdateEvent()
        data class Toast(val message: String) : UpdateEvent()
    }

    private val _checkState = MutableStateFlow<CheckState>(CheckState.Idle)
    val checkState: StateFlow<CheckState> = _checkState.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _events = MutableSharedFlow<UpdateEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UpdateEvent> = _events.asSharedFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    /** 用户当前选择的更新源 */
    fun getSourceMode(): UpdateRepository.SourceMode =
        UpdateChecker.getSourceMode(getApplication())

    fun setSourceMode(mode: UpdateRepository.SourceMode) {
        UpdateChecker.setSourceMode(getApplication(), mode)
    }

    /**
     * 手动检查更新（设置页"检查更新"按钮触发）。
     * 不区分频率限制，直接请求。
     */
    fun manualCheck() {
        startCheck(isAuto = false)
    }

    /**
     * 自动检查（App 启动时调用）。
     * 远端版本需满足"未忽略且 24h 内未提示过"才会进入 UpdateAvailable；
     * 否则静默回到 Idle。
     */
    fun autoCheck() {
        startCheck(isAuto = true)
    }

    private fun startCheck(isAuto: Boolean) {
        if (_checkState.value == CheckState.Checking) {
            Log.d(TAG, "startCheck($isAuto) skipped: already checking")
            return
        }
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            Log.d(TAG, "startCheck(isAuto=$isAuto) start, sourceMode=${getSourceMode()}")
            _checkState.value = CheckState.Checking
            try {
                val info = UpdateRepository.fetchLatest(getSourceMode())
                Log.d(TAG, "fetch ok: remote=${info.versionName}, local=${com.blive.tv.BuildConfig.VERSION_NAME}")
                if (!UpdateChecker.isNewer(info)) {
                    Log.d(TAG, "no update available")
                    _checkState.value = CheckState.NoUpdate
                    return@launch
                }
                if (isAuto && !UpdateChecker.shouldAutoPrompt(getApplication(), info)) {
                    Log.d(TAG, "skip auto prompt (ignored or already prompted recently)")
                    _checkState.value = CheckState.Idle
                    return@launch
                }
                // 标记已提示，避免 24h 内重复弹
                if (isAuto) {
                    UpdateChecker.markPrompted(getApplication(), info)
                }
                Log.d(TAG, "UpdateAvailable → show dialog")
                _checkState.value = CheckState.UpdateAvailable(info)
            } catch (e: Exception) {
                Log.w(TAG, "check failed", e)
                _checkState.value = CheckState.CheckError(e.message ?: "未知错误")
            }
        }
    }

    companion object {
        private const val TAG = "UpdateViewModel"
    }

    /** 用户在更新弹窗中选择"立即更新" → 开始下载 */
    fun startDownload(info: UpdateInfo) {
        if (_downloadState.value is DownloadState.Downloading) return

        // 已下载过该版本且完整 → 直接走安装
        val cached = ApkDownloader.getCachedFile(getApplication(), info.versionName, info.apkSize)
        if (cached != null) {
            _downloadState.value = DownloadState.Downloaded(info, cached)
            viewModelScope.launch { installApk(info, cached) }
            return
        }

        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            _downloadState.value = DownloadState.Downloading(info, 0L, info.apkSize ?: -1L)
            val result = ApkDownloader.download(
                context = getApplication(),
                url = info.apkUrl,
                versionName = info.versionName,
                expectedSize = info.apkSize,
                listener = ApkDownloader.ProgressListener { downloaded, total ->
                    _downloadState.value = DownloadState.Downloading(info, downloaded, total)
                }
            )
            when (result) {
                is ApkDownloader.DownloadResult.Success -> {
                    _downloadState.value = DownloadState.Downloaded(info, result.file)
                    installApk(info, result.file)
                }
                is ApkDownloader.DownloadResult.Failure -> {
                    _downloadState.value = DownloadState.DownloadError(
                        info,
                        result.error.message ?: "下载失败"
                    )
                }
                ApkDownloader.DownloadResult.Cancelled -> {
                    _downloadState.value = DownloadState.Cancelled
                }
            }
        }
    }

    /** 取消下载 */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _downloadState.value = DownloadState.Cancelled
    }

    /** 用户在弹窗中选择"忽略此版本" */
    fun ignoreThisVersion(versionName: String) {
        UpdateChecker.ignoreVersion(getApplication(), versionName)
        dismissDialog()
    }

    /** 关闭更新弹窗，回到 Idle */
    fun dismissDialog() {
        _checkState.value = CheckState.Idle
        _downloadState.value = DownloadState.Idle
    }

    /** 重置错误状态，便于用户重试 */
    fun resetError() {
        if (_checkState.value is CheckState.CheckError) {
            _checkState.value = CheckState.Idle
        }
        if (_downloadState.value is DownloadState.DownloadError ||
            _downloadState.value is DownloadState.Cancelled
        ) {
            _downloadState.value = DownloadState.Idle
        }
    }

    /** 重新安装已下载的 APK（用户之前关掉了系统安装界面） */
    fun installDownloadedApk() {
        val state = _downloadState.value
        if (state is DownloadState.Downloaded) {
            viewModelScope.launch { installApk(state.info, state.file) }
        }
    }

    private suspend fun installApk(info: UpdateInfo, file: File) {
        val context = getApplication<Application>()
        if (!ApkInstaller.canInstallPackages(context)) {
            _events.emit(UpdateEvent.Toast("请允许 BLive 安装未知来源应用"))
            _events.emit(UpdateEvent.RequestInstallPermission)
            return
        }
        // install 需要在主线程调用 startActivity
        val launched = withContext(Dispatchers.Main) {
            ApkInstaller.install(context, file)
        }
        if (!launched) {
            _events.emit(UpdateEvent.Toast("无法启动系统安装器"))
        }
    }

    override fun onCleared() {
        super.onCleared()
        checkJob?.cancel()
        downloadJob?.cancel()
    }
}
