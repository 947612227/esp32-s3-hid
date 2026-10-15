package com.dsh.touchpad;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.UUID;

/**
 * BLE **控制通道**客户端 —— 板子没网时,App 靠它读写板子。
 *
 * ══════════════════════════════════════════════════════════════════════
 *  和 HID 的区别(很重要)
 *
 *    HID    : 板子 → 手机(注入触摸),系统级输入设备,配对就行
 *    控制    : App ↔ 板子(读写状态/流程),**需要我们自己做 GATT 客户端**
 *
 *  这两条是**独立**的:蓝牙 HID 连着 ≠ 控制通道能用。
 * ══════════════════════════════════════════════════════════════════════
 *
 *  固件那边的服务(见 src/ble/hid_transport_ble.cpp):
 *      服务 A5C1  ·  RX A5C2(App 写)  ·  TX A5C3(板子通知)
 *      帧格式:[4 字节大端总长][数据...]
 */
public class Ble {

    // ---- 固件里定义的 UUID(见 src/ble/hid_transport_ble.cpp)----
    public static final UUID SVC_CTRL = shortUuid("A5C1");
    public static final UUID CH_RX    = shortUuid("A5C2");
    public static final UUID CH_TX    = shortUuid("A5C3");
    /** 通知开关(标准 CCCD) */
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /**
     * 老固件(< 1.5.0)的 BLE 名。1.5.0 起蓝牙名是 ESP32-S3_XXXX(MAC 后两字节),
     * 两种都含 "esp32",下面按「含 esp32」认。
     */
    private static final String BLE_NAME = "ESP32S3 Touch HID";

    /**
     * 每片的帧头 = 4 字节总长 + 4 字节本片偏移。
     *
     * ⚠️⚠️ 每片都带 —— 以前只在第一片带,结果丢一片就永久错位,
     *      后面的数据被当成帧头,读出天量长度 → OutOfMemoryError 崩 App。
     */
    private static final int HDR = 8;
    private static final int CHUNK_FALLBACK = 180;
    /** 单条响应的长度上限(防御非法帧头导致 OOM) */
    private static final int MAX_RESP = 1 << 20;      // 1 MB

    /**
     * 队列长度上限。
     *
     * ⚠️ 原来写死 8 —— **太小**。启动瞬间 linkTick + 轮询 + 中继会同时发请求,
     *    一下就满了,然后全部被丢,连一条都成功不了(实测:
     *    "队列过长,丢弃 STATUS" 刷屏,中继永远拿不到状态)。
     */
    private static final int QUEUE_MAX = 24;

    /** 16 位 UUID 展开成完整形式 */
    private static UUID shortUuid(String hex) {
        return UUID.fromString("0000" + hex.toLowerCase() + "-0000-1000-8000-00805f9b34fb");
    }

    // ================================================================ 单例

    private static Ble sInstance;

    public static synchronized Ble get() {
        if (sInstance == null) sInstance = new Ble();
        return sInstance;
    }

    // ================================================================ 状态

    /** 连接 + 服务发现 + 订阅都完成了才算"能用" */
    public volatile boolean ready = false;

    /**
     * 正在连接中。
     *
     * ⚠️⚠️ 必须有这个标志 —— 没有的话,每个进来的请求发现"还没就绪"
     *      就各自发起一次 connect(),几十个连接尝试互相打架,
     *      GATT 永远稳不下来(实测:logcat 里日志每秒刷十几次,列表一直超时)。
     *      正确做法:正在连就等着,别重复发起。
     */
    private volatile boolean connecting = false;
    /** 连接过程中攒下的回调,连完一起通知 */
    private final java.util.ArrayList<Cb> waiters = new java.util.ArrayList<Cb>();
    /** 连接过程中的一句话状态(给界面显示 / 排错) */
    public volatile String stateText = "未连接";
    /** 板子不支持控制通道(老固件 / 别的设备) */
    public volatile boolean unsupported = false;

    private Context appCtx;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rx, tx;
    private int mtu = 23;

    private final Handler ui = new Handler(Looper.getMainLooper());

    /**
     * 收包缓冲。
     *
     * ⚠️ 必须用 **字节数组** 收,不能逐字节 append 到 String ——
     *    那样每个字节变成一个 char,中文(UTF-8 三字节)会被拆成乱码。
     *    收齐之后再整体 new String(bytes, "UTF-8") 才是对的。
     */
    private byte[] rxData = null;        // 按偏移落位
    private int    rxWant = 0;           // 本帧总长
    private int    rxGot  = 0;           // 已收到多少字节

    /** 排队等发送的请求(一次只发一条 —— 板子是单线程的) */
    private final ArrayDeque<Req> queue = new ArrayDeque<Req>();
    private Req inflight;
    private volatile long inflightAt = 0;      // in-flight 是什么时候开始的
    private boolean sending = false;

    public interface Cb {
        /** @param body 响应正文;null 表示失败 */
        void done(String body);
    }

    private static class Req {
        String cmd;
        Cb cb;
        int timeoutMs;
        Runnable timeout;
        Req(String c, Cb b, int t) { cmd = c; cb = b; timeoutMs = t; }
    }

    // ================================================================ 连接

    /**
     * 连接控制通道。已经就绪时直接回调。
     *
     * ⚠️ 需要在**已配对**的前提下用 —— 配对是用户在系统设置里做的
     *    (HID 那边必须配对才能注入触摸),我们这里只做 GATT 连接。
     *    如果没配对就先扫描,扫到再连。
     */
    public synchronized void connect(final Context ctx, final Cb onDone) {
        appCtx = ctx.getApplicationContext();
        if (ready) { if (onDone != null) onDone.done("ok"); return; }
        if (onDone != null) waiters.add(onDone);
        // ⚠️ 已经在连了就只挂个回调,别再发起一次 —— 见 connecting 的注释
        if (connecting) return;
        connecting = true;

        final BluetoothManager bm =
                (BluetoothManager) appCtx.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null || bm.getAdapter() == null) {
            stateText = "蓝牙不可用";
            finishConnect(false);
            return;
        }

        // ① 先在已配对里找 —— 正常情况下 HID 已经配好了
        //    ⚠️ 配过好几块板子时,优先连**上次用的那块**(状态里读到的名字 ESP32-S3_XXXX);
        //       以前所有板子同名,只能拿配对列表里的第一个,可能连错板子
        BluetoothDevice found = null;
        try {
            String last = appCtx.getSharedPreferences("board", Context.MODE_PRIVATE)
                    .getString("boardName", "");
            for (BluetoothDevice d : bm.getAdapter().getBondedDevices()) {
                String nm = d.getName();
                if (nm == null || !(nm.contains(BLE_NAME) || nm.toLowerCase().contains("esp32"))) continue;
                if (!last.isEmpty() && nm.equals(last)) { found = d; break; }
                if (found == null) found = d;
            }
        } catch (Throwable t) {
            android.util.Log.w("TouchBle", "读配对列表失败", t);
            stateText = "读配对列表失败:" + t.getMessage();
        }

        if (found != null) {
            android.util.Log.i("TouchBle", "命中已配对:" + found.getName());
            stateText = "找到已配对设备,连接中…";
            connectGatt(found, onDone);
            return;
        }

        // ② 没配对 → 扫一下(板子广播里的名字就是 BLE_NAME)
        stateText = "扫描板子…";
        startScan(bm, onDone);
    }

    private void startScan(final BluetoothManager bm, final Cb onDone) {
        try {
            final BluetoothLeScanner sc = bm.getAdapter().getBluetoothLeScanner();
            if (sc == null) {
                stateText = "扫描不可用";
                finishConnect(false);
                return;
            }
            final boolean[] hit = {false};
            final ScanCallback cb = new ScanCallback() {
                @Override public void onScanResult(int type, ScanResult r) {
                    if (hit[0] || r == null || r.getDevice() == null) return;
                    String nm = null;
                    try { nm = r.getDevice().getName(); } catch (Throwable ignored) {}
                    if (nm != null && (nm.contains(BLE_NAME) || nm.toLowerCase().contains("esp32"))) {
                        hit[0] = true;
                        try { sc.stopScan(this); } catch (Throwable ignored) {}
                        stateText = "扫到板子,连接中…";
                        connectGatt(r.getDevice(), onDone);
                    }
                }
            };
            sc.startScan(cb);
            ui.postDelayed(new Runnable() {
                @Override public void run() {
                    if (hit[0]) return;
                    try { sc.stopScan(cb); } catch (Throwable ignored) {}
                    stateText = "没扫到板子(先在系统蓝牙里配对 ESP32-S3_XXXX,老固件叫 ESP32S3 Touch HID)";
                    finishConnect(false);
                }
            }, 8000);
        } catch (Throwable t) {
            android.util.Log.w("TouchBle", "扫描失败", t);
            stateText = "扫描失败:" + t.getMessage();
            finishConnect(false);
        }
    }

    private void connectGatt(BluetoothDevice dev, final Cb onDone) {
        try {
            if (gatt != null) { try { gatt.close(); } catch (Throwable ignored) {} gatt = null; }
            if (Build.VERSION.SDK_INT >= 23) {
                gatt = dev.connectGatt(appCtx, false, gattCb, BluetoothDevice.TRANSPORT_LE);
            } else {
                gatt = dev.connectGatt(appCtx, false, gattCb);
            }
        } catch (Throwable t) {
            stateText = "连接失败:" + t.getMessage();
            finishConnect(false);
        }
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                android.util.Log.i("TouchBle", "GATT 已连,发现服务…");
                stateText = "已连接,发现服务…";
                try { g.requestMtu(517); } catch (Throwable ignored) {}
                try { g.discoverServices(); } catch (Throwable ignored) {}
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ready = false;
                stateText = "已断开";
                rx = tx = null;
                rxWant = 0; rxGot = 0; rxData = null;   // 半截的包丢掉
                failQueue("连接断开");
                failInflight("蓝牙连接断开");
                finishConnect(false);                   // 连接超时(status=147)也走这里,等着的回调要有结果
            }
        }

        @Override public void onMtuChanged(BluetoothGatt g, int m, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = m;
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                stateText = "服务发现失败(status=" + status + ")";
                finishConnect(false);
                return;
            }
            BluetoothGattService svc = g.getService(SVC_CTRL);
            if (svc == null) {
                unsupported = true;
                stateText = "板子固件不支持蓝牙控制(没有 A5C1 服务)";
                finishConnect(false);
                return;
            }
            rx = svc.getCharacteristic(CH_RX);
            tx = svc.getCharacteristic(CH_TX);
            if (rx == null || tx == null) {
                unsupported = true;
                stateText = "控制通道特征缺失";
                finishConnect(false);
                return;
            }
            // 订阅通知
            try {
                g.setCharacteristicNotification(tx, true);
                BluetoothGattDescriptor d = tx.getDescriptor(CCCD);
                if (d != null) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    } else {
                        d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        g.writeDescriptor(d);
                    }
                } else {
                    // 没有 CCCD 也先认为能用,靠 onDescriptorWrite 兜底
                    ready = true;
                    stateText = "就绪(无 CCCD)";
                    finishConnect(true);
                }
            } catch (Throwable t) {
                stateText = "订阅失败:" + t.getMessage();
                finishConnect(false);
            }
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (d != null && CCCD.equals(d.getUuid())) {
                ready = (status == BluetoothGatt.GATT_SUCCESS);
                if (ready) startWatchdog();
                stateText = ready ? "就绪(蓝牙,MTU=" + mtu + ")" : "订阅失败(status=" + status + ")";
                android.util.Log.i("TouchBle", "订阅结果 ready=" + ready + " mtu=" + mtu);
                finishConnect(ready);
            }
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            if (v != null) onChunk(v);
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failInflight("写入失败(status=" + status + ")");
                return;
            }
            sendNext();      // 上一片发完了,继续下一片
        }
    };

    /**
     * **看门狗** —— 每秒检查一次,把卡死的 in-flight 强行踢掉。
     *
     * ⚠️⚠️ 为什么不能只靠每条请求自己的超时:
     *     实测出现过 in-flight 卡死、连 `postDelayed` 的超时都没生效,
     *     结果队列涨到 24 条、流程列表永远"读取中…"。
     *     一个**独立的、周期性的**看门狗比"每条自己管自己"可靠得多 ——
     *     它保证"任何请求最多卡 N 秒",绝不会悬挂。
     */
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            try {
                Req r = inflight;
                if (r != null && inflightAt > 0
                        && System.currentTimeMillis() - inflightAt > 12000) {
                    android.util.Log.w("TouchBle", "看门狗:in-flight 卡了 12 秒,强制失败 "
                            + r.cmd.substring(0, Math.min(12, r.cmd.length())));
                    failInflight("看门狗");
                }
                // 队列太长 ⇒ 丢最老的。
                // 上限从 8 提到 24:8 太紧,启动瞬间(linkTick + 轮询 + 中继)
                // 一下就满了,结果全被丢(实测踩过)。
                // 优先丢 PAGE(可丢弃),PAGE 丢完再丢别的。
                while (queue.size() > QUEUE_MAX) {
                    Req old = null;
                    java.util.Iterator<Req> it = queue.iterator();
                    while (it.hasNext()) {
                        Req c = it.next();
                        if (c.cmd.startsWith("PAGE ")) { it.remove(); old = c; break; }
                    }
                    if (old == null) old = queue.pollLast();
                    if (old == null) break;
                    final Req d = old;
                    if (d.timeout != null) ui.removeCallbacks(d.timeout);
                    try { d.cb.done(null); } catch (Throwable ignored) {}
                    android.util.Log.w("TouchBle", "队列过长,丢弃 " + d.cmd);
                }
            } catch (Throwable ignored) {}
            ui.postDelayed(this, 1000);
        }
    };

    /** 启动看门狗(连上后调一次) */
    private void startWatchdog() { ui.removeCallbacks(watchdog); ui.post(watchdog); }

    /**
     * 一次连接尝试结束(成功或任何一种失败)都必须走这里:
     * 清掉 connecting,并通知 connect() 里登记的全部回调(每个只通知一次)。
     * ⚠️ 漏掉 connecting=false 的话,之后所有 connect() 都会直接返回,再也连不上。
     */
    private void finishConnect(boolean ok) {
        connecting = false;
        // 连接期间攒下的回调一起通知
        java.util.ArrayList<Cb> ws;
        synchronized (this) {
            ws = new java.util.ArrayList<Cb>(waiters);
            waiters.clear();
        }
        for (Cb w : ws) {
            try { w.done(ok ? "ok" : null); } catch (Throwable ignored) {}
        }
    }

    public void disconnect() {
        ready = false;
        connecting = false;
        failQueue("主动断开");
        try { if (gatt != null) { gatt.disconnect(); gatt.close(); } } catch (Throwable ignored) {}
        gatt = null; rx = tx = null;
        stateText = "未连接";
    }

    // ================================================================ 收包

    private void onChunk(byte[] v) {
        if (v.length < HDR) return;
        long len = ((long)(v[0] & 0xFF) << 24) | ((long)(v[1] & 0xFF) << 16)
                 | ((long)(v[2] & 0xFF) << 8) | (long)(v[3] & 0xFF);
        long off = ((long)(v[4] & 0xFF) << 24) | ((long)(v[5] & 0xFF) << 16)
                 | ((long)(v[6] & 0xFF) << 8) | (long)(v[7] & 0xFF);

        // ⚠️ 合法性校验 —— 没有它,一个错位就能让 App OOM 崩掉(实测)
        if (len <= 0 || len > MAX_RESP || off < 0 || off >= len
                || HDR + (len - off) < v.length) {
            android.util.Log.w("TouchBle", "非法片头 len=" + len + " off=" + off + ",丢弃");
            return;
        }

        int n = (int) Math.min(v.length - HDR, len - off);

        // 新的一帧(偏移 0)或长度变了 → 重新开缓冲
        if (rxData == null || rxWant != (int) len || off == 0) {
            if (rxData == null || rxWant != (int) len) {
                rxData = new byte[(int) len];
                rxGot = 0;
                rxWant = (int) len;
            }
        }
        if (rxData == null) return;
        System.arraycopy(v, HDR, rxData, (int) off, n);
        rxGot += n;

        if (rxGot >= rxWant) {
            String body;
            try {
                body = new String(rxData, 0, rxWant, "UTF-8");
            } catch (Throwable t) {
                body = new String(rxData, 0, rxWant);
            }
            rxWant = 0; rxGot = 0; rxData = null;
            deliver(body);
        }
    }

    /** 一次请求的响应收全了 */
    private void deliver(String body) {
        final Req r = inflight;
        inflight = null;
        inflightAt = 0;
        sending = false;
        if (r != null) {
            if (r.timeout != null) ui.removeCallbacks(r.timeout);
            r.cb.done(body);
        }
        pump();
    }

    /**
     * 把**队列里排着**的请求全部失败掉。
     *
     * ⚠️⚠️ 必须有这个 —— 否则:
     *      板子重启/蓝牙断开 ⇒ ready=false ⇒ pump() 直接 return
     *      ⇒ 队列里的请求**既发不出去、也不超时**,
     *        调用方的回调永远不回来,界面就一直卡在"读取中…"
     *        (实测:19 条请求只有 3 条有回调)。
     */
    private void failQueue(String why) {
        java.util.ArrayDeque<Req> rest = new java.util.ArrayDeque<Req>();
        while (!queue.isEmpty()) rest.add(queue.poll());
        for (Req r : rest) {
            if (r.timeout != null) ui.removeCallbacks(r.timeout);
            try { r.cb.done(null); } catch (Throwable ignored) {}
        }
    }

    private void failInflight(String why) {
        final Req r = inflight;
        inflight = null;
        inflightAt = 0;
        sending = false;
        rxWant = 0; rxGot = 0; rxData = null;
        if (r != null) {
            if (r.timeout != null) ui.removeCallbacks(r.timeout);
            r.cb.done(null);
        }
        pump();
    }

    // ================================================================ 发请求

    /**
     * 发一条命令,等响应。
     *
     * ⚠️ **必须在后台线程调** —— 内部会等蓝牙往返(默认 4 秒)。
     *    和 Board 的 HTTP 接口一样是异步回调风格,方便直接替换。
     *
     * @param cmd 形如 "STATUS" / "RUN 2" / "SAVE 1\nT 0.5 0.5"
     */
    public void request(final String cmd, final int timeoutMs, final Cb cb) {
        if (!ready || rx == null) { cb.done(null); return; }
        requestOnce(cmd, timeoutMs, 1, cb);
    }

    /**
     * 带重试的请求。
     *
     * ⚠️ BLE 通知**确实会丢片**(不是 bug,是链路特性)——
     *    丢一片整帧就收不全。所以失败要重试一次,
     *    否则用户看到的就是"偶尔读不出来"。
     */
    private void requestOnce(final String cmd, final int timeoutMs, final int left,
                             final Cb cb) {
        // ⚠️⚠️ **只有"用户主动发起"的命令才重试**。
        //
        //     两类绝不能重试:
        //       · PAGE  —— 每 700ms 一次高频推送,旧页面本来就被新页面取代。
        //                  重试会造成:新 PAGE 合并掉旧 PAGE → 旧的"失败"→ 又入队
        //                  → 队列指数增长(实测踩过)。
        //       · 周期轮询(STATUS/LOG/LIST)—— 本来就每几秒再来一次,
        //                  重试只是**把负载翻倍**,让溢出更严重。
        //                  实测:启动瞬间多个任务同时发请求 → 队列溢出 →
        //                  被丢的又重试 → 雪崩 → 一条都成功不了。
        if (!isUserAction(cmd)) { requestRaw(cmd, timeoutMs, cb); return; }

        requestRaw(cmd, timeoutMs, new Cb() {
            @Override public void done(String body) {
                if (body != null || left <= 0) { cb.done(body); return; }
                android.util.Log.i("TouchBle", "重试(" + left + "):"
                        + cmd.substring(0, Math.min(16, cmd.length())));
                requestOnce(cmd, timeoutMs, left - 1, cb);
            }
        });
    }

    /**
     * 是不是「用户主动点的」命令。
     *
     * 只有这类才值得重试 —— 用户点了一次"运行",失败了该再试一次。
     * 而周期轮询(STATUS/LOG/LIST)失败了无所谓,过几秒自然会再来;
     * 给它们重试只会把负载翻倍,反而更容易溢出(实测踩过)。
     */
    /**
     * 最近一次"用户主动动作"的时刻。
     *
     * ⚠️⚠️ 为什么要有它(实测踩的坑):
     *      用户点「运行」→ RUN 入队 → 但队列前面堵着 20 秒轮询和中继的大响应
     *      → RUN 迟迟发不出去 / 响应回不来 → **超时** → App 重试 → 这才跑起来。
     *      用户侧表现就是「点了运行,等 30 秒才动」,而且因为重试,板子可能跑两遍。
     *
     *      所以:有用户动作时,**周期轮询全部让路**(见 MainActivity 的 netTick)。
     */
    private static volatile long lastUserActionAt = 0;

    /** 用户动作刚刚发生 —— 轮询该暂停一会儿 */
    public static boolean userActionPending() {
        return System.currentTimeMillis() - lastUserActionAt < 12000;
    }

    /** 用户动作开始(调用方在发 RUN/SAVE/DEL/STOP 前调一下) */
    public static void noteUserAction() {
        lastUserActionAt = System.currentTimeMillis();
    }

    private static boolean isUserAction(String cmd) {
        if (cmd.startsWith("RUN ") || cmd.equals("STOP")
                || cmd.startsWith("SAVE ") || cmd.startsWith("DEL ")) {
            noteUserAction();
        }
        return cmd.startsWith("RUN ") || cmd.equals("STOP")
            || cmd.startsWith("SAVE ") || cmd.startsWith("DEL ")
            || cmd.equals("LED") || cmd.startsWith("LED ")
            || cmd.startsWith("GET ");          // 读某个流程内容(用户点开的)
    }

    private void requestRaw(final String cmd, final int timeoutMs, final Cb cb) {
        if (!ready || rx == null) { cb.done(null); return; }
        ui.post(new Runnable() {
            @Override public void run() {
                // ⚠️⚠️ PAGE 是「最新覆盖」语义 —— 必须合并掉排队里的旧 PAGE。
                //    页面识别每 700ms 推一次,不合并的话队列会被 PAGE 塞满,
                //    大响应(比如 982 字节的日志)排在后面活活饿死,
                //    表现就是「/api/logs 失败/超时」(实测踩过)。
                if (cmd.startsWith("PAGE ")) {
                    java.util.Iterator<Req> it = queue.iterator();
                    while (it.hasNext()) {
                        Req old = it.next();
                        if (old.cmd.startsWith("PAGE ")) {
                            it.remove();
                            ui.removeCallbacks(old.timeout);
                            old.cb.done(null);      // 通知旧的那个"被新页面取代"
                        }
                    }
                    // 正在发的那条 PAGE 也让它发完(没法取消),但不再排队
                }

                final Req r = new Req(cmd, cb, Math.max(2500, timeoutMs));
                // 超时挂到**真正开始发**的时候(见 pump);入队时挂会提前超时
                //
                // ⚠️ **优先级**:PAGE 是每 700ms 一次的高频推送,而且丢一两次无所谓。
                //    如果它和有意义的请求(读列表/读日志)排同一条队,
                //    队列会被 PAGE 占满 ⇒ 列表一直"读取中…"(实测踩过)。
                //    所以:PAGE 排到**队尾**,其它请求插到**队首**。
                if (cmd.startsWith("PAGE ") || queue.isEmpty()) {
                    queue.addLast(r);
                } else {
                    queue.addFirst(r);
                }
                android.util.Log.i("TouchBleQ", "入队 " + cmd.substring(0, Math.min(10, cmd.length()))
                        + " 队列=" + queue.size()
                        + " inflight=" + (inflight == null ? "null" : "有"));
                pump();
            }
        });
    }

    /** 从队列取一条,拼成整帧开始发 */
    private void pump() {
        // ⚠️ 不可用时**不能直接把队列晾着** —— 那样调用方的回调永远不回来。
        //    这里顺手清掉,保证"要么成功要么失败",绝不悬挂。
        if (!ready || rx == null) {
            if (!queue.isEmpty()) failQueue("通道不可用");
            return;
        }
        if (sending || inflight != null || queue.isEmpty()) {
            android.util.Log.i("TouchBleQ", "pump 跳过 sending=" + sending
                    + " inflight=" + (inflight == null ? "null" : inflight.cmd.substring(0, Math.min(8, inflight.cmd.length())))
                    + " queued=" + queue.size() + " ready=" + ready);
            return;
        }
        final Req r = queue.poll();
        inflight = r;
        inflightAt = System.currentTimeMillis();
        sending = true;

        // 整帧 = [4 字节大端长度][正文]
        final byte[] body = r.cmd.getBytes();
        txFrame = new byte[HDR + body.length];
        txFrame[0] = (byte) ((body.length >> 24) & 0xFF);
        txFrame[1] = (byte) ((body.length >> 16) & 0xFF);
        txFrame[2] = (byte) ((body.length >> 8) & 0xFF);
        txFrame[3] = (byte) (body.length & 0xFF);
        System.arraycopy(body, 0, txFrame, HDR, body.length);
        txOff = 0;

        // 真正开始发了,这时候才起超时
        r.timeout = new Runnable() {
            @Override public void run() {
                if (inflight == r) failInflight("超时");
            }
        };
        ui.postDelayed(r.timeout, r.timeoutMs);

        sendNext();
    }

    /**
     * 发下一片。
     *
     * ⚠️ 必须**等上一片的 onCharacteristicWrite 回调**再发下一片 ——
     *    BLE 的写是串行的,连续调 writeCharacteristic 后面那些会被丢掉。
     */
    private void sendNext() {
        if (txFrame == null) {
            android.util.Log.w("TouchBleQ", "sendNext: txFrame 为 null!");
            failInflight("txFrame 空");
            return;
        }
        if (txOff >= txFrame.length) return;          // 发完了,等对端通知回来

        int chunk = Math.max(20, Math.min(240, mtu - 3));
        int n = Math.min(chunk, txFrame.length - txOff);
        byte[] part = new byte[n];
        System.arraycopy(txFrame, txOff, part, 0, n);
        txOff += n;
        writeNow(part);
    }

    private void writeNow(byte[] payload) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(rx, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            } else {
                rx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                rx.setValue(payload);
                gatt.writeCharacteristic(rx);
            }
        } catch (Throwable t) {
            failInflight("写异常:" + t.getMessage());
        }
    }

    /** 当前正在发的整帧 + 已发到哪 */
    private byte[] txFrame;
    private int txOff;

    // ================================================================ 便捷方法(和 Board 的接口对应)

    public void status(Cb cb)              { request("STATUS", 4000, cb); }
    public void scripts(Cb cb)             { request("LIST", 4000, cb); }
    public void scriptText(int i, Cb cb)   { request("GET " + i, 5000, cb); }
    public void run(int i, Cb cb)          { request("RUN " + i, 4000, cb); }
    public void stop(Cb cb)                { request("STOP", 4000, cb); }
    public void led(Cb cb)                 { request("LED", 4000, cb); }   // 找板子(灯闪)
    public void logs(Cb cb)                { request("LOG 8", 5000, cb); }   // 只回最后 8 行 ⇒ 单片 ⇒ 稳
    public void clearLogs(Cb cb)          { request("LOGCLR", 4000, cb); }
    public void page(String act, String txt, Cb cb) {
        request("PAGE " + (act == null ? "" : act) + "\n" + (txt == null ? "" : txt), 3000, cb);
    }
    public void save(String name, String text, Cb cb) {
        request("SAVE -1\n" + name + "\n" + (text == null ? "" : text), 8000, cb);
    }
    public void remove(String name, Cb cb) { request("DEL " + name, 4000, cb); }
}
