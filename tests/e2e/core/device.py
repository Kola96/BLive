"""设备底层封装：adb 按键/日志/截图 + uiautomator2 断言。

注意：按键必须走 `adb shell input keyevent`。
uiautomator2 的 d.press() 注入的 DPAD 事件到不了本 App（实测验证），
u2 只用于 UI 层级断言与显式等待。
"""
import os
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET

import uiautomator2 as u2

PACKAGE = "com.blive.tv"
MAIN_ACTIVITY = f"{PACKAGE}/.MainActivity"


def _resolve_adb() -> str:
    """优先从项目根的 local.properties 解析 sdk.dir（向上逐级查找）。"""
    directory = os.path.dirname(os.path.abspath(__file__))
    for _ in range(5):
        local_props = os.path.join(directory, "local.properties")
        if os.path.exists(local_props):
            with open(local_props) as f:
                for line in f:
                    if line.startswith("sdk.dir="):
                        adb = os.path.join(line.split("=", 1)[1].strip(), "platform-tools", "adb")
                        if os.path.exists(adb):
                            return adb
        directory = os.path.dirname(directory)
    return os.environ.get("ADB", "adb")


class Device:
    def __init__(self):
        self.adb = _resolve_adb()
        self.d = u2.connect()

    # ---------- adb 基础操作 ----------

    def shell(self, *args: str) -> str:
        return subprocess.run(
            [self.adb, "shell", *args], capture_output=True, text=True
        ).stdout

    def key(self, code: str):
        """发送遥控器按键，如 DPAD_DOWN / DPAD_CENTER / MENU / BACK。"""
        self.shell("input", "keyevent", f"KEYCODE_{code}")

    def device_time(self) -> str:
        return self.shell("date").strip()

    def screenshot(self) -> str:
        path = os.path.join(tempfile.gettempdir(), f"blive_e2e_{int(time.time())}.png")
        with open(path, "wb") as f:
            subprocess.run([self.adb, "exec-out", "screencap", "-p"], stdout=f)
        return path

    # ---------- 日志断言 ----------

    def clear_logcat(self):
        subprocess.run([self.adb, "logcat", "-c"], capture_output=True)

    def logcat(self, pattern: str = "") -> list[str]:
        out = subprocess.run(
            [self.adb, "logcat", "-d"], capture_output=True, text=True
        ).stdout
        return [l for l in out.splitlines() if pattern in l]

    def wait_log(self, pattern: str, timeout: float = 10.0) -> bool:
        """轮询等待日志中出现指定内容（网络类等待用这个，别盲 sleep）。"""
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.logcat(pattern):
                return True
            time.sleep(0.5)
        return False

    # ---------- App 控制 ----------

    def launch_app(self, force: bool = True):
        if force:
            self.shell("am", "force-stop", PACKAGE)
        self.shell("am", "start", "-n", MAIN_ACTIVITY)

    def back_to_home_page(self, timeout: float = 5.0):
        """从任意页面退回主列表（播放页最多按 3 次 BACK：收分类/关面板/退出）。"""
        for _ in range(3):
            if self.d(resourceId=f"{PACKAGE}:id/main_grid").exists:
                return
            self.key("BACK")
            time.sleep(1)
        self.d(resourceId=f"{PACKAGE}:id/main_grid").wait(timeout=timeout)

    # ---------- UI 层级断言 ----------

    def focused_text(self) -> str | None:
        """当前焦点元素的文本（TV 焦点状态断言）。"""
        root = ET.fromstring(self.d.dump_hierarchy())
        for node in root.iter("node"):
            if node.get("focused") == "true":
                return node.get("text")
        return None

    def child_count(self, resource_id: str) -> int:
        root = ET.fromstring(self.d.dump_hierarchy())
        for node in root.iter("node"):
            if node.get("resource-id") == resource_id:
                return len(list(node))
        return 0
