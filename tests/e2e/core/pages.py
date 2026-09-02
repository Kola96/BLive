"""页面对象：主列表页 / 播放页。

导航策略：
- 面板/列表项优先用「文本点击」（u2 click = 坐标触摸，会触发 item 的 click 监听），
  比 D-pad 逐步移动稳定得多。
- 仅网格卡片进入直播间等少数场景用 D-pad。
"""
import time

from .device import Device, PACKAGE

GRID_ID = f"{PACKAGE}:id/main_grid"

# 播放设置面板全部设置项（用于完整性断言）
SETTINGS_CATEGORIES = ["画质", "线路", "编码"]
DANMU_SETTINGS_CATEGORIES = ["开关", "速度", "不透明度", "大小", "显示区域"]


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

    # ---------- 设置面板 ----------

    def open_settings(self):
        self.dev.key("MENU")
        time.sleep(1.5)  # 面板打开动画 + 焦点恢复

    def close_settings(self):
        self.dev.key("BACK")
        time.sleep(1)

    def category_value(self, name: str) -> str | None:
        """读取设置分类当前值：如 编码 -> 'H.264 (AVC)'。"""
        import xml.etree.ElementTree as ET
        root = ET.fromstring(self.dev.d.dump_hierarchy())
        for parent in root.iter("node"):
            texts = [c.get("text") for c in parent if c.get("text")]
            if name in texts:
                # 过滤分类名与左右箭头，剩下的就是当前值
                values = [t for t in texts if t != name and t not in ("<", ">")]
                return values[0] if values else None
        return None

    # ---------- 文本点击的可靠性处理 ----------
    # TV 焦点模型下首击=聚焦、二击=激活，且展开/收起是 toggle；
    # 因此不假设点击次数，用"点击-校验-重试"自闭环。
    # 另注意重名文本（如两个分类的值都可能是 "100%"），
    # 选项必须限定在目标分类行的下方区域。

    @staticmethod
    def _parse_bounds(bounds: str) -> tuple[int, int, int, int]:
        # "[left,top][right,bottom]"
        nums = bounds.replace("][", ",").strip("[]").split(",")
        return tuple(int(n) for n in nums)

    def _text_nodes(self, text: str):
        import xml.etree.ElementTree as ET
        root = ET.fromstring(self.dev.d.dump_hierarchy())
        return [
            self._parse_bounds(n.get("bounds"))
            for n in root.iter("node")
            if n.get("text") == text and n.get("bounds")
        ]

    def _click_bounds(self, bounds):
        left, top, right, bottom = bounds
        self.dev.d.click((left + right) // 2, (top + bottom) // 2)

    def _marker_nodes(self):
        """分类行的展开/收起标记：▼=展开，▶=收起（仅分类行有此文本节点）。"""
        return self._text_nodes("▼"), self._text_nodes("▶")

    def _category_state(self, category: str):
        """返回 (分类行 bounds, 是否已展开)。"""
        cat_nodes = self._text_nodes(category)
        if not cat_nodes:
            return None, False
        cat = cat_nodes[0]
        expanded_markers, _ = self._marker_nodes()
        # ▼ 标记与分类名在同一行（y 区间重叠）
        expanded = any(m[1] < cat[3] and m[3] > cat[1] for m in expanded_markers)
        return cat, expanded

    def _option_bounds_under(self, category: str, option: str) -> tuple | None:
        """展开的分类下方、下一个分类行（带 ▶/▼ 标记）之前的选项区域。"""
        cat, expanded = self._category_state(category)
        if cat is None or not expanded:
            return None
        expanded_markers, collapsed_markers = self._marker_nodes()
        next_rows = [m for m in expanded_markers + collapsed_markers if m[1] >= cat[3]]
        upper = min((m[1] for m in next_rows), default=1 << 30)
        candidates = [b for b in self._text_nodes(option) if cat[3] <= b[1] < upper]
        return candidates[0] if candidates else None

    def ensure_expanded(self, category: str, timeout: float = 8.0):
        """确保分类处于展开状态（点击-校验-重试，自闭环处理"首击仅聚焦"与 toggle）。"""
        deadline = time.time() + timeout
        while True:
            cat, expanded = self._category_state(category)
            assert cat is not None, f"找不到设置分类: {category}"
            if expanded:
                return
            assert time.time() < deadline, f"展开分类失败: {category}"
            self._click_bounds(cat)
            time.sleep(1.2)

    def _scrollable_ancestor_id(self, text: str) -> str | None:
        """找到文本节点所在的可滚动容器（RecyclerView）resource-id。"""
        import xml.etree.ElementTree as ET
        root = ET.fromstring(self.dev.d.dump_hierarchy())
        parent_map = {c: p for p in root.iter() for c in p}
        for node in root.iter("node"):
            if node.get("text") == text:
                current = parent_map.get(node)
                while current is not None:
                    if current.get("scrollable") == "true":
                        return current.get("resource-id")
                    current = parent_map.get(current)
                return None
        return None

    def _scroll_until_option(self, category: str, option: str) -> tuple | None:
        """选项可能因列表过长被滚出屏幕（离屏项不在层级中），
        在分类所在的可滚动容器内向下滚动直至选项出现。"""
        rv_id = self._scrollable_ancestor_id(category)
        for _ in range(4):
            bounds = self._option_bounds_under(category, option)
            if bounds is not None:
                return bounds
            if rv_id is None:
                return None
            self.dev.d(resourceId=rv_id).scroll.forward()
            time.sleep(1)
        return self._option_bounds_under(category, option)

    def select_category_option(self, category: str, option: str, timeout: float = 8.0):
        """展开分类并点选指定选项。"""
        self.ensure_expanded(category, timeout)
        # 点选选项（已是目标值则不点；离屏则滚动查找）
        deadline = time.time() + timeout
        while self.category_value(category) != option:
            assert time.time() < deadline, f"选择 {category} -> {option} 失败"
            bounds = self._scroll_until_option(category, option)
            assert bounds, f"选项不可见: {option}"
            self._click_bounds(bounds)
            time.sleep(1.2)

    def exit_to_home(self):
        """退出播放页回主列表：BACK 关面板 → BACK ×2 退出（3 秒窗口内）。"""
        self.dev.back_to_home_page()
