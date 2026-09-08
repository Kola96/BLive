"""页面对象：主列表页 / 播放页。

导航策略：
- 面板/列表项优先用「文本点击」（u2 click = 坐标触摸，会触发 item 的 click 监听），
  比 D-pad 逐步移动稳定得多。
- 仅网格卡片进入直播间等少数场景用 D-pad。

设置面板（底部抽屉版）结构：
- 上排：分类 chip 行（settings_category_recycler，横向），chip = 名称 + 当前值
- 下排：选项 pill 行（settings_option_recycler，横向），跟随分类焦点联动刷新
"""
import time
import xml.etree.ElementTree as ET

from .device import Device, PACKAGE

GRID_ID = f"{PACKAGE}:id/main_grid"
CATEGORY_RV_ID = f"{PACKAGE}:id/settings_category_recycler"
OPTION_RV_ID = f"{PACKAGE}:id/settings_option_recycler"

# 播放设置面板全部分类（用于完整性断言）
SETTINGS_CATEGORIES = ["画质", "线路", "编码"]
DANMU_SETTINGS_CATEGORIES = ["弹幕", "速度", "不透明度", "大小", "显示区域"]


class MainPage:
    def __init__(self, dev: Device):
        self.dev = dev

    def wait_loaded(self, timeout: float = 20.0) -> int:
        """等待主网格出现且加载出卡片，返回卡片数。"""
        assert self.dev.d(resourceId=GRID_ID).wait(timeout=timeout), "主网格未出现"
        deadline = time.time() + timeout
        while time.time() < deadline:
            count = self.dev.child_count(GRID_ID)
            if count > 0:
                return count
            time.sleep(1)
        raise AssertionError(f"网格 {timeout}s 内未加载出卡片")

    def open_first_room(self):
        """tab 焦点右移进内容区，确认进入第一个直播间。"""
        self.dev.key("DPAD_RIGHT")
        time.sleep(2)  # 焦点编排是异步的
        self.dev.key("DPAD_CENTER")


class PlayPage:
    def __init__(self, dev: Device):
        self.dev = dev

    # ---------- 播放/弹幕状态 ----------

    def wait_playing(self, timeout: float = 15.0):
        assert self.dev.wait_log("PlayerManager", timeout), "播放器未启动"
        errors = self.dev.logcat("Source error")
        assert not errors, f"播放出错: {errors[-1]}"

    def wait_danmu_connected(self, timeout: float = 15.0):
        assert self.dev.wait_log("弹幕连接建立成功", timeout), "弹幕未连接"

    def danmu_disconnected(self) -> bool:
        return bool(self.dev.logcat("EOFException"))

    # ---------- 设置面板（底部抽屉） ----------

    def open_settings(self):
        """打开设置面板：发送 MENU 并验证"画质"出现，失败重试（按键可能被吞）。"""
        for _ in range(4):
            if self.dev.d(text="画质").wait(timeout=1.5):
                time.sleep(0.8)  # 等动画与焦点稳定
                return
            self.dev.key("MENU")
        raise AssertionError("设置面板打开失败")

    def close_settings(self):
        """关闭设置面板并验证。"""
        for _ in range(3):
            if not self.dev.d(text="画质").exists:
                return
            self.dev.key("BACK")
            time.sleep(0.8)
        self.dev.key("BACK")
        time.sleep(0.5)

    # ---------- 层级解析工具 ----------

    @staticmethod
    def _parse_bounds(bounds: str) -> tuple[int, int, int, int]:
        # "[left,top][right,bottom]"
        nums = bounds.replace("][", ",").strip("[]").split(",")
        return tuple(int(n) for n in nums)

    def _nodes_in_container(self, container_id: str, text: str):
        """查找位于指定容器（resource-id）内、文本匹配的节点 bounds 列表。"""
        root = ET.fromstring(self.dev.d.dump_hierarchy())
        parent_map = {c: p for p in root.iter() for c in p}
        result = []
        for node in root.iter("node"):
            if node.get("text") != text or not node.get("bounds"):
                continue
            current = parent_map.get(node)
            while current is not None:
                if current.get("resource-id") == container_id:
                    result.append(self._parse_bounds(node.get("bounds")))
                    break
                current = parent_map.get(current)
        return result

    def _click_bounds(self, bounds):
        left, top, right, bottom = bounds
        self.dev.d.click((left + right) // 2, (top + bottom) // 2)

    # ---------- 设置面板操作 ----------

    def category_value(self, name: str) -> str | None:
        """读取分类 chip 的当前值：如 编码 -> 'H.264 (AVC)'。"""
        root = ET.fromstring(self.dev.d.dump_hierarchy())
        parent_map = {c: p for p in root.iter() for c in p}
        for node in root.iter("node"):
            if node.get("text") != name:
                continue
            # 限定在分类行容器内，排除选项行里的重名文本
            current = parent_map.get(node)
            in_category_rv = False
            while current is not None:
                if current.get("resource-id") == CATEGORY_RV_ID:
                    in_category_rv = True
                    break
                current = parent_map.get(current)
            if not in_category_rv:
                continue
            # chip 容器内除名称外的文本即当前值
            chip = parent_map.get(node)
            if chip is None:
                continue
            texts = [c.get("text") for c in chip.iter("node") if c.get("text") and c.get("text") != name]
            return texts[0] if texts else None
        return None

    def _option_pill_bounds(self, option: str):
        """选项 pill 必须位于选项行容器内（与分类 chip 的值文本隔离）。"""
        nodes = self._nodes_in_container(OPTION_RV_ID, option)
        return nodes[0] if nodes else None

    def _scroll_option_into_view(self, option: str):
        """选项行是横向列表，超长时向右滚动查找。"""
        rv = self.dev.d(resourceId=OPTION_RV_ID)
        for _ in range(4):
            bounds = self._option_pill_bounds(option)
            if bounds is not None:
                return bounds
            if not rv.exists:
                return None
            rv.scroll.forward()
            time.sleep(0.8)
        return self._option_pill_bounds(option)

    # ---------- 设置面板操作（D-pad 导航，与真实用户一致） ----------
    # 注：横向 RecyclerView 的 accessibility scroll 对本面板无效（实测返回 False），
    # 因此分类/选项行导航一律用方向键，RV 获得焦点后会自动滚动。

    # 分类 chip 固定顺序
    CHIP_ORDER = ["画质", "线路", "编码", "弹幕", "速度", "不透明度", "大小", "显示区域"]

    def _focused_item_texts(self, container_id: str) -> list[str]:
        """容器内当前焦点 item 的全部文本（chip: [名称, 值]；pill: [✓?, 标签]）。"""
        root = ET.fromstring(self.dev.d.dump_hierarchy())
        parent_map = {c: p for p in root.iter() for c in p}
        for node in root.iter("node"):
            if node.get("focused") != "true":
                continue
            parent = parent_map.get(node)
            if parent is not None and parent.get("resource-id") == container_id:
                return [d.get("text") for d in node.iter("node") if d.get("text")]
        return []

    def _focused_chip_name(self) -> str | None:
        texts = self._focused_item_texts(CATEGORY_RV_ID)
        return texts[0] if texts else None

    def _focused_pill_label(self) -> str | None:
        texts = self._focused_item_texts(OPTION_RV_ID)
        return texts[-1] if texts else None

    def focus_chip(self, category: str, timeout: float = 10.0):
        """把焦点移动到目标分类 chip（进入面板行的方式：先点任一可见 chip）。"""
        deadline = time.time() + timeout
        while time.time() < deadline:
            focused = self._focused_chip_name()
            if focused == category:
                return
            if focused is None:
                # 焦点不在分类行：点击第一个可见 chip 进入
                entered = False
                for name in self.CHIP_ORDER:
                    nodes = self._nodes_in_container(CATEGORY_RV_ID, name)
                    if nodes:
                        self._click_bounds(nodes[0])
                        time.sleep(0.8)
                        entered = True
                        break
                if not entered:
                    raise AssertionError("分类行没有可见 chip（面板未打开？）")
                continue
            cur = self.CHIP_ORDER.index(focused)
            target = self.CHIP_ORDER.index(category)
            self.dev.key("DPAD_RIGHT" if target > cur else "DPAD_LEFT")
            time.sleep(0.6)
        raise AssertionError(f"无法聚焦分类: {category}")

    def chip_exists(self, category: str) -> bool:
        """分类 chip 是否存在（focus 导航验证，RV 会自动滚动显示）。"""
        try:
            self.focus_chip(category)
            return True
        except (AssertionError, ValueError):
            return False

    def _option_pills_in_order(self) -> list[str]:
        """选项行当前可见 pill 文本，按从左到右顺序。"""
        root = ET.fromstring(self.dev.d.dump_hierarchy())
        parent_map = {c: p for p in root.iter() for c in p}
        pills = []
        for node in root.iter("node"):
            t = node.get("text")
            if not t or not node.get("bounds") or t == "✓":
                continue
            current = parent_map.get(node)
            while current is not None:
                if current.get("resource-id") == OPTION_RV_ID:
                    pills.append((self._parse_bounds(node.get("bounds"))[0], t))
                    break
                current = parent_map.get(current)
        return [t for _, t in sorted(pills)]

    def select_category_option(self, category: str, option: str, timeout: float = 12.0):
        """聚焦分类 chip → 进入选项行 → 移动到目标 pill → 确认选择。

        注意：选项行端点继续按方向键焦点会逃逸回分类行（框架 focusSearch），
        因此移动方向由 pill 的左右顺序计算，不做盲目试探。
        """
        deadline = time.time() + timeout
        if self.category_value(category) == option:
            return

        self.focus_chip(category)

        # 布尔分类：chip 上按确认直接切换，不进入选项行
        if category == "弹幕":
            while self.category_value("弹幕") != option:
                assert time.time() < deadline, f"切换弹幕 -> {option} 失败"
                self.dev.key("DPAD_CENTER")
                time.sleep(1.0)
            return

        # 确认进入选项行（焦点落在当前选中 pill）
        self.dev.key("DPAD_CENTER")
        time.sleep(0.8)

        while self.category_value(category) != option:
            assert time.time() < deadline, f"选择 {category} -> {option} 失败"
            focused = self._focused_pill_label()
            if focused is None:
                # 焦点逃逸回分类行：重新聚焦分类再进入
                self.focus_chip(category)
                self.dev.key("DPAD_CENTER")
                time.sleep(0.8)
                continue
            if focused == option:
                self.dev.key("DPAD_CENTER")
                time.sleep(1.0)
                continue
            pills = self._option_pills_in_order()
            if option not in pills or focused not in pills:
                # 目标 pill 离屏或焦点读取异常：右移一位让列表滚动后重读
                self.dev.key("DPAD_RIGHT")
                time.sleep(0.6)
                continue
            direction = "DPAD_RIGHT" if pills.index(option) > pills.index(focused) else "DPAD_LEFT"
            self.dev.key(direction)
            time.sleep(0.6)

    def exit_to_home(self):
        """退出播放页回主列表：BACK 关面板 → BACK ×2 退出（3 秒窗口内）。"""
        self.dev.back_to_home_page()
