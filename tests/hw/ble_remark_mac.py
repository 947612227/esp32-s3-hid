#!/usr/bin/env python3
"""
真机验证:蓝牙控制通道读状态 / 改备注(在 Mac 上跑,代替 App 走同一条通道)

  帧格式和 App(Ble.java)一样:
    发:[4 字节大端总长][4 字节 0][正文],按 MTU 切片顺序写 A5C2
    收:A5C3 通知,每片 [4 字节总长][4 字节本片偏移][数据]

用法:
  python3 -m venv /tmp/bleak-venv && /tmp/bleak-venv/bin/pip install bleak   # 依赖装在临时目录,不进仓库
  /tmp/bleak-venv/bin/python tests/hw/ble_remark_mac.py ESP32-S3_098C 蓝牙改的

⚠️ 只按完整蓝牙名找板子;正式板子的名字不要传进来
"""
import asyncio
import json
import struct
import sys

from bleak import BleakClient, BleakScanner

CH_RX = "0000a5c2-0000-1000-8000-00805f9b34fb"   # App 写
CH_TX = "0000a5c3-0000-1000-8000-00805f9b34fb"   # 板子通知


class 控制通道:
    def __init__(self, client):
        self.c = client
        self.buf = None      # 本帧缓冲
        self.want = 0        # 本帧总长
        self.got = 0
        self.done = None     # 等响应的 Future

    def on_notify(self, _h, v: bytearray):
        total, off = struct.unpack(">II", v[:8])
        if self.buf is None or self.want != total or off == 0:
            self.buf, self.want, self.got = bytearray(total), total, 0
        n = min(len(v) - 8, total - off)
        self.buf[off:off + n] = v[8:8 + n]
        self.got += n
        if self.got >= self.want and self.done and not self.done.done():
            self.done.set_result(bytes(self.buf[:self.want]).decode("utf-8", "replace"))
            self.buf = None

    async def cmd(self, text, timeout=8):
        body = text.encode("utf-8")
        frame = struct.pack(">II", len(body), 0) + body
        chunk = max(20, min(240, self.c.mtu_size - 3))
        self.done = asyncio.get_running_loop().create_future()
        for i in range(0, len(frame), chunk):
            await self.c.write_gatt_char(CH_RX, frame[i:i + chunk], response=True)
        return await asyncio.wait_for(self.done, timeout)


async def main(name, remark):
    print(f"1. 扫描蓝牙名 = {name} 的板子…")
    dev = await BleakScanner.find_device_by_name(name, timeout=15)
    if not dev:
        print("❌ 没扫到"); sys.exit(1)
    async with BleakClient(dev) as client:
        ch = 控制通道(client)
        await client.start_notify(CH_TX, ch.on_notify)

        print("2. 读状态(STATUS)")
        st = json.loads(await ch.cmd("STATUS"))
        print(f"   名字={st.get('name')} 备注={st.get('remark')!r} 待同步={st.get('remarkPending')}")
        if st.get("name") != name:
            print("❌ 状态里的名字和蓝牙名不一致"); sys.exit(1)

        print(f"3. 改备注(REMARK {remark})")
        r = json.loads(await ch.cmd("REMARK " + remark))
        print("   回复:", r)
        if not r.get("ok"):
            print("❌ 改备注失败"); sys.exit(1)

        print("4. 不合法的备注(带引号)应被拒")
        bad = json.loads(await ch.cmd('REMARK 带"引号'))
        print("   回复:", bad)
        if bad.get("ok"):
            print("❌ 不合法的备注被收下了"); sys.exit(1)

        print("5. 再读状态核对")
        st2 = json.loads(await ch.cmd("STATUS"))
        print(f"   名字={st2.get('name')} 备注={st2.get('remark')!r} 待同步={st2.get('remarkPending')}")
        if st2.get("remark") != remark or st2.get("remarkPending"):
            print("❌ 备注没写进去"); sys.exit(1)
    print("✅ 蓝牙读状态 / 改备注 / 拒绝不合法备注 都正常")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("用法:ble_remark_mac.py <完整蓝牙名> <新备注>"); sys.exit(2)
    asyncio.run(main(sys.argv[1], sys.argv[2]))
