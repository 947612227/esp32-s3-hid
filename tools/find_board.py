#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
找板子 —— 触摸注入板在哪。

三种手段,依次尝试:
  ① 问服务器 —— 板子 2 分钟一次心跳,服务器记着它最后报的 IP(最省事)
  ② UDP 广播 —— 和板子同网段时最准
  ③ HTTP 确认 —— 拿到候选 IP 后确认它真的是板子

⚠️ UDP 广播**跨不了网段**。电脑和板子不在同一段时(电脑接有线、
   板子接另一个路由器),只能靠服务器那条路。
"""
import json
import socket
import sys
import time
import urllib.request
import urllib.parse
import http.cookiejar

SERVER = "http://120.55.183.206"
USER, PASS = "admin", "7f373c21c1cf"
DISCOVER_PORT = 45678


def op(url, timeout=6, data=None, opener=None):
    req = urllib.request.Request(url, data=data)
    if data is not None:
        req.add_header("Content-Type", "application/x-www-form-urlencoded")
    return (opener.open(req, timeout=timeout) if opener
            else urllib.request.urlopen(req, timeout=timeout))


def ask_server():
    print("════ ① 问服务器 ════")
    try:
        cj = http.cookiejar.CookieJar()
        op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
        body = urllib.parse.urlencode({"username": USER, "password": PASS}).encode()
        op.open(urllib.request.Request(SERVER + "/admin/login", data=body), timeout=8)
        raw = op.open(SERVER + "/api/admin/boards", timeout=8).read()
        boards = json.loads(raw.decode("utf-8")).get("boards", [])
    except Exception as e:
        print("  服务器没答上来:", type(e).__name__, e)
        return []
    if not boards:
        print("  (服务器上还没有板子 —— 说明还没上报过心跳)")
        return []
    out = []
    for b in boards:
        ip = b.get("ip") or ""
        ls = b.get("lastSeen") or 0
        ago = "从没" if not ls else "%.0f 秒前" % ((time.time() * 1000 - ls) / 1000)
        g = b.get("geo") or {}
        print("  %s  %-15s %s  fw=%s" % ("🟢 在线" if b.get("online") else "🔴 离线",
                                          ip or "?", b.get("mac"), b.get("fw")))
        print("        最后心跳 %s · 公网 %s · %s" % (ago, b.get("pubIp") or "?",
                                                     g.get("city", "")))
        if not ip:
            continue
        try:
            st = json.loads(urllib.request.urlopen(
                "http://%s/api/status" % ip, timeout=3).read().decode("utf-8"))
            print("        ✅ 板子在 %s,流程 %s 个,HID %s" % (
                ip, st.get("scriptCount"), "就绪" if st.get("hid") else "未连接"))
            print("        → http://%s/" % ip)
            out.append(ip)
        except Exception as e:
            print("        ⚠️ 服务器记的 IP 连不上(%s)—— IP 可能变了" % type(e).__name__)
    return out


def udp_scan():
    print()
    print("════ ② UDP 广播(需要和板子同网段)════")
    try:
        self_ips = set(socket.gethostbyname_ex(socket.gethostname())[2])
    except Exception:
        self_ips = set()
    self_ips |= {"127.0.0.1"}

    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        s.bind(("", DISCOVER_PORT))
    except OSError:
        pass
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    s.settimeout(1.2)
    try:
        s.sendto(b"TOUCHPAD_DISCOVER", ("255.255.255.255", DISCOVER_PORT))
    except Exception as e:
        print("  发送失败:", e)
        return []
    found = {}
    t0 = time.time()
    while time.time() - t0 < 4:
        try:
            d, a = s.recvfrom(256)
        except socket.timeout:
            break
        txt = d.decode("utf-8", "ignore").strip()
        # ⚠️ 会收到**自己发的广播回声** —— 必须排除,否则误报"找到了"
        if a[0] in self_ips or "TOUCHPAD_HERE" not in txt:
            continue
        found[a[0]] = txt
    if not found:
        print("  (没收到 —— 多半是电脑和板子不在同一网段,看第 ① 步)")
        return []
    ips = []
    for ip, msg in found.items():
        print("  ✅ %s  %s" % (ip, msg))
        ips.append(ip)
    return ips


def main():
    ips = ask_server()
    ips += [i for i in udp_scan() if i not in ips]
    print()
    if ips:
        print("════ 结论 ════")
        for ip in ips:
            print("  http://%s/" % ip)
    else:
        print("════ 没找到板子 ════")
        print("  检查:① 板子通电了吗 ② 有没有连上 WiFi")
        print("  兜底:板子连不上 WiFi 时会自己开热点")
        print("        SSID  ESP32-Touch-xxxx")
        print("        密码  12345678")
        print("        连上去访问 http://192.168.4.1/ 配网")
    return 0


if __name__ == "__main__":
    sys.exit(main())
