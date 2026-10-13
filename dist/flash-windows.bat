@echo off
chcp 65001 >nul
REM ===========================================================================
REM  触摸注入板 · Windows 一键烧录
REM
REM  用法:把手机/板子用 USB 线插到电脑,双击本文件即可。
REM        或者在这个目录打开 cmd,运行:  flash-windows.bat
REM
REM  第一次运行会自动装 esptool(需要电脑有 Python 3)
REM ===========================================================================
setlocal
cd /d "%~dp0"

echo.
echo ================================
echo   触摸注入板 · 烧录
echo ================================
echo.

REM ---- 找固件 ----
set FW=
if exist "touchpad-ble-full.bin" set FW=touchpad-ble-full.bin
if "%1"=="usb"          set FW=touchpad-usb-full.bin
if "%1"=="touchtest"    set FW=touchpad-usb_touchtest-full.bin
if "%FW%"=="" (
  echo [X] 这个目录里找不到固件 bin 文件
  echo     把 touchpad-ble-full.bin 和本脚本放同一个目录
  pause & exit /b 1
)
echo   固件: %FW%
echo.

REM ---- 找 esptool ----
where esptool.py >nul 2>nul
if errorlevel 1 (
  where esptool >nul 2>nul
  if errorlevel 1 (
    echo   没装 esptool,正在安装...
    python -m pip install esptool
    if errorlevel 1 ( echo [X] 安装失败,请先装 Python 3: https://www.python.org/downloads/ & pause & exit /b 1 )
  )
)

REM ---- 找串口 ----
echo   正在找串口...
for /f "tokens=1" %%p in ('python -c "import serial.tools.list_ports as l;[print(p.device) for p in l.comports()]" 2^>nul') do set PORT=%%p
if "%PORT%"=="" (
  echo [X] 没找到串口。检查:
  echo      · 板子用**数据线**插到电脑了吗
  echo      · 板子插的是「原生 USB」口,不是 UART 口
  echo      · 按住 BOOT 键,点一下 RST,松开 BOOT,再试
  pause & exit /b 1
)
echo   串口: %PORT%
echo.

echo   开始烧录(约 30 秒)...
esptool.py --chip esp32s3 --port %PORT% --baud 921600 ^
  write_flash -z --flash_mode keep --flash_freq keep --flash_size 8MB ^
  0x0 %FW%

if errorlevel 1 (
  echo.
  echo [X] 烧录失败。常见原因:
  echo      · 没进下载模式 —— 按住 BOOT 再点 RST
  echo      · 数据线不行 / 用了 USB hub
  echo      · 串口被别的软件占用(关掉串口助手)
  pause & exit /b 1
)

echo.
echo ================================
echo   [OK] 烧录完成
echo ================================
echo.
echo   接下来:
echo    1. 等 10 秒,板子会连 WiFi
echo    2. 手机上蓝牙搜 ESP32-Touch 并配对
echo    3. App 里「自动查找板子」
echo.
echo   连不上 WiFi? 板子会自己开热点:
echo     SSID: ESP32-Touch-xxxx    密码: 12345678
echo     连上去访问 http://192.168.4.1/ 配网
echo.
pause
