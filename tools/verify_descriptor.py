#!/usr/bin/env python3
"""
校验 HID 描述符是否合法。

为什么需要:描述符里 Collection(0xA1)与 End Collection(0xC0)不配对时
编译完全正常,但 Android 的 HID 层会拒绝连接,表现为
「蓝牙已连接但内核里没有设备 / HID connection state=0」,极难定位。

用法:python3 tools/verify_descriptor.py
"""
import re
import sys
import os

HDR = os.path.join(os.path.dirname(__file__), '..', 'src', 'common', 'hid_report_map.h')

# 与 hid_report_map.h 中的默认值保持一致
DEFAULTS = {'HID_INCLUDE_KEYBOARD': '0', 'HID_INCLUDE_CONSUMER': '0', 'HID_INCLUDE_MOUSE': '0'}


def load_descriptor():
    src = open(HDR, encoding='utf-8').read()
    i = src.index('static const uint8_t HID_REPORT_MAP[] = {')
    j = src.index('};', i)
    body = src[i:j]

    active, stack = [], []
    for line in body.splitlines():
        st = line.strip()
        m = re.match(r'#if\s+(\w+)', st)
        if m:
            stack.append(DEFAULTS.get(m.group(1), '1') == '1')
            continue
        if st.startswith('#endif'):
            if stack:
                stack.pop()
            continue
        if stack and not all(stack):
            continue
        if st.startswith('//'):
            continue
        code = st.split('//')[0]
        for h in re.findall(r'0x([0-9A-Fa-f]{2})', code):
            active.append(int(h, 16))
    return active


def main():
    b = load_descriptor()
    print(f"描述符长度: {len(b)} 字节")

    depth = opens = closes = 0
    for v in b:
        if v == 0xA1:
            opens += 1
            depth += 1
        elif v == 0xC0:
            closes += 1
            depth -= 1
    print(f"Collection: 开(0xA1)={opens}  闭(0xC0)={closes}  深度={depth}")

    ok = depth == 0 and opens > 0
    if not ok:
        print("❌ Collection 不配对 —— Android 会拒绝连接,请检查 #if/#endif 的位置")
        return 1

    print("✅ 配对正确")
    if b[:4] != [0x05, 0x0D, 0x09, 0x04] or b[4] != 0xA1:
        print("⚠ 触摸屏集合开头不符合预期(应为 05 0D 09 04 A1 01)")
        return 1
    print("✅ 触摸屏集合开头正确")
    print(f"✅ 结尾: {' '.join(f'{x:02X}' for x in b[-3:])}")
    return 0


if __name__ == '__main__':
    sys.exit(main())
