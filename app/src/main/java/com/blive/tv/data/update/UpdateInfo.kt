package com.blive.tv.data.update

/**
 * 统一的更新信息，屏蔽 GitHub / Gitee API 的差异。
 */
data class UpdateInfo(
    /** 版本号，例如 "1.0.2"（已去掉 tag 前缀 v） */
    val versionName: String,
    /** 更新日志（Markdown 原文） */
    val changelog: String,
    /** APK 直链下载地址 */
    val apkUrl: String,
    /** APK 文件大小（字节），用于下载进度显示；未知则为 null */
    val apkSize: Long?,
    /** 发布时间（ISO 8601 字符串，原样保留） */
    val publishedAt: String,
    /** 该信息来自哪个更新源 */
    val source: UpdateSource
)
