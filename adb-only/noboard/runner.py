#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
runner.py —— 动作层:把"点击某个按钮"翻译并执行

核心能力:
  - 找目标:UI 树里按文字/id 找到控件,回退到最近的可点击祖先,取中心坐标
  - 发动作:通过 Driver(adb / dry-run / HID 板子)注入
  - 脚本:自带一套极简命令语言,批量执行并逐步反馈

命令语言(CLI 与脚本共用,一行一条):
  tap <目标> [次数]       点击,目标可为文字 / text: / id: / desc: / x,y / 50%,50%
  long <目标> [ms]        长按
  swipe <起点> <终点> [ms] 滑动,起点终点同样支持目标写法或坐标
  text <内容>             输入 ASCII 文本
  key <键名>              发送按键(HOME / BACK / ENTER / ...)
  wait <秒>               等待
  ui                      打印当前界面上的可点元素
  shot [文件名]           截图
"""

from __future__ import annotations

import time
from pathlib import Path
from typing import Optional

from adb import Adb, AdbError
from drivers import Driver, HidError
from targets import UINode, parse_coord, parse_ui, resolve, walk


class TargetNotFound(RuntimeError):
    """UI 树里找不到指定目标。"""


class Runner:
    def __init__(self, adb: Adb, driver: Driver, verbose: bool = True,
                 shot_dir: str | Path = "shots"):
        self.adb = adb
        self.driver = driver
        self.verbose = verbose
        self.shot_dir = Path(shot_dir)
        self._ui: Optional[UINode] = None

    # ------------------------------------------------------------------ 日志
    def log(self, msg: str) -> None:
        if self.verbose:
            print(msg, flush=True)

    # ------------------------------------------------------------------ 感知
    def refresh_ui(self) -> Optional[UINode]:
        """重新抓一次 UI 树。"""
        xml = self.adb.ui_dump()
        self._ui = parse_ui(xml)
        if self._ui is None:
            self.log("  ⚠ UI 树为空或解析失败(自绘/WebView 界面常见),只能按坐标点")
        return self._ui

    @property
    def ui(self) -> Optional[UINode]:
        if self._ui is None:
            self.refresh_ui()
        return self._ui

    def screen_size(self) -> tuple[int, int]:
        s = self.adb.screen()
        return s.width, s.height

    # ------------------------------------------------------------------ 解析
    def resolve_target(self, spec: str, refresh: bool = True) -> tuple[int, int]:
        """把目标描述解析成像素坐标。

        目标写法:
          540,1150          绝对像素
          50%,50%           百分比
          center            屏幕中心
          确认并知悉          按文字找控件(可点击祖先回退)
          text:确认          文字包含匹配
          id:btn_confirm    resource-id 后缀匹配
          desc:xxx          content-desc 包含匹配
        """
        w, h = self.screen_size()
        s = spec.strip()

        # 1) 看起来像坐标就直接当坐标
        looks_like_coord = (
            s.lower() in ("center", "centre", "中心", "c")
            or "," in s
        )
        if looks_like_coord:
            try:
                return parse_coord(s, w, h)
            except ValueError:
                pass

        # 2) 否则当 UI 目标找
        if refresh:
            self.refresh_ui()
        node = resolve(self.ui, s)
        if node is None:
            raise TargetNotFound(
                f"UI 树里找不到目标 {s!r}。可以先执行 `ui` 看当前有哪些可点元素,"
                f"或直接用坐标 `tap 540,1150`"
            )
        cx, cy = node.center
        self.log(f"  找到目标 {node.label} → 中心 {cx},{cy}  bounds={node.bounds}")
        return cx, cy

    # ------------------------------------------------------------------ 动作
    def tap(self, spec: str, times: int = 1) -> None:
        """点击目标(文字/id/坐标)。"""
        self.adb.wake()
        # 每次点击前重新解析,避免界面变化导致坐标过期
        for i in range(max(1, times)):
            x, y = self.resolve_target(spec, refresh=(i > 0 or times > 1))
            self.log(f"→ tap {x},{y}" + (f"  ({i + 1}/{times})" if times > 1 else ""))
            self.driver.tap(x, y)
            if i + 1 < times:
                time.sleep(0.15)

    def long_press(self, spec: str, ms: int = 800) -> None:
        self.adb.wake()
        x, y = self.resolve_target(spec)
        self.log(f"→ long_press {x},{y} {ms}ms")
        self.driver.long_press(x, y, ms)

    def swipe(self, src: str, dst: str, ms: int = 300) -> None:
        self.adb.wake()
        x1, y1 = self.resolve_target(src)
        x2, y2 = self.resolve_target(dst, refresh=False)
        self.log(f"→ swipe {x1},{y1} → {x2},{y2} {ms}ms")
        self.driver.swipe(x1, y1, x2, y2, ms)

    def text(self, s: str) -> None:
        self.log(f"→ text {s!r}")
        self.driver.text(s)

    def key(self, code: str | int) -> None:
        self.log(f"→ key {code}")
        self.driver.key(code)

    def wait(self, seconds: float) -> None:
        self.log(f"→ wait {seconds}s")
        time.sleep(seconds)

    def shot(self, name: Optional[str] = None) -> Path:
        self.shot_dir.mkdir(parents=True, exist_ok=True)
        fname = name or time.strftime("shot_%Y%m%d_%H%M%S.png")
        if not fname.endswith(".png"):
            fname += ".png"
        path = self.shot_dir / fname
        self.adb.screenshot(path)
        self.log(f"→ shot 已保存 {path}")
        return path

    # ------------------------------------------------------------------ 展示
    def print_ui(self, clickable_only: bool = True) -> None:
        """打印当前界面元素,帮你确认要点哪里。"""
        self.refresh_ui()
        nodes = [n for n in walk(self.ui)
                 if n.visible and n.area > 0 and (n.clickable or not clickable_only)]
        if not nodes:
            self.log("当前 UI 树没有任何可点元素(自绘/WebView 界面常见)。"
                     "请改用坐标点击。")
            return

        self.log(f"当前界面可点元素({len(nodes)} 个):")
        self.log(f"  {'中心坐标':<14} {'bounds':<26} 元素")
        for n in nodes:
            cx, cy = n.center
            self.log(f"  {f'{cx},{cy}':<14} {str(n.bounds):<26} {n.label[:40]}")

    # ------------------------------------------------------------------ 脚本
    def run_script(self, path: str | Path, loop: int = 1,
                   interval: float = 0.0) -> None:
        """执行脚本文件。每行一条命令,`#` 开头为注释。"""
        lines = []
        for raw in Path(path).read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            lines.append(line)

        for round_no in range(1, max(1, loop) + 1):
            if loop > 1:
                self.log(f"===== 第 {round_no}/{loop} 轮 =====")
            for line in lines:
                self.log(f"[cmd] {line}")
                try:
                    self.exec_line(line)
                except (TargetNotFound, AdbError, HidError) as e:
                    self.log(f"  ✗ 失败:{e}")
            if round_no < loop and interval > 0:
                self.wait(interval)

    def exec_line(self, line: str) -> None:
        """执行一条命令(CLI 与脚本共用同一套分派)。"""
        parts = line.split()
        if not parts:
            return
        cmd = parts[0].lower()
        args = parts[1:]

        if cmd == "tap":
            if not args:
                raise ValueError("tap 需要目标,如 `tap 确认并知悉` 或 `tap 540,1150`")
            times = int(args[1]) if len(args) > 1 else 1
            self.tap(args[0], times)
        elif cmd == "long":
            if not args:
                raise ValueError("long 需要目标")
            ms = int(args[1]) if len(args) > 1 else 800
            self.long_press(args[0], ms)
        elif cmd == "swipe":
            if len(args) < 2:
                raise ValueError("swipe 需要起点和终点,如 `swipe 50%,80% 50%,20%`")
            ms = int(args[2]) if len(args) > 2 else 300
            self.swipe(args[0], args[1], ms)
        elif cmd == "text":
            if not args:
                raise ValueError("text 需要内容")
            self.text(" ".join(args))
        elif cmd == "key":
            if not args:
                raise ValueError("key 需要键名,如 HOME / BACK / ENTER")
            self.key(args[0])
        elif cmd == "wait":
            self.wait(float(args[0]) if args else 1.0)
        elif cmd == "ui":
            self.print_ui()
        elif cmd == "shot":
            self.shot(args[0] if args else None)
        else:
            raise ValueError(f"未知命令:{cmd}")
