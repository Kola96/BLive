#!/bin/bash
# 修复 Android TV 模拟器（VirtioWifi）开机不自动连网的问题
#
# 背景：emulator 36.x 对 TV 虚拟设备默认启用 VirtioWifi，网络走虚拟 WiFi。
# 该组合的 autojoin 逻辑损坏：AP 扫得到、网络也保存了，但每次重启都不会
# 自动连接，导致整机断网（eth0 无 IP、DNS 失败）。TV 镜像没有 ethernet
# 服务，禁用 VirtioWifi 也无法回退 NAT，唯一解法是启动后补连。
#
# 用法：
#   scripts/fix-emu-wifi.sh            一次性修复所有在线模拟器
#   scripts/fix-emu-wifi.sh --watch    后台守护，模拟器断网自动修（推荐）
#       nohup scripts/fix-emu-wifi.sh --watch >/dev/null 2>&1 &

set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_DIR="$(grep -s '^sdk\.dir' "$SCRIPT_DIR/../local.properties" | cut -d= -f2)"
ADB="${SDK_DIR:-$HOME/Library/Android/sdk}/platform-tools/adb"
SSID="AndroidWifi"
WATCH_INTERVAL=15
BOOT_TIMEOUT=90
NET_TIMEOUT=40

log() { echo "[emu-wifi-fix] $*"; }

# 等待指定设备完成启动
wait_boot() {
    local dev="$1"
    for _ in $(seq 1 $((BOOT_TIMEOUT / 3))); do
        if [ "$("$ADB" -s "$dev" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
            return 0
        fi
        sleep 3
    done
    return 1
}

# 判断指定设备是否已联网（默认网络已建立）
is_online() {
    local dev="$1"
    local net
    net="$("$ADB" -s "$dev" shell dumpsys connectivity 2>/dev/null | grep -m1 'Active default network')"
    [ -n "$net" ] && [[ "$net" != *"none"* ]]
}

# 修复一台设备：未联网则连 WiFi 并等待网络就绪
fix_device() {
    local dev="$1"
    if is_online "$dev"; then
        return 0
    fi
    if ! wait_boot "$dev"; then
        log "$dev: 等待启动超时，跳过"
        return 1
    fi
    if is_online "$dev"; then
        return 0
    fi
    log "$dev: 断网，连接虚拟 WiFi '$SSID'"
    "$ADB" -s "$dev" shell cmd wifi connect-network "$SSID" open >/dev/null 2>&1
    for _ in $(seq 1 $((NET_TIMEOUT / 2))); do
        if is_online "$dev"; then
            log "$dev: 网络已恢复"
            return 0
        fi
        sleep 2
    done
    log "$dev: 连接后网络仍未就绪"
    return 1
}

run_once() {
    local found=0
    for dev in $("$ADB" devices 2>/dev/null | awk '/^emulator-/ && $2=="device" {print $1}'); do
        found=1
        fix_device "$dev" || true
    done
    if [ "$found" = "0" ]; then
        log "无在线模拟器"
    fi
}

if [ "${1:-}" = "--watch" ]; then
    log "守护模式启动（每 ${WATCH_INTERVAL}s 巡检一次，Ctrl+C 或 kill 退出）"
    while true; do
        run_once
        sleep "$WATCH_INTERVAL"
    done
else
    run_once
fi
