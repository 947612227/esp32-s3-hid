#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
无板方案(adb-only)—— 不需要任何额外硬件,插上 USB 就能点屏幕上的任意位置

与有板方案(ESP32-S3 HID)的区别:
  本目录 = 手机开 USB 调试,用 adb 直接注入点击/滑动。零硬件成本,但需要一根线
          连着电脑(或无线调试),并且会留下 USB 调试痕迹。
  ../src = ESP32-S3 做成 HID 设备注入。免装 App、免调试授权,但需要一块板子。

两者共享同一套"感知"逻辑(截图 / UI 树),只有"注入"那一步不同 ——
见 noboard/drivers.py 里的 Driver 抽象。

常用示例:
  python3 click.py devices                      # 看设备
  python3 click.py info                         # 看机型和屏幕
  python3 click.py ui                           # 列出当前界面能点的元素
  python3 click.py find 确认并知悉               # 只找坐标,不点击
  python3 click.py tap 确认并知悉                # 按文字点
  python3 click.py tap 540,1150                 # 按绝对坐标点
  python3 click.py tap 50%,72%                  # 按百分比点
  python3 click.py swipe 50%,80% 50%,20% 300    # 滑动
  python3 click.py run scripts/demo.txt         # 跑脚本
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

# 让 noboard/ 下的模块可被导入(不论从哪个目录调用本脚本)
_HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(_HERE / "noboard"))
sys.path.insert(0, str(_HERE))

from adb import Adb, AdbError  # noqa: E402
from drivers import AdbDriver, DryRunDriver  # noqa: E402
from runner import Runner, TargetNotFound  # noqa: E402


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="click.py",
        description="无板方案:用 adb 点击/滑动手机屏幕上的任意位置",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    p.add_argument("--serial", "-s", help="指定设备序列号(多台设备时必需)")
    p.add_argument("--adb", help="adb 可执行文件路径(默认自动探测)")
    p.add_argument("--dry-run", action="store_true",
                   help="只解析坐标并打印,不真正注入(强烈建议第一次先用它确认)")
    p.add_argument("--quiet", "-q", action="store_true", help="少打印")
    p.add_argument("--shot-dir", default="shots", help="截图保存目录")

    sub = p.add_subparsers(dest="cmd", required=True)

    sub.add_parser("devices", help="列出设备")
    sub.add_parser("info", help="显示机型 / 屏幕 / 当前前台窗口")
    sub.add_parser("ui", help="列出当前界面可点元素及其中心坐标")
    sub.add_parser("screen", help="只打印屏幕分辨率与密度")
    sub.add_parser("shot", help="截图")

    sp = sub.add_parser("find", help="只解析目标坐标,不注入")
    sp.add_argument("target", help="文字 / text:xx / id:xx / x,y / 50%,50%")

    sp = sub.add_parser("tap", help="点击")
    sp.add_argument("target")
    sp.add_argument("-n", "--times", type=int, default=1, help="连点次数")

    sp = sub.add_parser("long", help="长按")
    sp.add_argument("target")
    sp.add_argument("--ms", type=int, default=800)

    sp = sub.add_parser("swipe", help="滑动")
    sp.add_argument("src", help="起点:目标或坐标")
    sp.add_argument("dst", help="终点:目标或坐标")
    sp.add_argument("--ms", type=int, default=300)

    sp = sub.add_parser("text", help="输入 ASCII 文本")
    sp.add_argument("content")

    sp = sub.add_parser("key", help="发送按键,如 HOME / BACK / ENTER")
    sp.add_argument("keycode")

    sp = sub.add_parser("exec", help="执行一条或多条命令(与脚本语法一致,用 ; 分隔)")
    sp.add_argument("line", nargs=argparse.REMAINDER,
                    help="如:exec 'tap 确认并知悉 ; wait 1 ; tap 50%,50%'")

    sp = sub.add_parser("run", help="执行脚本文件")
    sp.add_argument("script")
    sp.add_argument("--loop", type=int, default=1, help="循环轮数")
    sp.add_argument("--interval", type=float, default=0.0,
                    help="每轮之间的间隔秒数")

    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    try:
        adb = Adb(serial=args.serial, adb_path=args.adb)
    except AdbError as e:
        print(f"错误:{e}", file=sys.stderr)
        return 2

    # ---- 不需要设备的命令 ----
    if args.cmd == "devices":
        try:
            devs = adb.devices()
        except AdbError as e:
            print(f"错误:{e}", file=sys.stderr)
            return 2
        if not devs:
            print("没有检测到设备。")
            print("检查:① 数据线能传数据(不是只充电线)② 手机已开『USB 调试』"
                  "③ 手机上点了『允许 USB 调试』")
            return 1
        for d in devs:
            extra = f"{d.model} product={d.product}" if d.model else ""
            print(f"{d.serial:<22} {d.state:<10} {extra}")
        return 0

    # ---- 其余命令都需要选定设备 ----
    try:
        serial = adb.connect()
    except AdbError as e:
        print(f"错误:{e}", file=sys.stderr)
        return 2

    driver = DryRunDriver() if args.dry_run else AdbDriver(adb)
    runner = Runner(adb, driver, verbose=not args.quiet, shot_dir=args.shot_dir)

    if not args.quiet:
        try:
            print(f"设备:{serial}  {adb.describe()}  屏幕:{adb.screen()}"
                  f"  注入方式:{driver.name}")
        except AdbError:
            pass

    try:
        if args.cmd == "info":
            print(f"序列号:{serial}")
            print(f"型号  :{adb.describe()}")
            print(f"屏幕  :{adb.screen()}")
            print(f"前台  :{adb.current_focus() or '(读取不到)'}")
            print(f"亮屏  :{adb.screen_on()}")
        elif args.cmd == "screen":
            print(adb.screen())
        elif args.cmd == "ui":
            runner.print_ui()
        elif args.cmd == "shot":
            runner.shot()
        elif args.cmd == "find":
            x, y = runner.resolve_target(args.target)
            print(f"{x},{y}")
        elif args.cmd == "tap":
            runner.tap(args.target, args.times)
        elif args.cmd == "long":
            runner.long_press(args.target, args.ms)
        elif args.cmd == "swipe":
            runner.swipe(args.src, args.dst, args.ms)
        elif args.cmd == "text":
            runner.text(args.content)
        elif args.cmd == "key":
            runner.key(args.keycode)
        elif args.cmd == "exec":
            # 支持用 ; 串多个动作:exec "wait 1 ; tap 确认并知悉"
            for piece in " ".join(args.line).split(";"):
                piece = piece.strip()
                if not piece:
                    continue
                runner.log(f"[cmd] {piece}")
                runner.exec_line(piece)
        elif args.cmd == "run":
            runner.run_script(args.script, loop=args.loop, interval=args.interval)
        else:
            print(f"未知命令:{args.cmd}", file=sys.stderr)
            return 2
    except (TargetNotFound, AdbError, ValueError) as e:
        print(f"错误:{e}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\n已中断。")
        return 130

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
