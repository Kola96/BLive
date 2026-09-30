# BLive 端到端测试（Python + uiautomator2）

对运行在 Android TV 模拟器/真机上的 App 做黑盒集成测试。

## 环境准备

```bash
# 1. 启动 Android TV 模拟器（或连接真机），确认在线
adb devices

# 2. 确认模拟器时钟与宿主机一致（时钟偏移会导致 CDN SSL 校验失败！）
adb shell date && date

# 3. 安装依赖（建议虚拟环境）
python3 -m venv .venv && source .venv/bin/activate
pip install -r tests/e2e/requirements.txt

# 4. 构建并安装被测应用
./gradlew installDebug
```

## 运行

```bash
pytest tests/e2e          # 全量
pytest tests/e2e -k hevc  # 单条
```

## 实现要点（踩坑记录）

- **按键注入必须走 `adb shell input keyevent`**：uiautomator2 的 `d.press()` 事件
  到不了本 App（注入通道不同），因此 `core/device.py` 里按键统一走 adb。
- **uiautomator2 只负责断言与等待**：`wait(timeout)`、UI 层级、焦点元素读取。
- **视频/弹幕无 UI 层级**（SurfaceView / 自定义 View），用 logcat 标签断言：
  `PlayerManager`（播放）、`DanmuTcpClient`（弹幕连接）。
- **修改设置的用例必须恢复现场**：偏好是持久化的，测试结束要改回原值。
- 设备时钟错误会直接导致播放失败，conftest 会先做时钟预检。
