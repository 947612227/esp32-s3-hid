#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
adb.py —— adb 驱动层

只做三件事,全部通过调用本机 adb 完成:
  1. 设备/屏幕信息
  2. 屏幕采集(截图、UI 树)
  3. 输入注入(点击/长按/滑动/输入文本/按键)

不依赖任何第三方库(不需要 pyserial / uiautomator2 / opencv)。
adb 路径按常见位置自动探测,也可以用 ADB 环境变量或 --adb 参数指定。
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Optional


class AdbError(RuntimeError):
    """adb 调用失败。"""


def find_adb() -> str:
    """按优先级探测 adb 可执行文件位置。"""
    # 1. 环境变量
    env = os.environ.get("ADB")
    if env and Path(env).exists():
        return env
    # 2. PATH
    found = shutil.which("adb")
    if found:
        return found
    # 3. 常见安装位置
    home = Path.home()
    candidates = [
        home / "app" / "android_sdk" / "platform-tools" / "adb",
        home / "Library" / "Android" / "sdk" / "platform-tools" / "adb",
        home / "Android" / "Sdk" / "platform-tools" / "adb",
        Path("/opt/homebrew/bin/adb"),
        Path("/usr/local/bin/adb"),
        Path("/usr/bin/adb"),
    ]
    for c in candidates:
        if c.exists():
            return str(c)
    raise AdbError(
        "找不到 adb。请安装 Android platform-tools,"
        "或设置环境变量 ADB=/path/to/adb"
    )


@dataclass
class DeviceInfo:
    serial: str
    state: str
    model: str = ""
    product: str = ""
    device: str = ""


@dataclass
class ScreenInfo:
    width: int
    height: int
    density: int

    def __str__(self) -> str:
        return f"{self.width}x{self.height} @ {self.density}dpi"


class Adb:
    """一个极简的 adb 封装。"""

    def __init__(self, serial: Optional[str] = None, adb_path: Optional[str] = None,
                 timeout: int = 30):
        self.adb = adb_path or find_adb()
        self.serial = serial
        self.timeout = timeout
        self._screen: Optional[ScreenInfo] = None

    # ------------------------------------------------------------------ 底层
    def _base(self) -> list[str]:
        cmd = [self.adb]
        if self.serial:
            cmd += ["-s", self.serial]
        return cmd

    def raw(self, *args: str, timeout: Optional[int] = None,
            binary: bool = False):
        """执行 adb 子命令。binary=True 时返回 bytes(不 strip)。"""
        cmd = self._base() + list(args)
        try:
            proc = subprocess.run(
                cmd,
                capture_output=True,
                timeout=timeout or self.timeout,
                check=False,
            )
        except subprocess.TimeoutExpired as e:
            raise AdbError(f"adb 超时({timeout or self.timeout}s): {' '.join(args)}") from e

        if proc.returncode != 0:
            err = proc.stderr.decode("utf-8", "replace").strip()
            raise AdbError(f"adb 失败(exit {proc.returncode}): {' '.join(args)}\n{err}")

        if binary:
            return proc.stdout
        return proc.stdout.decode("utf-8", "replace")

    def shell(self, *args: str, timeout: Optional[int] = None) -> str:
        """执行 adb shell 命令,返回 stdout 文本。"""
        return self.raw("shell", *args, timeout=timeout)

    # ------------------------------------------------------------------ 设备
    def devices(self) -> list[DeviceInfo]:
        out = self.raw("devices", "-l")
        result = []
        for line in out.splitlines()[1:]:
            line = line.strip()
            if not line:
                continue
            parts = line.split()
            serial = parts[0]
            state = parts[1] if len(parts) > 1 else "unknown"
            info = DeviceInfo(serial=serial, state=state)
            for p in parts[2:]:
                if p.startswith("model:"):
                    info.model = p.split(":", 1)[1]
                elif p.startswith("product:"):
                    info.product = p.split(":", 1)[1]
                elif p.startswith("device:"):
                    info.device = p.split(":", 1)[1]
            result.append(info)
        return result

    def online_devices(self) -> list[DeviceInfo]:
        return [d for d in self.devices() if d.state == "device"]

    def connect(self, serial: Optional[str] = None) -> str:
        """选定要操作的设备(不传则自动选唯一在线设备)。"""
        if serial:
            self.serial = serial
            return serial
        devs = self.online_devices()
        if not devs:
            raise AdbError("没有在线设备。请确认:数据线可传数据、手机已开 USB 调试、"
                           "并在手机上点了『允许 USB 调试』")
        if len(devs) > 1:
            names = ", ".join(d.serial for d in devs)
            raise AdbError(f"检测到多台设备,请用 --serial 指定:{names}")
        self.serial = devs[0].serial
        return self.serial

    def prop(self, key: str) -> str:
        return self.shell("getprop", key).strip()

    def describe(self) -> str:
        return (f"{self.prop('ro.product.brand')} {self.prop('ro.product.model')} "
                f"(Android {self.prop('ro.build.version.release')}, "
                f"SDK {self.prop('ro.build.version.sdk')})")

    # ------------------------------------------------------------------ 屏幕
    def screen(self, refresh: bool = False) -> ScreenInfo:
        """屏幕分辨率与密度(会缓存)。"""
        if self._screen is not None and not refresh:
            return self._screen
        out = self.shell("wm", "size")
        m = re.search(r"(\d+)x(\d+)", out)
        if not m:
            raise AdbError(f"无法解析屏幕尺寸: {out!r}")
        w, h = int(m.group(1)), int(m.group(2))

        dout = self.shell("wm", "density")
        d = re.search(r"(\d+)", dout)
        density = int(d.group(1)) if d else 0

        self._screen = ScreenInfo(w, h, density)
        return self._screen

    def screenshot(self, out_path: str | Path) -> Path:
        """截图并保存到本地(用 exec-out 直传二进制,避免中间文件)。"""
        out_path = Path(out_path)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        data = self.raw("exec-out", "screencap", "-p", binary=True, timeout=60)
        if not data:
            raise AdbError("截图为空(可能被 FLAG_SECURE 拦截,或屏幕未点亮)")
        out_path.write_bytes(data)
        return out_path

    def ui_dump(self, remote: str = "/sdcard/window_dump.xml") -> str:
        """抓取 UI 层级 XML 并返回其内容。"""
        self.shell("uiautomator", "dump", remote, timeout=60)
        return self.shell("cat", remote, timeout=60)

    def current_focus(self) -> str:
        """当前前台窗口(需要 dumpsys,部分 ROM 可能受限)。"""
        try:
            out = self.shell("dumpsys", "window", timeout=30)
        except AdbError:
            return ""
        for line in out.splitlines():
            if "mCurrentFocus" in line:
                return line.strip()
        return ""

    def screen_on(self) -> bool:
        """屏幕是否点亮(用于避开息屏导致的点击无效)。"""
        try:
            out = self.shell("dumpsys", "power", timeout=30)
        except AdbError:
            return True
        return "mWakefulness=Awake" in out

    # ------------------------------------------------------------------ 输入
    def tap(self, x: int, y: int) -> None:
        self.shell("input", "tap", str(int(x)), str(int(y)))

    def long_press(self, x: int, y: int, ms: int = 800) -> None:
        """长按:input swipe 起点=终点 且带时长,等价于长按。"""
        self.shell("input", "swipe", str(int(x)), str(int(y)),
                   str(int(x)), str(int(y)), str(int(ms)))

    def swipe(self, x1: int, y1: int, x2: int, y2: int, ms: int = 300) -> None:
        self.shell("input", "swipe", str(int(x1)), str(int(y1)),
                   str(int(x2)), str(int(y2)), str(int(ms)))

    def text(self, s: str) -> None:
        """输入文本(ASCII)。

        注意:adb 的 `input text` 只支持 ASCII,中文会失败。需要中文输入时
        请在手机上装 ADBKeyboard 这类输入法,再用 IME 广播的方式输入
        (见 README「输入中文」一节)。
        """
        # adb input text 用 %s 表示空格
        payload = s.replace(" ", "%s")
        self.shell("input", "text", payload)

    def key(self, keycode: str | int) -> None:
        """发送按键。可传名称(如 HOME/BACK/ENTER)或数字 keycode。"""
        self.shell("input", "keyevent", str(keycode))

    def wake(self) -> None:
        """点亮屏幕(息屏状态下 input 事件会被丢弃)。"""
        if not self.screen_on():
            self.key("KEYCODE_WAKEUP")
            time.sleep(0.6)
