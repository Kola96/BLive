# BLive 开发文档

本文档面向需要从源码构建、调试与打包 BLive 的开发者。

## 环境要求

- Android Studio Iguana 或更高版本
- JDK 17
- Android SDK API 34（Min SDK 21）

## 构建与运行（Debug）

1. 克隆仓库

```bash
git clone https://github.com/your-username/BLive.git
cd BLive
```

2. 打开项目  
启动 Android Studio，选择 `Open` 并指向 `BLive` 目录。

3. 同步依赖  
等待 Gradle Sync 完成。

4. 运行项目  
- 连接 Android TV 设备或启动 Android TV 模拟器。
- 点击 Android Studio 顶部 `Run` 按钮。  
或在项目根目录执行：

```bash
./gradlew assembleDebug
```

## 签名配置（Release，可选）

项目默认使用 Debug 签名。若需要构建正式签名 Release 包，请配置 `local.properties`：

1. 生成您自己的 Keystore 文件（`.jks`）。
2. 在项目根目录创建或编辑 `local.properties`。
3. 添加以下配置：

```properties
storeFile=/path/to/your/keystore.jks
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
```

注意事项：
- `local.properties` 含敏感信息，不要提交到版本控制系统。
- `.jks` 文件不要提交到版本控制系统。

4. 生成 Release 包：

```bash
./gradlew assembleRelease
```

## 安装到电视（ADB）

如果要安装本地构建的 APK：

1. 构建 Debug 包：

```bash
./gradlew assembleDebug
```

2. 产物目录：
- Debug：`app/build/outputs/apk/debug/`
- Release：`app/build/outputs/apk/release/`

3. 安装到电视：

```bash
adb connect <电视IP地址>
adb install -r app/build/outputs/apk/debug/<debug-apk文件名>.apk
```
