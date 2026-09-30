package com.blive.tv.utils

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * APK 下载器。
 *
 * - 流式写入，避免 OOM
 * - 通过回调上报进度（已下载字节 / 总字节）
 * - 已存在完整文件时跳过下载
 * - 取消：协程取消即中断写入并删除半成品文件
 */
object ApkDownloader {

    private const val TAG = "ApkDownloader"

    sealed class DownloadResult {
        data class Success(val file: File) : DownloadResult()
        data class Failure(val error: Throwable) : DownloadResult()
        object Cancelled : DownloadResult()
    }

    /** 进度回调：downloadedBytes / totalBytes（totalBytes 可能为 -1 表示未知） */
    fun interface ProgressListener {
        fun onProgress(downloadedBytes: Long, totalBytes: Long)
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** 生成 APK 的本地目标文件 */
    fun getTargetFile(context: Context, versionName: String): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        return File(dir, "BLive-update-v$versionName.apk")
    }

    /**
     * 如果本地已存在该版本且大小匹配，则直接返回该文件；否则返回 null。
     */
    fun getCachedFile(context: Context, versionName: String, expectedSize: Long?): File? {
        val file = getTargetFile(context, versionName)
        if (!file.exists()) return null
        if (expectedSize != null && expectedSize > 0 && file.length() != expectedSize) {
            // 大小不匹配，删了重新下
            file.delete()
            return null
        }
        // 大小未知但文件存在，先用着
        return file
    }

    suspend fun download(
        context: Context,
        url: String,
        versionName: String,
        expectedSize: Long?,
        listener: ProgressListener?
    ): DownloadResult = withContext(Dispatchers.IO) {
        val target = getTargetFile(context, versionName)
        // 用临时文件，下完再改名，避免半成品被误认为完整文件
        val tmpFile = File(target.parentFile, target.name + ".download")
        if (tmpFile.exists()) tmpFile.delete()

        Log.d(TAG, "download start: url=$url target=${target.absolutePath} expectedSize=$expectedSize")

        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                Log.d(TAG, "response code=${response.code} contentLength=${response.body?.contentLength()}")
                if (!response.isSuccessful) {
                    return@withContext DownloadResult.Failure(
                        Exception("HTTP ${response.code}: ${response.message}")
                    )
                }
                val body = response.body
                    ?: return@withContext DownloadResult.Failure(Exception("响应体为空"))
                val totalBytes = body.contentLength().takeIf { it > 0 } ?: (expectedSize ?: -1L)

                body.byteStream().use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastLoggedPercent = -1
                        while (true) {
                            if (!isActive) {
                                Log.d(TAG, "download cancelled at $downloaded bytes")
                                tmpFile.delete()
                                return@withContext DownloadResult.Cancelled
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            listener?.onProgress(downloaded, totalBytes)
                            if (totalBytes > 0) {
                                val percent = (downloaded * 100 / totalBytes).toInt()
                                if (percent != lastLoggedPercent && percent % 10 == 0) {
                                    lastLoggedPercent = percent
                                    Log.d(TAG, "progress: $percent% ($downloaded/$totalBytes)")
                                }
                            }
                        }
                        output.flush()
                    }
                }

                // 校验大小
                if (totalBytes > 0 && tmpFile.length() != totalBytes) {
                    Log.w(TAG, "size mismatch: expected=$totalBytes actual=${tmpFile.length()}")
                    tmpFile.delete()
                    return@withContext DownloadResult.Failure(
                        Exception("文件大小不一致：期望 $totalBytes 字节，实际 ${tmpFile.length()} 字节")
                    )
                }

                if (target.exists()) target.delete()
                if (!tmpFile.renameTo(target)) {
                    tmpFile.delete()
                    return@withContext DownloadResult.Failure(Exception("无法移动临时文件"))
                }
                Log.d(TAG, "download success: ${target.absolutePath} (${target.length()} bytes)")
                DownloadResult.Success(target)
            }
        } catch (e: Exception) {
            Log.w(TAG, "download failed", e)
            tmpFile.delete()
            if (!isActive) DownloadResult.Cancelled else DownloadResult.Failure(e)
        }
    }
}
