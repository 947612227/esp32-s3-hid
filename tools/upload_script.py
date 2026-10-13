#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
upload_script.py —— 把本地脚本文件通过串口下发给板子,存进它的 Flash

板子的串口输入缓冲只有 128 字节,不能一次性灌整个文件,所以要逐行发并限速。

用法:
    python3 tools/upload_script.py --port /dev/cu.usbmodem5CE50175731 scripts/demo.txt
    python3 tools/upload_script.py --port ... --name myflow scripts/myflow.txt
    python3 tools/upload_script.py --port ... --list          # 只列出板上已有脚本
    python3 tools/upload_script.py --port ... --run myflow    # 跑一个脚本
    python3 tools/upload_script.py --port ... --delete myflow

不指定 --name 时用文件名(去掉扩展名)作为脚本名。
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

try:
    import serial
    from serial.tools import list_ports
except ImportError:
    print("缺少 pyserial,请先执行: pip install pyserial")
    sys.exit(1)

# 串口 128 字节缓冲,留足余量
LINE_GAP = 0.30        # 每行之间的间隔(秒)
CMD_TIMEOUT = 4.0      # 等回显超时


def pick_port(explicit: str | None) -> str:
    if explicit:
        return explicit
    # 优先 ESP32 常见串口
    hints = ("usbmodem", "wchusbserial", "SLAB", "usbserial", "CP210")
    ports = list(list_ports.comports())
    for p in ports:
        desc = (p.description or "")
        if any(h.lower() in p.device.lower() or h.lower() in desc.lower()
               for h in hints):
            if "R5CW" in p.device:      # 排除手机自带的 usbmodem
                continue
            return p.device
    if ports:
        return ports[0].device
    raise SystemExit("找不到串口,请用 --port 指定")


def read_available(ser: serial.Serial, wait: float = CMD_TIMEOUT) -> str:
    """读到安静为止,返回累计文本。"""
    buf = b""
    end = time.time() + wait
    last = time.time()
    while time.time() < end:
        d = ser.read(4096)
        if d:
            buf += d
            last = time.time()
        else:
            # 已经安静了 0.4 秒就认为输出结束
            if buf and time.time() - last > 0.4:
                break
            time.sleep(0.02)
    return buf.decode("utf-8", "replace")


def send_cmd(ser: serial.Serial, cmd: str, wait: float = CMD_TIMEOUT,
             echo: bool = True) -> str:
    ser.reset_input_buffer()
    ser.write((cmd + "\n").encode("ascii", "ignore"))
    ser.flush()
    out = read_available(ser, wait)
    if echo:
        for line in out.splitlines():
            line = line.strip()
            if line:
                print(f"    {line}")
    return out


def check_recording_failed(out: str) -> bool:
    """录制模式下板子只回显 '  + 行内容';若出现 ERR 说明它没进录制态。"""
    return "ERR" in out and "+" not in out


def upload(ser: serial.Serial, name: str, text: str) -> bool:
    print(f"1) 进入录制:SAVE {name}")
    out = send_cmd(ser, f"SAVE {name}")
    if "ERR" in out:
        print("   ✗ 进入录制失败")
        return False

    print(f"2) 逐行下发({len(text.splitlines())} 行,每行间隔 {LINE_GAP}s)")
    lines = [l for l in text.splitlines()]
    for idx, raw in enumerate(lines):
        line = raw.rstrip()
        ser.reset_input_buffer()
        ser.write((line + "\n").encode("ascii", "ignore"))
        ser.flush()
        time.sleep(LINE_GAP)
        back = ser.read(4096).decode("utf-8", "replace")
        # 板子回显 '  + <行>'
        shown = [b.strip() for b in back.splitlines() if b.strip()]
        mark = "✓" if any(b.startswith("+") for b in shown) else "?"
        print(f"   {mark} [{idx+1:>2}] {line[:70]}")
        if "ERR" in back:
            print("        ✗ 板子报错:", " | ".join(shown))
            send_cmd(ser, "CANCEL")
            return False

    print("3) 结束录制:END")
    out = send_cmd(ser, "END")
    ok = "OK 已保存" in out
    print("   " + ("✓ 保存成功" if ok else "✗ 保存失败"))
    return ok


def main() -> int:
    ap = argparse.ArgumentParser(description="把脚本文件下载到板子的 Flash")
    ap.add_argument("file", nargs="?", help="本地脚本文件路径")
    ap.add_argument("--port", "-p", help="串口设备")
    ap.add_argument("--baud", type=int, default=115200)
    ap.add_argument("--name", "-n", help="板上脚本名(默认取文件名)")
    ap.add_argument("--list", action="store_true", help="列出板上脚本")
    ap.add_argument("--show", metavar="NAME", help="查看某个脚本的步骤")
    ap.add_argument("--run", metavar="NAME", help="执行某个脚本")
    ap.add_argument("--delete", metavar="NAME", help="删除某个脚本")
    args = ap.parse_args()

    port = pick_port(args.port)
    print(f"串口: {port} @ {args.baud}")
    ser = serial.Serial(port, args.baud, timeout=0.2)
    time.sleep(0.3)

    try:
        # ---- 只读/管理类命令 ----
        if args.list:
            send_cmd(ser, "LIST")
            return 0
        if args.show:
            send_cmd(ser, f"LIST {args.show}")
            return 0
        if args.run:
            send_cmd(ser, f"RUN {args.run}", wait=30)
            return 0
        if args.delete:
            send_cmd(ser, f"DEL {args.delete}")
            return 0

        if not args.file:
            ap.error("需要给出脚本文件,或使用 --list/--show/--run/--delete")

        path = Path(args.file)
        if not path.exists():
            print(f"文件不存在: {path}")
            return 2
        text = path.read_text(encoding="utf-8")
        name = args.name or path.stem

        print(f"脚本名: {name}   共 {len(text.splitlines())} 行")
        print()
        if not upload(ser, name, text):
            return 1

        print()
        print("4) 回读校验")
        send_cmd(ser, f"LIST {name}")
        return 0
    finally:
        ser.close()


if __name__ == "__main__":
    sys.exit(main())
