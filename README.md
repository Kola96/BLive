# BLive - Android TV Bilibili Live Client

BLive 是一个专为 Android TV 设计的哔哩哔哩（Bilibili）直播客户端，采用 Kotlin 编写。它旨在为电视用户提供流畅、纯净的大屏直播观看体验，支持遥控器操作、弹幕显示以及画质调节等功能。

*AI真是太好用了，你们知道吗？*

## ✨ 主要功能

*   **大屏直播观看**：适配 Android TV，支持高清直播流播放。
*   **实时弹幕**：内置弹幕引擎，支持实时显示直播间弹幕。
*   **扫码登录**：支持 Bilibili 手机端扫码登录，同步用户信息。
*   **遥控器适配**：全功能 D-pad 方向键支持，操作顺滑。
*   **个性化设置**：
    *   **画质切换**：支持多种清晰度选择。
    *   **弹幕设置**：支持调节弹幕大小、不透明度、速度以及开关。

*Tips：目前仅支持观看关注列表中的直播间*

## 🛠 技术栈

本项目基于现代 Android 开发技术栈构建：

*   **语言**：[Kotlin](https://kotlinlang.org/)
*   **UI 框架**：Android View System (XML) + [Android Leanback Library](https://developer.android.com/jetpack/androidx/releases/leanback) (TV UI 适配)
*   **网络请求**：[Retrofit](https://square.github.io/retrofit/) + [OkHttp](https://square.github.io/okhttp/)
*   **视频播放**：[ExoPlayer](https://github.com/google/ExoPlayer)
*   **图片加载**：[Glide](https://github.com/bumptech/glide)
*   **二维码**：[ZXing](https://github.com/zxing/zxing)
*   **弹幕通信**：自定义 TCP/WebSocket 客户端

## 🚀 快速开始

### 环境要求

*   Android Studio Iguana 或更高版本
*   JDK 17
*   Android SDK API 34 (Min SDK 21)

### 构建步骤

1.  **克隆仓库**
    ```bash
    git clone https://github.com/your-username/BLive.git
    cd BLive
    ```

2.  **打开项目**
    启动 Android Studio，选择 `Open` 并指向 `BLive` 目录。

3.  **同步依赖**
    等待 Gradle Sync 完成，下载所需依赖库。

4.  **运行项目**
    *   连接 Android TV 设备或启动 Android TV 模拟器。
    *   点击 Android Studio 顶部的 `Run` 按钮 (绿色播放图标)。

### 安装说明

如果您想直接安装 APK：

1.  在项目根目录下执行：
    ```bash
    ./gradlew assembleDebug
    ```
2.  生成的 APK 文件位于 `app/build_new/outputs/apk/debug/app-debug.apk`。
3.  通过 ADB 安装到电视：
    ```bash
    adb connect <电视IP地址>
    adb install -r app/build_new/outputs/apk/debug/app-debug.apk
    ```

## 🎮 操作指南

*   **下方向键/菜单键 (播放页)**：呼出设置菜单。

## ⚠️ 免责声明

1.  本项目仅供个人学习、研究和交流使用，请于下载后 24 小时内删除。
2.  本项目完全免费，严禁用于任何商业用途或非法盈利。
3.  本项目所使用的 API 接口均来源于 Bilibili 官方，其知识产权归 Bilibili 所有。本项目不保证 API 的稳定性、安全性及可用性。
4.  使用本项目所产生的任何后果由使用者自行承担，开发者不承担任何法律责任。
5.  如果本项目侵犯了您的权益，请联系开发者删除。

## 📄 许可证

[MIT License](LICENSE)
