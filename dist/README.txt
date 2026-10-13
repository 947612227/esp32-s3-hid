触摸注入板 · 烧录包
================================================================

【这个包里有什么】
  touchpad-ble-full.bin          固件 · 蓝牙版(推荐)
  touchpad-usb-full.bin          固件 · USB 版
  touchpad-usb_touchtest-full.bin 固件 · 自检版(开机自动点四角,验证坐标)
  flash-windows.bat              Windows 一键烧录
  flash-mac-linux.sh             Mac / Linux 一键烧录
  README.txt                     本文件

【硬件要求】必须完全符合,否则烧了也不工作
  芯片   ESP32-S3
  Flash  8MB
  PSRAM  8MB OPI(八线)—— 俗称 N8R8
  → 买的时候搜「ESP32-S3 N8R8 开发板」

【怎么烧】
  Windows:  双击 flash-windows.bat
  Mac/Linux: chmod +x flash-mac-linux.sh && ./flash-mac-linux.sh

  脚本会自动:装 esptool(如果没装)→ 找串口 → 烧录

【烧录前 3 个检查】
  1. 板子插的是「原生 USB」口(通常标 USB / COM),
     不是标 UART / 232 的那个
  2. 用能传数据的数据线(有些线只供电)
  3. 如果找不到串口:按住板子上的 BOOT 键不放 →
     点一下 RST → 松开 BOOT,再重试

【烧完之后】
  1. 等约 10 秒,板子会自动连 WiFi
  2. 手机开蓝牙,搜 ESP32-Touch 并配对
  3. 装 App(触摸注入板.apk),菜单 → App 列表 → 选目标 App
  4. 设置 → 页面识别 → 开启(要无障碍权限)
  5. 流程页 → 🔍 自动查找板子

【连不上 WiFi 怎么办】
  板子会自动开热点兜底:
    SSID  ESP32-Touch-xxxx
    密码  12345678
    连上去访问 http://192.168.4.1/ ,填正确的 WiFi 名和密码,保存

【⚠️ 注意】
  · 原生 USB 口看不到串口日志(USB 被拿去当触摸屏了)。
    要看日志:浏览器打开 http://<板子IP>/ ,或 App 的「日志」页
  · 自检版(usb_touchtest)开机会自动触摸屏幕,
    烧之前先确认手机停在安全界面
  · 想改 WiFi:要自己重新编译(在 secrets.h 里改),或用上面的热点配网

【服务器地址】
  https://<你的服务器>/download/
