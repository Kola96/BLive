"""pytest 夹具：设备连接、时钟预检、页面状态保证。"""
import subprocess
import time

import pytest

from core.device import Device
from core.pages import MainPage, PlayPage


@pytest.fixture(scope="session")
def dev() -> Device:
    """会话级设备连接，并做两项预检：
    1. 设备在线
    2. 设备时钟与宿主机偏差 < 120s（时钟偏移会导致 CDN SSL 证书校验失败）
    """
    device = Device()
    epoch = device.shell("date", "+%s").strip()
    drift = abs(int(epoch) - int(time.time()))
    if drift > 120:
        pytest.exit(
            f"设备时钟偏移 {drift}s（>120s），请先 Cold Boot 模拟器同步时钟，"
            "否则播放会因 SSL 证书校验失败而全部失败"
        )
    return device


@pytest.fixture(scope="session")
def main_page(dev) -> MainPage:
    return MainPage(dev)


@pytest.fixture(scope="session")
def play_page(dev) -> PlayPage:
    return PlayPage(dev)


@pytest.fixture()
def fresh_app(dev):
    """每个用例前清日志并冷启动 App（回到主列表）。"""
    dev.clear_logcat()
    dev.launch_app(force=True)
    yield
    # 用例间隔离：退回主列表，避免脏状态
    dev.back_to_home_page()


@pytest.fixture()
def in_play_page(dev, main_page, fresh_app) -> PlayPage:
    """进入第一个直播间并确认播放启动，返回播放页对象。"""
    main_page.wait_loaded()
    main_page.open_first_room()
    page = PlayPage(dev)
    page.wait_playing()
    return page
