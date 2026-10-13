package com.dsh.touchpad;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.InputDevice;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import java.util.HashMap;

/**
 * 「连接方式」检测 —— 蓝牙 / USB 直插 / WiFi。
 *
 * ══════════════════════════════════════════════════════════════════════
 *  为什么需要区分**两条链路**
 *
 *  这个系统里有两条**互相独立**的连接:
 *
 *    ① HID 链路(注入触摸)  ── 板子 → 手机
 *         · BLE HID(蓝牙)   刷 ble 固件
 *         · USB HID(直插)   刷 usb 固件
 *         这是**系统级输入设备**,和 App 无关,配对/插线由系统管。
 *
 *    ② 控制链路(App 读写板子) ── App ↔ 板子
 *         以前**只有 WiFi HTTP**,所以"板子有没有网"决定了 App 能不能用。
 *
 *  所以「选了蓝牙」并不等于"App 和板子走蓝牙" ——
 *  蓝牙原来只管注入触摸。这块要等 BLE 控制通道做好才统一。
 * ══════════════════════════════════════════════════════════════════════
 */
public class Link {

    /** ESP32-S3 原生 USB 的厂商 ID(Espressif) */
    private static final int VID_ESPRESSIF = 0x303a;

    public static class State {
        /** 蓝牙 HID 已连 —— 板子的触摸屏出现在系统的输入设备列表里 */
        public boolean bleHid;
        /** USB HID 已插(直插) */
        public boolean usbHid;
        /** WiFi 可达 */
        public boolean wifi;

        /** 输入设备名(用于展示和排错) */
        public String hidName = "";
        /** WiFi 可达时的地址 */
        public String host = "";
        /** 探测耗时 */
        public long costMs;

        public boolean hidReady() { return bleHid || usbHid; }

        /** 一行摘要,给界面用 */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            if (usbHid)      sb.append("🔌 USB 直插");
            else if (bleHid) sb.append("🔵 蓝牙 HID");
            else             sb.append("⚪ HID 未连接");

            sb.append("  ·  ");
            if (wifi) sb.append("📶 WiFi 可达 ").append(host);
            else      sb.append("📵 WiFi 不可达");
            return sb.toString();
        }

        /** 上报服务器用的短标识 */
        public String mode() {
            if (usbHid && wifi) return "usb+wifi";
            if (bleHid && wifi) return "ble+wifi";
            if (usbHid)         return "usb";
            if (bleHid)         return "ble";
            if (wifi)           return "wifi";
            return "none";
        }
    }

    /**
     * 检测 HID 链路(蓝牙 / USB)。
     *
     * ⚠️ **为什么查 InputDevice 而不是 BluetoothAdapter**:
     *    板子是以"触摸屏"的身份接入的 —— 只要它真的连上了,
     *    系统输入设备列表里就会出现它。
     *    这比查蓝牙配对状态准得多:配对过 ≠ 现在连着。
     *
     * @param boardNameHint 板子名字前缀(默认 ESP32-Touch),用来认人
     */
    public static void detectHid(Context ctx, State st) {
        String hint = boardNameHint(ctx);

        // ---- ① USB:找 Espressif 的 USB 设备 ----
        try {
            UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
            if (um != null) {
                HashMap<String, UsbDevice> map = um.getDeviceList();
                for (UsbDevice d : map.values()) {
                    if (d.getVendorId() == VID_ESPRESSIF) {
                        st.usbHid = true;
                        st.hidName = d.getProductName() == null ? "ESP32-S3" : d.getProductName();
                    }
                }
            }
        } catch (Throwable ignored) {}

        // ---- ② 输入设备:找我们的触摸屏 ----
        try {
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice d = InputDevice.getDevice(id);
                if (d == null) continue;
                String nm = d.getName();
                if (nm == null || nm.isEmpty()) continue;
                String low = nm.toLowerCase();
                boolean mine = low.contains("esp32") || low.contains("touchpad")
                        || (!hint.isEmpty() && nm.contains(hint));
                if (!mine) continue;
                st.hidName = nm;
                // 已经确认是 USB 的就不重复算成蓝牙
                if (!st.usbHid) st.bleHid = true;
            }
        } catch (Throwable ignored) {}

        // USB 设备在、但输入设备里没看到 → 只插了 USB 线(可能是串口口),HID 还没起来
        // 这时 usbHid 保持 true 也无妨:用户能看到"USB 直插"这个状态
    }

    /** 固件里写死的 BLE 名(见 src/ble/hid_transport_ble.cpp) */
    private static final String BLE_NAME = "ESP32S3 Touch HID";

    /**
     * 从**蓝牙**推板子的 WiFi MAC。
     *
     * ⚠️ 为什么需要:App 第一次用、又读不到板子(WiFi 不通)时,
     *    本地没有缓存 MAC,连接方式就报不上去(实测踩过)。
     *    但蓝牙这时**是连着的** —— 从系统里能拿到板子的蓝牙 MAC。
     *
     * ESP32 约定:**WiFi MAC 和 BT MAC 相邻**(通常 WiFi = BT − 1)。
     * 实测这块板:WiFi 28:84:85:89:78:C8,BT …78:C9 ✅ 正好差 1。
     *
     * 拿到之后只当"临时标识"用 —— 等真读到板子,会被真实 MAC 覆盖。
     *
     * @return 形如 "28:84:85:89:78:c8";拿不到返回 ""
     */
    public static String guessBoardMacFromBle(Context ctx) {
        try {
            android.bluetooth.BluetoothManager bm =
                    (android.bluetooth.BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            if (bm == null || bm.getAdapter() == null) return "";
            for (android.bluetooth.BluetoothDevice d : bm.getAdapter().getBondedDevices()) {
                String nm = d.getName();
                if (nm == null) continue;
                if (!nm.contains(BLE_NAME) && !nm.toLowerCase().contains("esp32")) continue;
                String addr = d.getAddress();          // 已配对的设备能拿到真实地址
                if (addr == null || addr.length() != 17) continue;
                return minusOne(addr);
            }
        } catch (Throwable ignored) {}
        return "";
    }

    /** 把 MAC 的最后一字节减 1(BT MAC → WiFi MAC) */
    private static String minusOne(String mac) {
        String[] p = mac.split(":");
        if (p.length != 6) return "";
        try {
            int last = Integer.parseInt(p[5], 16);
            last = (last - 1 + 256) & 0xFF;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5; i++) { sb.append(p[i].toLowerCase()).append(':'); }
            sb.append(String.format("%02x", last));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 板子名字前缀(用户在设置里可改;默认 ESP32-Touch) */
    public static String boardNameHint(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences("board", Context.MODE_PRIVATE);
            String h = sp.getString("boardNameHint", "");
            return h == null || h.isEmpty() ? "ESP32-Touch" : h;
        } catch (Throwable t) {
            return "ESP32-Touch";
        }
    }

    /**
     * 整个检测 = HID 检测 + WiFi 探测。
     *
     * ⚠️ **必须在后台线程调** —— WiFi 探测最长会等 timeoutMs。
     *
     * @param lastHost  上次成功的地址(优先探这个,最快)
     * @param timeoutMs WiFi 探测超时;传 0 就只做 HID 检测(不联网)
     */
    public static State detect(Context ctx, String lastHost, int timeoutMs) {
        long t0 = System.currentTimeMillis();
        State st = new State();
        detectHid(ctx, st);

        if (timeoutMs > 0 && lastHost != null && !lastHost.isEmpty()) {
            st.wifi = probe(ctx, lastHost, timeoutMs);
            if (st.wifi) st.host = lastHost;
        }
        st.costMs = System.currentTimeMillis() - t0;
        return st;
    }

    /**
     * 探一个地址上有没有板子。
     *
     * 用 /api/status —— 它回 JSON 且很轻。**不用**再走一遍 UDP 广播:
     * 广播虽然也能找到,但要等超时,而且跨网段就出不去(实测过)。
     */
    public static boolean probe(Context ctx, String host, int timeoutMs) {
        try {
            java.net.URL u = new java.net.URL("http://" + host + "/api/status");
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("Connection", "close");
            int code = c.getResponseCode();
            java.io.InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (is != null) {
                byte[] buf = new byte[512];
                int n = is.read(buf);
                is.close();
                if (n > 0) {
                    String s = new String(buf, 0, n, "UTF-8");
                    // 认一下是不是板子(避免把别的 HTTP 服务当成板子)
                    return s.contains("\"hid\"") || s.contains("\"scriptCount\"")
                            || s.contains("\"fw\"");
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }
}
