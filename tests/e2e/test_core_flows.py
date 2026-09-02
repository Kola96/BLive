"""核心链路端到端测试。

覆盖：启动加载 → 进房播放 → 弹幕连接 → 设置面板 → 弹幕设置持久化 → HEVC 切换。
运行前确保模拟器已启动且安装了最新 debug 包（见 README.md）。
"""
import time

import pytest

from core.pages import DANMU_SETTINGS_CATEGORIES, SETTINGS_CATEGORIES


class TestHomePage:
    def test_launch_and_load(self, dev, main_page, fresh_app):
        """启动后主网格加载出直播间卡片。"""
        count = main_page.wait_loaded()
        assert count > 0, "列表为空"


class TestPlayback:
    def test_enter_room_and_play(self, dev, main_page, fresh_app):
        """进入第一个直播间，播放器启动且无错误。"""
        main_page.wait_loaded()
        main_page.open_first_room()
        from core.pages import PlayPage
        page = PlayPage(dev)
        page.wait_playing()

    def test_danmu_connected(self, in_play_page):
        """弹幕 TCP 连接建立成功，且不被服务器秒断（uid 修复回归）。"""
        in_play_page.wait_danmu_connected()
        time.sleep(5)  # 观察一个窗口期
        assert not in_play_page.danmu_disconnected(), "弹幕连接被服务器断开后重连"


class TestPlaySettings:
    def test_settings_panel_complete(self, in_play_page):
        """设置面板包含全部播放与弹幕设置项（含新增的速度/显示区域）。"""
        in_play_page.open_settings()
        for label in SETTINGS_CATEGORIES + DANMU_SETTINGS_CATEGORIES:
            assert in_play_page.dev.d(text=label).exists, f"设置项缺失: {label}"
        in_play_page.close_settings()

    def test_danmu_area_setting(self, in_play_page):
        """弹幕显示区域可切换，值即时更新；用例结束恢复全屏。"""
        page = in_play_page
        page.open_settings()
        try:
            page.select_category_option("显示区域", "1/4屏")
            assert page.category_value("显示区域") == "1/4屏"
        finally:
            # 恢复现场：设置是持久化的，不能污染后续用例和用户配置
            page.select_category_option("显示区域", "全屏")
            page.close_settings()

    def test_hevc_switch_if_available(self, dev, main_page, fresh_app):
        """HEVC 切换后编码值更新为 H.265 且播放无错误。
        依次尝试前 5 个直播间，都不支持 HEVC 才跳过。"""
        from core.pages import PlayPage
        page = PlayPage(dev)
        main_page.wait_loaded()

        found_hevc = False
        for _ in range(5):
            dev.key("DPAD_RIGHT")   # 首个房间：tab→内容；之后：焦点移到下一张卡片
            time.sleep(1.5)
            dev.key("DPAD_CENTER")
            page.wait_playing()
            page.open_settings()
            page.ensure_expanded("编码")
            if page._option_bounds_under("编码", "H.265 (HEVC)") is not None:
                found_hevc = True
                break
            page.exit_to_home()
            time.sleep(1.5)
        if not found_hevc:
            pytest.skip("前 5 个直播间都不提供 HEVC 流（关注列表中大主播下播时会跳过）")

        page.dev.clear_logcat()
        try:
            page.select_category_option("编码", "H.265 (HEVC)")
            time.sleep(5)  # 无缝切换：预加载 + 上屏
            assert page.category_value("编码") == "H.265 (HEVC)"
            errors = page.dev.logcat("Source error")
            assert not errors, f"HEVC 切换后播放出错: {errors[-1]}"
        finally:
            page.select_category_option("编码", "H.264 (AVC)")
            page.close_settings()


class TestSettingsPersistence:
    def test_danmu_settings_persist_across_restart(self, dev, in_play_page):
        """播放页修改弹幕设置即持久化：杀进程重启后设置值仍在。"""
        page = in_play_page
        page.open_settings()
        page.select_category_option("不透明度", "50%")
        page.close_settings()

        # 杀进程重进直播间
        dev.clear_logcat()
        dev.launch_app(force=True)
        from core.pages import MainPage
        main = MainPage(dev)
        main.wait_loaded()
        main.open_first_room()
        page.wait_playing()

        page.open_settings()
        try:
            assert page.category_value("不透明度") == "50%"
        finally:
            page.select_category_option("不透明度", "100%")
            page.close_settings()
