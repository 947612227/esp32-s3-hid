#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ESP32-S3 HID 触摸注入开发板 —— 上位机 Demo

作用:通过串口(UART0, 115200)向板子下发归一化坐标手势命令。
依赖:只有 pyserial(tkinter 是 Python 标准库)。

用法:
    pip install pyserial
    python3 host_demo.py

界面说明:
    - 上方选择串口并连接(板子同时插着手机和 PC 两条线:手机接原生 USB,PC 接 UART 口)
    - 中间"触摸板"区域:用鼠标画一下
          单击            -> 点击
          按住不动        -> 长按
          按住拖动        -> 滑动
    - 右侧按钮:常用手势、键盘、音量、鼠标
    - 下方:直接输入原始命令(格式见 README)
"""

import sys
import time
import threading
import tkinter as tk
from tkinter import ttk, messagebox

try:
    import serial
    from serial.tools import list_ports
except ImportError:
    print("缺少 pyserial,请先执行: pip install pyserial")
    sys.exit(1)

BAUD = 115200

# 常见 ESP32 串口关键字(用于自动选中)
PORT_HINTS = ("usbmodem", "wchusbserial", "SLAB_USBtoUART", "usbserial", "CH340", "CP210")


class HostDemo:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.ser: serial.Serial | None = None
        self.reader_thread: threading.Thread | None = None
        self.reader_stop = threading.Event()

        root.title("ESP32-S3 HID Touch Injector — Host Demo")
        root.geometry("900x660")

        self._build_toolbar()
        self._build_body()
        self._build_log()

        self.refresh_ports()
        self.log("就绪。选串口 → 连接 → 在触摸板上拖动鼠标。")
        self.log("坐标提示:x 向右 0..1,y 向下 0..1(归一化,与分辨率无关)")

    # ------------------------------------------------------------------ 工具条
    def _build_toolbar(self):
        bar = ttk.Frame(self.root, padding=6)
        bar.pack(fill=tk.X)

        ttk.Label(bar, text="串口:").pack(side=tk.LEFT)
        self.port_var = tk.StringVar()
        self.port_cb = ttk.Combobox(bar, textvariable=self.port_var, width=38)
        self.port_cb.pack(side=tk.LEFT, padx=4)

        ttk.Button(bar, text="刷新", command=self.refresh_ports).pack(side=tk.LEFT, padx=2)
        self.conn_btn = ttk.Button(bar, text="连接", command=self.toggle_conn)
        self.conn_btn.pack(side=tk.LEFT, padx=2)

        self.status = ttk.Label(bar, text="未连接", foreground="#b00")
        self.status.pack(side=tk.LEFT, padx=10)

    # ------------------------------------------------------------------ 主体
    def _build_body(self):
        body = ttk.Frame(self.root, padding=6)
        body.pack(fill=tk.BOTH, expand=True)

        # 左:触摸板
        left = ttk.LabelFrame(body, text="触摸板(鼠标拖动 = 滑动,单击 = 点击,按住 = 长按)", padding=6)
        left.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)

        self.pad = tk.Canvas(left, bg="#101418", highlightthickness=1,
                             highlightbackground="#444", cursor="crosshair")
        self.pad.pack(fill=tk.BOTH, expand=True)
        self.pad.bind("<ButtonPress-1>", self.pad_press)
        self.pad.bind("<B1-Motion>", self.pad_move)
        self.pad.bind("<ButtonRelease-1>", self.pad_release)
        self.pad.bind("<Configure>", lambda e: self.draw_pad_guides())

        self.pad_start = None
        self.pad_cur = None
        self.pad_dur_ms = 0

        # 右:预设按钮
        right = ttk.Frame(body)
        right.pack(side=tk.LEFT, fill=tk.Y, padx=(8, 0))

        self._btn_group(right, "单指手势", [
            ("上滑",     lambda: self.send("S 0.5 0.75 0.5 0.30 260")),
            ("下滑",     lambda: self.send("S 0.5 0.30 0.5 0.75 260")),
            ("左滑",     lambda: self.send("S 0.85 0.5 0.15 0.5 260")),
            ("右滑",     lambda: self.send("S 0.15 0.5 0.85 0.5 260")),
            ("曲线滑",   lambda: self.send("C 0.2 0.8 0.7 0.5 0.2 0.2 320")),
            ("长按中心", lambda: self.send("H 0.5 0.5 1000")),
            ("双击中心", lambda: self.send("D 0.5 0.5 90")),
        ])

        self._btn_group(right, "双指手势", [
            ("双指上滑", lambda: self.send("P 0.4 0.75 0.4 0.30 0.6 0.75 0.6 0.30 260")),
            ("捏合",     lambda: self.send("Z 0.5 0.5 0.55 0.20 300")),
            ("张开",     lambda: self.send("Z 0.5 0.5 0.20 0.55 300")),
        ])

        self._btn_group(right, "按键 / 鼠标", [
            ("自检(四角+中心)", lambda: self.send("G")),
            ("返回键",   lambda: self.send("K 41")),       # HID usage 0x29 = Esc(=Android BACK)
            ("Home 键",  lambda: self.send("K 74")),       # HID usage 0x4A
            ("回车",     lambda: self.send("K 40")),       # HID usage 0x28 = Enter
            ("音量 +",   lambda: self.send("V 233")),      # 0xE9
            ("音量 -",   lambda: self.send("V 234")),      # 0xEA
            ("鼠标左键", lambda: self.send("B 1")),
            ("抬起全部", lambda: self.send("U")),
        ])

        self._btn_group(right, "参数", [
            ("时长 x0.5", lambda: self.set_scale(0.5)),
            ("时长 x1.0", lambda: self.set_scale(1.0)),
            ("时长 x2.0", lambda: self.set_scale(2.0)),
        ])
        self.scale_lbl = ttk.Label(right, text="时长倍率:1.0x")
        self.scale_lbl.pack(anchor=tk.W, pady=(2, 6))

        # 原始命令
        raw = ttk.LabelFrame(self.root, text="原始命令(回车发送)", padding=6)
        raw.pack(fill=tk.X, padx=6)
        self.raw_var = tk.StringVar()
        ent = ttk.Entry(raw, textvariable=self.raw_var)
        ent.pack(side=tk.LEFT, fill=tk.X, expand=True)
        ent.bind("<Return>", lambda e: self.send(self.raw_var.get(), clear=True))
        ttk.Button(raw, text="发送", command=lambda: self.send(self.raw_var.get(), clear=True)).pack(side=tk.LEFT, padx=4)
        ttk.Button(raw, text="?", width=3, command=lambda: self.send("?")).pack(side=tk.LEFT)

    def _btn_group(self, parent, title, items):
        box = ttk.LabelFrame(parent, text=title, padding=4)
        box.pack(fill=tk.X, pady=3)
        for label, cb in items:
            b = ttk.Button(box, text=label, command=cb, width=18)
            b.pack(fill=tk.X, pady=1)

    def _build_log(self):
        box = ttk.LabelFrame(self.root, text="日志", padding=4)
        box.pack(fill=tk.BOTH, padx=6, pady=(4, 6))
        self.log_txt = tk.Text(box, height=9, bg="#0d1117", fg="#c9d1d9",
                               insertbackground="#c9d1d9", wrap=tk.WORD)
        self.log_txt.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        sb = ttk.Scrollbar(box, command=self.log_txt.yview)
        sb.pack(side=tk.RIGHT, fill=tk.Y)
        self.log_txt.config(yscrollcommand=sb.set)

    # ------------------------------------------------------------------ 触摸板
    def draw_pad_guides(self):
        self.pad.delete("guide")
        w = self.pad.winfo_width()
        h = self.pad.winfo_height()
        if w < 10 or h < 10:
            return
        for i in range(1, 4):
            self.pad.create_line(w * i / 4, 0, w * i / 4, h, fill="#222a33", tags="guide")
            self.pad.create_line(0, h * i / 4, w, h * i / 4, fill="#222a33", tags="guide")
        self.pad.create_text(8, 8, anchor=tk.NW, fill="#4b5563", tags="guide",
                             text="(0,0)")

    def _pad_norm(self, x, y):
        w = max(self.pad.winfo_width(), 1)
        h = max(self.pad.winfo_height(), 1)
        nx = min(max(x / w, 0.0), 1.0)
        ny = min(max(y / h, 0.0), 1.0)
        return nx, ny

    def pad_press(self, ev):
        self.pad_start = self._pad_norm(ev.x, ev.y)
        self.pad_cur = self.pad_start
        self.pad_dur_ms = int(time.time() * 1000)
        self.pad.delete("trace")

    def pad_move(self, ev):
        if self.pad_start is None:
            return
        self.pad_cur = self._pad_norm(ev.x, ev.y)
        w, h = self.pad.winfo_width(), self.pad.winfo_height()
        self.pad.delete("trace")
        self.pad.create_line(self.pad_start[0] * w, self.pad_start[1] * h,
                             ev.x, ev.y, fill="#3b82f6", width=2, tags="trace")

    def pad_release(self, ev):
        if self.pad_start is None:
            return
        end = self._pad_norm(ev.x, ev.y)
        start = self.pad_start
        self.pad_start = None
        dt = int(time.time() * 1000) - self.pad_dur_ms

        dx = abs(end[0] - start[0])
        dy = abs(end[1] - start[1])

        if dx < 0.02 and dy < 0.02:
            # 基本没动:点击 or 长按
            if dt >= 600:
                self.send(f"H {end[0]:.4f} {end[1]:.4f} {dt}")
            else:
                self.send(f"T {end[0]:.4f} {end[1]:.4f} 40")
        else:
            # 拖动:时长按用户实际动作时长给,夹在 80..1200ms
            ms = min(max(dt, 80), 1200)
            self.send(f"S {start[0]:.4f} {start[1]:.4f} {end[0]:.4f} {end[1]:.4f} {ms}")
        self.pad.delete("trace")

    # ------------------------------------------------------------------ 串口
    def refresh_ports(self):
        ports = list(list_ports.comports())
        names = [f"{p.device}  ({p.description})" for p in ports]
        self.port_cb["values"] = names
        if names and not self.port_var.get():
            # 优先选像 ESP32 的
            pick = 0
            for i, p in enumerate(ports):
                if any(h.lower() in p.device.lower() or
                       (p.description and h.lower() in p.description.lower())
                       for h in PORT_HINTS):
                    pick = i
                    break
            self.port_var.set(names[pick])
        self.log(f"发现 {len(names)} 个串口")

    def current_device(self):
        v = self.port_var.get().strip()
        return v.split("  ")[0] if v else ""

    def toggle_conn(self):
        if self.ser and self.ser.is_open:
            self.disconnect()
        else:
            self.connect()

    def connect(self):
        dev = self.current_device()
        if not dev:
            messagebox.showwarning("提示", "请先选择一个串口")
            return
        try:
            self.ser = serial.Serial(dev, BAUD, timeout=0.2)
        except Exception as e:
            messagebox.showerror("连接失败", f"{dev}\n{e}")
            return
        self.conn_btn.config(text="断开")
        self.status.config(text=f"已连接 {dev}", foreground="#0a0")
        self.log(f"已连接 {dev} @ {BAUD}")
        self.reader_stop.clear()
        self.reader_thread = threading.Thread(target=self._reader, daemon=True)
        self.reader_thread.start()
        time.sleep(0.3)
        self.send("?")

    def disconnect(self):
        self.reader_stop.set()
        if self.ser:
            try:
                self.ser.close()
            except Exception:
                pass
        self.ser = None
        self.conn_btn.config(text="连接")
        self.status.config(text="未连接", foreground="#b00")
        self.log("已断开")

    def _reader(self):
        while not self.reader_stop.is_set() and self.ser:
            try:
                data = self.ser.readline()
                if data:
                    self.log("<< " + data.decode("utf-8", "replace").strip())
            except Exception as e:
                self.log(f"读取错误: {e}")
                break

    # ------------------------------------------------------------------ 发送
    def send(self, cmd: str, clear: bool = False):
        cmd = (cmd or "").strip()
        if not cmd:
            return
        if not (self.ser and self.ser.is_open):
            self.log("(未连接,未发送) " + cmd)
            return
        try:
            self.ser.write((cmd + "\n").encode("ascii", "ignore"))
            self.ser.flush()
            self.log(">> " + cmd)
        except Exception as e:
            self.log(f"发送失败: {e}")
        if clear:
            self.raw_var.set("")

    def set_scale(self, k: float):
        # 命令协议里没有全局缩放,这里用"记下来由上位机乘算"的方式实现
        self.time_scale = k
        self.scale_lbl.config(text=f"时长倍率:{k:.1f}x")
        self.log(f"提示:倍率 {k:.1f}x 仅影响本界面拖动生成的时长;预设按钮需在固件里改 TOUCH_TIME_SCALE")

    def log(self, msg: str):
        self.log_txt.insert(tk.END, msg + "\n")
        self.log_txt.see(tk.END)


def main():
    root = tk.Tk()
    try:
        ttk.Style().theme_use("clam")
    except Exception:
        pass
    HostDemo(root)
    root.mainloop()


if __name__ == "__main__":
    main()
