#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
优化13 单元测试:adb-only 的 HidDriver(不需要手机和板子)

用假的 Adb(只记录发给手机 sh 的命令,返回预设的 "正文\\n状态码")验证:
  - 像素 → 0..1 换算、夹边
  - tap / long / swipe / key 拼出的板子接口 URL 和固件 handleGesture/handleControl 的参数一致
  - 经手机转发时整条 curl 命令的 URL 在单引号里(& 不会被 sh 当成后台符)
  - 板子返回非 200(如 409 HID 未就绪)、text、不支持的键 ⇒ 抛 HidError

用法: python3 tests/adb_only/test_hid_driver.py
"""

import re
import sys
import unittest
from pathlib import Path

# adb-only/noboard 不是包,按 click.py 的方式加进搜索路径
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "adb-only" / "noboard"))

from drivers import HidDriver, HidError  # noqa: E402


class FakeAdb:
    """假的 Adb:记下每条 shell 命令,按顺序返回预设响应。"""

    def __init__(self, replies=None):
        self.cmds = []                       # 发给手机的命令
        self.replies = list(replies or [])   # 预设响应(默认 200 成功)

    def shell(self, *args, timeout=None):
        self.cmds.append(" ".join(args))
        if self.replies:
            return self.replies.pop(0)
        return '{"ok":true,"message":"已执行"}\n200'


def url_of(cmd):
    """从 curl 命令里取出单引号包着的 URL。"""
    m = re.search(r"'(http://[^']+)'", cmd)
    assert m, f"命令里找不到单引号 URL:{cmd}"
    return m.group(1)


class HidDriverTest(unittest.TestCase):
    def setUp(self):
        self.adb = FakeAdb()
        # 屏幕 1000x2000,方便心算
        self.d = HidDriver("10.0.0.5", 1000, 2000, relay=self.adb)

    def test_tap_换算和URL(self):
        self.d.tap(250, 500)
        self.assertEqual(url_of(self.adb.cmds[0]),
                         "http://10.0.0.5/api/gesture?op=tap&a=0.25&b=0.25")

    def test_超出屏幕夹到边上(self):
        self.d.tap(-30, 9999)
        self.assertEqual(url_of(self.adb.cmds[0]),
                         "http://10.0.0.5/api/gesture?op=tap&a=0.0&b=1.0")

    def test_long_带时长(self):
        self.d.long_press(500, 1000, 900)
        self.assertEqual(url_of(self.adb.cmds[0]),
                         "http://10.0.0.5/api/gesture?op=long&a=0.5&b=0.5&ms=900")

    def test_swipe_四个坐标和时长(self):
        self.d.swipe(500, 1600, 500, 400, 300)
        self.assertEqual(url_of(self.adb.cmds[0]),
                         "http://10.0.0.5/api/gesture?op=swipe&a=0.5&b=0.8&c=0.5&d=0.2&ms=300")

    def test_key_映射到导航栏三键(self):
        for k, do in [("HOME", "home"), ("KEYCODE_BACK", "back"), (4, "back"),
                      ("app_switch", "recents"), (187, "recents")]:
            self.adb.cmds.clear()
            self.d.key(k)
            self.assertEqual(url_of(self.adb.cmds[0]),
                             f"http://10.0.0.5/api/control?do={do}", k)

    def test_不支持的键和文字_报错且不发请求(self):
        with self.assertRaises(HidError):
            self.d.key("VOLUME_UP")
        with self.assertRaises(HidError):
            self.d.text("abc")
        self.assertEqual(self.adb.cmds, [])

    def test_板子返回409_抛错带板子原话(self):
        d = HidDriver("http://10.0.0.5/", 1000, 2000,
                      relay=FakeAdb(['{"message":"HID 未就绪"}\n409']))
        with self.assertRaises(HidError) as cm:
            d.tap(1, 1)
        self.assertIn("409", str(cm.exception))
        self.assertIn("HID 未就绪", str(cm.exception))

    def test_转发无响应_抛错(self):
        d = HidDriver("10.0.0.5", 1000, 2000, relay=FakeAdb(["\n000"]))
        with self.assertRaises(HidError):
            d.tap(1, 1)

    def test_地址带http和斜杠也能用(self):
        d = HidDriver("http://10.0.0.5/", 1000, 2000, relay=self.adb)
        d.tap(0, 0)
        self.assertTrue(url_of(self.adb.cmds[0]).startswith("http://10.0.0.5/api/"))

    def test_curl命令_URL在单引号里且带状态码输出(self):
        self.d.swipe(1, 2, 3, 4, 100)
        cmd = self.adb.cmds[0]
        self.assertTrue(cmd.startswith("curl -s -m 15 -X POST -w '\\n%{http_code}' 'http://"), cmd)
        self.assertNotRegex(cmd.replace(url_of(cmd), ""), r"&")   # & 只出现在引号内的 URL 里

    def test_没有屏幕尺寸_拒绝构造(self):
        with self.assertRaises(HidError):
            HidDriver("10.0.0.5", 0, 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
