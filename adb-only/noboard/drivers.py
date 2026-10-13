#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
drivers.py —— 输入注入层的抽象

为什么要有这层:无板方案用 adb 注入,有板方案用 ESP32 HID 注入。
两者**感知方式相同**(都是 adb 截图 / UI 树),只有"怎么把动作发出去"不同。
把这一个点抽出来,以后接上 HID 板子只需要再加一个 driver,上层脚本不用改。

  AdbDriver   —— 已实现。手机插 USB,adb 直接注入
  HidDriver   —— 占位。走 ESP32-S3 板子(原生 USB HID 或 BLE HID),见 ../README.md
  DryRunDriver—— 空跑,只打印不执行,用来确认坐标找得对不对
"""

from __future__ import annotations

from typing import Protocol

from adb import Adb


class Driver(Protocol):
    """输入注入接口。"""

    name: str

    def tap(self, x: int, y: int) -> None: ...
    def long_press(self, x: int, y: int, ms: int = 800) -> None: ...
    def swipe(self, x1: int, y1: int, x2: int, y2: int, ms: int = 300) -> None: ...
    def text(self, s: str) -> None: ...
    def key(self, keycode: str | int) -> None: ...


class AdbDriver:
    """用 adb 把动作注入手机(不需要任何额外硬件)。"""

    name = "adb"

    def __init__(self, adb: Adb):
        self.adb = adb

    def tap(self, x: int, y: int) -> None:
        self.adb.tap(x, y)

    def long_press(self, x: int, y: int, ms: int = 800) -> None:
        self.adb.long_press(x, y, ms)

    def swipe(self, x1: int, y1: int, x2: int, y2: int, ms: int = 300) -> None:
        self.adb.swipe(x1, y1, x2, y2, ms)

    def text(self, s: str) -> None:
        self.adb.text(s)

    def key(self, keycode: str | int) -> None:
        self.adb.key(keycode)


class DryRunDriver:
    """只打印不执行。用来在真点之前确认坐标是否正确。"""

    name = "dry-run"

    def tap(self, x: int, y: int) -> None:
        print(f"  [dry-run] tap {x},{y}")

    def long_press(self, x: int, y: int, ms: int = 800) -> None:
        print(f"  [dry-run] long_press {x},{y} {ms}ms")

    def swipe(self, x1: int, y1: int, x2: int, y2: int, ms: int = 300) -> None:
        print(f"  [dry-run] swipe {x1},{y1} -> {x2},{y2} {ms}ms")

    def text(self, s: str) -> None:
        print(f"  [dry-run] text {s!r}")

    def key(self, keycode: str | int) -> None:
        print(f"  [dry-run] key {keycode}")


class HidDriver:
    """走 ESP32-S3 板子的注入通道(占位,尚未实现)。

    接上板子后要实现的是:把归一化坐标 0..1 通过串口发给板子,
    板子再翻译成 HID 触摸报文。坐标换算方式见 tap() 里的注释。

    用法上它和 AdbDriver 完全一样,所以上层动作脚本不需要改动 ——
    这正是把 driver 抽出来的目的。
    """

    name = "hid(未实现)"

    def __init__(self, serial_port: str | None = None, width: int = 0,
                 height: int = 0):
        self.serial_port = serial_port
        self.width = width
        self.height = height

    def _norm(self, x: int, y: int) -> tuple[float, float]:
        """像素坐标 → 归一化坐标(板子侧用 0..32767 逻辑坐标更稳)。"""
        if self.width <= 0 or self.height <= 0:
            raise RuntimeError("HidDriver 需要屏幕尺寸才能换算归一化坐标")
        return x / self.width, y / self.height

    def _todo(self) -> None:
        raise NotImplementedError(
            "HID 通道尚未实现。当前请用 --driver adb,"
            "或在 adb-only/README.md 里查看接入板子的步骤。"
        )

    def tap(self, x: int, y: int) -> None:
        # 板子侧命令格式(见 ../README.md 第 5 节):T <nx> <ny> <ms>
        nx, ny = self._norm(x, y)
        self._todo()

    def long_press(self, x: int, y: int, ms: int = 800) -> None:
        self._todo()

    def swipe(self, x1: int, y1: int, x2: int, y2: int, ms: int = 300) -> None:
        self._todo()

    def text(self, s: str) -> None:
        self._todo()

    def key(self, keycode: str | int) -> None:
        self._todo()
