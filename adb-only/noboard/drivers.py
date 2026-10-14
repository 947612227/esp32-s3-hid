#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
drivers.py —— 输入注入层的抽象

为什么要有这层:无板方案用 adb 注入,有板方案用 ESP32 HID 注入。
两者**感知方式相同**(都是 adb 截图 / UI 树),只有"怎么把动作发出去"不同。
把这一个点抽出来,以后接上 HID 板子只需要再加一个 driver,上层脚本不用改。

  AdbDriver   —— 手机插 USB,adb 直接注入
  HidDriver   —— 走 ESP32-S3 板子的网页接口,板子用 HID 触摸注入
  DryRunDriver—— 空跑,只打印不执行,用来确认坐标找得对不对
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from typing import Optional, Protocol

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


class HidError(RuntimeError):
    """板子拒绝或没响应(HID 未就绪、地址不通、参数错)。"""


class HidDriver:
    """走 ESP32-S3 板子注入:调板子网页接口 /api/gesture、/api/control。

    感知(找控件、截图)仍然用 adb,只有"把动作发出去"换成板子的 HID 触摸 ——
    手机上看到的是外接触摸屏的真实触摸,不是 `input` 命令。

    两种到达板子的方式:
      - 电脑和板子在同一个网络:直接 HTTP(默认)
      - 板子连在手机热点上、电脑够不着:relay=Adb 实例,
        在手机上用 curl 转发(注入本身仍然是 HID,adb 只负责递话)

    板子接口(见 src/common/net_manager.cpp handleGesture / handleControl):
      POST /api/gesture?op=tap|long|swipe&a=x&b=y[&c=x2&d=y2][&ms=N]  坐标 0..1
      POST /api/control?do=home|back|recents                           点导航栏三键
    板子执行完动作才返回,所以连续调用天然按顺序。
    """

    name = "hid"

    # Android 键名/键码 → /api/control 的 do 值。
    # 板子的 home/back/recents 是点屏幕底部三键导航栏;
    # 电源/音量走 HID 多媒体接口,固件默认编译关闭(HID_INCLUDE_CONSUMER=0),
    # 发了板子也回"已发送"但手机没反应 ⇒ 不映射,直接报错。
    _KEYS = {
        "HOME": "home", "KEYCODE_HOME": "home", "3": "home",
        "BACK": "back", "KEYCODE_BACK": "back", "4": "back",
        "APP_SWITCH": "recents", "KEYCODE_APP_SWITCH": "recents",
        "RECENTS": "recents", "187": "recents",
    }

    def __init__(self, board: str, width: int, height: int,
                 relay: Optional[Adb] = None, timeout: int = 15):
        """board:板子地址,如 10.207.182.250 或 http://10.207.182.250
        width/height:手机屏幕像素(adb wm size),用来把像素换算成 0..1
        relay:传 Adb 实例 ⇒ 经手机 curl 转发"""
        if width <= 0 or height <= 0:
            raise HidError("HidDriver 需要屏幕尺寸才能换算归一化坐标")
        self.base = board if board.startswith("http") else "http://" + board
        self.base = self.base.rstrip("/")
        self.width = width
        self.height = height
        self.relay = relay
        self.timeout = timeout
        if relay is not None:
            self.name = "hid(经手机转发)"

    def _norm(self, x: int, y: int) -> tuple[float, float]:
        """像素坐标 → 0..1 归一化坐标(超出屏幕的夹到边上)。"""
        nx = min(max(x / self.width, 0.0), 1.0)
        ny = min(max(y / self.height, 0.0), 1.0)
        return round(nx, 4), round(ny, 4)

    def _post(self, path: str) -> str:
        """POST 到板子,返回板子的 message;失败抛 HidError。"""
        url = self.base + path
        if self.relay is not None:
            # 整条命令作为一个字符串交给手机的 sh:URL 里的 & 必须在引号里
            out = self.relay.shell(
                f"curl -s -m {self.timeout} -X POST -w '\\n%{{http_code}}' '{url}'",
                timeout=self.timeout + 5)
            body, _, code = out.rstrip().rpartition("\n")
            status = int(code) if code.isdigit() else 0
        else:
            req = urllib.request.Request(url, data=b"", method="POST")
            try:
                with urllib.request.urlopen(req, timeout=self.timeout) as r:
                    status, body = r.status, r.read().decode("utf-8", "replace")
            except urllib.error.HTTPError as e:
                status, body = e.code, e.read().decode("utf-8", "replace")
            except OSError as e:
                raise HidError(f"连不上板子 {self.base}:{e}"
                               "(板子在手机热点上时加 --relay 经手机转发)") from e
        try:
            msg = json.loads(body).get("message", body)
        except ValueError:
            msg = body
        if status != 200:
            raise HidError(f"板子返回 {status or '无响应'}:{msg}")
        return msg

    def tap(self, x: int, y: int) -> None:
        nx, ny = self._norm(x, y)
        self._post(f"/api/gesture?op=tap&a={nx}&b={ny}")

    def long_press(self, x: int, y: int, ms: int = 800) -> None:
        nx, ny = self._norm(x, y)
        self._post(f"/api/gesture?op=long&a={nx}&b={ny}&ms={int(ms)}")

    def swipe(self, x1: int, y1: int, x2: int, y2: int, ms: int = 300) -> None:
        a, b = self._norm(x1, y1)
        c, d = self._norm(x2, y2)
        self._post(f"/api/gesture?op=swipe&a={a}&b={b}&c={c}&d={d}&ms={int(ms)}")

    def text(self, s: str) -> None:
        raise HidError("板子不能输入文字:键盘接口默认编译关闭(HID_INCLUDE_KEYBOARD=0),"
                       "而且中文输入法会把按键变成候选词。需要输文字请用 --driver adb")

    def key(self, keycode: str | int) -> None:
        k = str(keycode).upper()
        do = self._KEYS.get(k)
        if do is None:
            raise HidError(f"板子不支持按键 {keycode}:只支持 HOME / BACK / APP_SWITCH"
                           "(点导航栏三键),其它键请用 --driver adb")
        self._post(f"/api/control?do={do}")
