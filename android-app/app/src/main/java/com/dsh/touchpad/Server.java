package com.dsh.touchpad;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.List;

/**
 * 控制中心客户端(120.55.183.206)。
 *
 * 三件事:
 *   ① 启动时查 APK 更新 —— **15 秒超时,超时就算了**,绝不阻塞界面
 *   ② 上报手机信息(型号/Android 版本/WiFi)
 *   ③ 上报已装 App 列表
 *
 * 设计原则:**一切失败都静默**。
 * 服务器连不上、超时、返回垃圾 —— 对 App 的正常使用没有任何影响。
 * 这本来就是个"锦上添花"的功能,不能因为它把主流程拖死。
 */
public class Server {

    /** 控制中心地址(没有域名,直接用 IP) */
    public static final String BASE = "http://120.55.183.206";

    /** 你要求的 15 秒超时 */
    private static final int TIMEOUT_MS = 15000;

    public interface Cb {
        /** ok=false 时 body 是错误信息 */
        void done(boolean ok, String body);
    }

    private static String token(Context ctx) {
        // token 内置在 App 里(自用 sideload,不做复杂分发)
        return "app_7cdce99161b567c062a0031d4359b0fe";
    }

    // ---------------------------------------------------------------- HTTP

    // ================================================================ 服务器连通状态
    //
    //  ⚠️ 为什么要有这个:
    //      原来所有服务器调用都是 **fire-and-forget** —— 失败就静默吞掉。
    //      用户根本不知道"云端到底通没通",只看到某些功能时好时坏。
    //      现在每次请求都顺手记一笔结果,界面直接显示。
    //
    //  值:0 = 还没试过 / 1 = 通 / -1 = 不通
    public static volatile int    lastState = 0;
    public static volatile long   lastOkAt  = 0;
    public static volatile String lastErr   = "";

    public static void markOk() {
        lastState = 1; lastOkAt = System.currentTimeMillis(); lastErr = "";
    }
    public static void markFail(String why) {
        lastState = -1; lastErr = why == null ? "" : why;
    }

    /** 一行文本,给界面用 */
    public static String stateText() {
        if (lastState == 0) return "检测中…";
        if (lastState == 1) {
            long ago = (System.currentTimeMillis() - lastOkAt) / 1000;
            String t = ago < 60 ? ago + " 秒前"
                     : (ago < 3600 ? (ago / 60) + " 分钟前" : (ago / 3600) + " 小时前");
            return "已连接(" + t + ")";
        }
        return "连不上" + (lastErr.isEmpty() ? "" : ":" + lastErr);
    }

    /**
     * 显式探活一次。
     *
     * 用 `/api/v1/board/health` —— 最轻的接口(只回 {ok:true})✅
     * 拿得到响应就说明网络通 ✅
     */
    public static void ping(Context ctx, Cb cb) {
        req("/api/v1/board/health", "GET", null, cb);
    }

    private static void req(final String path, final String method, final String body,
                            final Cb cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                HttpURLConnection c = null;
                try {
                    URL u = new URL(BASE + path);
                    c = (HttpURLConnection) u.openConnection();
                    c.setConnectTimeout(TIMEOUT_MS);
                    c.setReadTimeout(TIMEOUT_MS);
                    c.setRequestMethod(method);
                    c.setRequestProperty("X-Token", token(null));
                    if (body != null) {
                        c.setDoOutput(true);
                        c.setRequestProperty("Content-Type", "application/json");
                        OutputStream os = c.getOutputStream();
                        os.write(body.getBytes(Charset.forName("UTF-8")));
                        os.close();
                    }
                    int code = c.getResponseCode();
                    InputStream is = (code >= 200 && code < 300) ? c.getInputStream()
                                                                 : c.getErrorStream();
                    String text = "";
                    if (is != null) {
                        BufferedReader r = new BufferedReader(
                                new InputStreamReader(is, "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line);
                        r.close();
                        text = sb.toString();
                    }
                    boolean ok = code >= 200 && code < 300;
                    // ⚠️ 顺手记一笔连通状态 —— 不用额外发请求
                    if (ok) markOk(); else markFail("HTTP " + code);
                    if (cb != null) cb.done(ok, text);
                } catch (Throwable e) {
                    // 连不上/超时 —— 不打扰用户,但**要让界面看得到**
                    markFail(String.valueOf(e.getMessage()));
                    if (cb != null) cb.done(false, String.valueOf(e.getMessage()));
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        }, "server-req").start();
    }

    // ---------------------------------------------------------------- ① 查更新

    /**
     * 查 APK 更新。hasUpdate 时回调里给出 info。
     * 调用方自己决定怎么提示用户(我们做成一个可选的弹窗)。
     */
    public interface UpdateCb {
        void done(boolean hasUpdate, String versionName, int versionCode,
                  String url, String notes, long size);
    }

    public static void checkUpdate(Context ctx, final UpdateCb cb) {
        final int localCode = localVersionCode(ctx);
        req("/api/v1/app/version?code=" + localCode, "GET", null, new Cb() {
            @Override public void done(boolean ok, String body) {
                if (!ok) { if (cb != null) cb.done(false, null, 0, null, null, 0); return; }
                try {
                    JSONObject o = new JSONObject(body);
                    boolean has = o.optBoolean("hasUpdate", false);
                    if (cb != null) cb.done(has,
                            o.optString("versionName"), o.optInt("versionCode"),
                            o.optString("url"), o.optString("notes"),
                            o.optLong("size"));
                } catch (Throwable e) {
                    if (cb != null) cb.done(false, null, 0, null, null, 0);
                }
            }
        });
    }

    private static int localVersionCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionCode;
        } catch (Throwable e) {
            return 0;
        }
    }

    public static String localVersionName(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName;
        } catch (Throwable e) {
            return "";
        }
    }

    // ---------------------------------------------------------------- ② 上报手机

    /** 设备唯一标识(ANDROID_ID;重置系统会变,对我们够用) */
    public static String deviceId(Context ctx) {
        try {
            String s = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ANDROID_ID);
            return s == null ? "unknown" : s;
        } catch (Throwable e) {
            return "unknown";
        }
    }

    public static void reportPhone(final Context ctx) {
        try {
            JSONObject o = new JSONObject();
            o.put("deviceId", deviceId(ctx));
            o.put("name", Build.MODEL);
            o.put("model", Build.MODEL);
            o.put("android", Build.VERSION.RELEASE);
            o.put("appVersion", localVersionName(ctx));

            // WiFi 信息
            try {
                WifiManager wm = (WifiManager)
                        ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
                if (wi != null) {
                    JSONObject w = new JSONObject();
                    String ssid = wi.getSSID();
                    if (ssid != null) ssid = ssid.replace("\"", "");
                    w.put("ssid", ssid == null ? "" : ssid);
                    w.put("rssi", wi.getRssi());
                    o.put("wifi", w);
                }
            } catch (Throwable ignored) {
                // Android 10 读 SSID 可能要定位权限,拿不到就算了
            }

            req("/api/v1/app/phone", "POST", o.toString(), null);
        } catch (Throwable ignored) {}
    }

    // ---------------------------------------------------------------- ③ 上报已装 App

    /**
     * 上报已安装的第三方 App(名称 + 包名)。
     * 只报"有桌面图标"的,过滤掉系统内部包,避免列表又臭又长。
     */
    public static void reportApps(final Context ctx) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    PackageManager pm = ctx.getPackageManager();
                    List<ApplicationInfo> list = pm.getInstalledApplications(0);
                    JSONArray arr = new JSONArray();
                    for (ApplicationInfo ai : list) {
                        // 只要用户能启动的
                        if (pm.getLaunchIntentForPackage(ai.packageName) == null) continue;
                        JSONObject o = new JSONObject();
                        o.put("pkg", ai.packageName);
                        o.put("name", String.valueOf(pm.getApplicationLabel(ai)));
                        o.put("system", (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                        arr.put(o);
                    }
                    JSONObject body = new JSONObject();
                    body.put("deviceId", deviceId(ctx));
                    body.put("apps", arr);
                    req("/api/v1/app/apps", "POST", body.toString(), null);
                } catch (Throwable ignored) {}
            }
        }, "report-apps").start();
    }

    // ---------------------------------------------------------------- ④ 上报定时任务

    /**
     * 把 App 里的定时任务报给服务端。
     *
     * ⚠️ 为什么要报:定时是存在**手机 App 的本地设置**里的,
     *    板子根本不知道 —— 不报的话看板就看不到"下次什么时候执行"。
     *
     * @param boardMac 板子的 WiFi MAC(从 /api/status 拿)
     * @param tasksJson 形如 [{"name":"大麦","mode":"at","fireAt":123,"enabled":true}]
     */
    public static void reportSchedules(Context ctx, String boardMac, String tasksJson) {
        if (boardMac == null || boardMac.isEmpty()) return;
        try {
            JSONObject o = new JSONObject();
            o.put("boardMac", boardMac);
            o.put("deviceId", deviceId(ctx));
            o.put("tasks", new JSONArray(tasksJson));
            req("/api/v1/app/schedule", "POST", o.toString(), null);
        } catch (Throwable ignored) {}
    }

    /**
     * App **代板子**上报。
     *
     * ══════════════════════════════════════════════════════════════════
     *  为什么需要
     *
     *  板子可能**上不了网** —— 典型:公司 WiFi 要飞连/802.1X 认证,
     *  ESP32 做不了,只能开热点。这时板子自己发不了心跳,
     *  云端就看不到在线状态、公网 IP、**流程备份**(误删的保险)。
     *
     *  但**手机是有网的**(连板子热点时 Android 默认保留蜂窝数据)。
     *  所以让 App 把板子的状态中转上去。
     *
     *  ⚠️ 公网 IP 不上报 —— 手机的公网 IP 不等于板子的,
     *     传上去会把手机的位置错记到板子头上。
     * ══════════════════════════════════════════════════════════════════
     *
     * @param statusJson 板子 /api/status 的原始 JSON
     * @param flowNames  流程名数组的 JSON
     * @param flowTexts  流程名 → 内容 的 JSON 对象(可为 null,那就只报名字)
     */
    /**
     * 上报「当前是怎么连的」—— 蓝牙 / USB 直插 / WiFi。
     *
     * 变化时立刻报(不等 30 分钟的 relay),因为用户最关心的就是"通没通"。
     */
    public static void reportLink(Context ctx, String boardMac, String mode, String hid) {
        if (boardMac == null || boardMac.isEmpty()) return;
        try {
            JSONObject o = new JSONObject();
            o.put("boardMac", boardMac);
            o.put("deviceId", deviceId(ctx));
            o.put("mode", mode == null ? "" : mode);
            o.put("hid", hid == null ? "" : hid);
            req("/api/v1/app/link", "POST", o.toString(), null);
        } catch (Throwable ignored) {}
    }

    /** 中继成功后回调(用来处理服务器捎回来的板子命令) */
    public interface RelayCb { void done(String ledJson); }

    public static void relayBoard(Context ctx, String boardMac, String boardName,
                                  String statusJson, String flowNames, String flowTexts) {
        relayBoard(ctx, boardMac, boardName, statusJson, flowNames, flowTexts, null);
    }

    /**
     * 代板子上报,并处理服务器**捎回来的命令**。
     *
     * ⚠️ 为什么需要:板子连不上服务器时收不到心跳响应,
     *    后台存进 settings 的"点灯"命令**没人来取** ——
     *    后台按钮看着成功、板子毫无反应(实测的断层)。
     *    App 和板子有直连,所以由 App 把命令取走再下发。
     */
    public static void relayBoard(Context ctx, String boardMac, String boardName,
                                  String statusJson, String flowNames, String flowTexts,
                                  final RelayCb cb) {
        if (boardMac == null || boardMac.isEmpty()) return;
        try {
            JSONObject o = new JSONObject();
            o.put("boardMac", boardMac);
            if (boardName != null) o.put("boardName", boardName);
            o.put("deviceId", deviceId(ctx));
            o.put("status", new JSONObject(statusJson == null ? "{}" : statusJson));
            if (flowNames != null) o.put("flowNames", new JSONArray(flowNames));
            if (flowTexts != null && !flowTexts.isEmpty()) {
                o.put("flowTexts", new JSONObject(flowTexts));
            }
            req("/api/v1/app/board-relay", "POST", o.toString(), new Cb() {
                @Override public void done(boolean ok, String body) {
                    if (cb == null) return;
                    String led = null;
                    try {
                        JSONObject r = new JSONObject(body == null ? "{}" : body);
                        JSONObject l = r.optJSONObject("led");
                        if (l != null) led = l.toString();
                    } catch (Throwable ignored) {}
                    cb.done(led);
                }
            });
        } catch (Throwable ignored) {}
    }

    // ---------------------------------------------------------------- 启动时统一处理

    /**
     * App 启动时调一次:上报 + 查更新。
     * 全部在后台线程,**不阻塞界面**。
     */
    // ---------------------------------------------------------------- 流程同步
    //
    //  ⚠️ 为什么要有这一套:
    //      原来流程有三处真相(板子正文 / App 状态 / 服务器只读备份),
    //      换手机就丢状态,换板子就丢流程。
    //      现在服务器是**权威**,这里负责"拉下来 / 推上去"。

    /** 拉增量。since=0 表示全量(第一次装或清过数据) */
    public static void fetchFlows(Context ctx, long since, Cb cb) {
        final String d = deviceId(ctx);
        req("/api/v1/app/flows?deviceId=" + android.net.Uri.encode(d) + "&since=" + since,
            "GET", null, cb);
    }

    /** 推一条上去。baseVer=0 表示"我没见过服务器版本"(新建/导入),不报冲突 */
    public static void pushFlow(Context ctx, String name, String text, String metaJson,
                                long baseVer, Cb cb) {
        try {
            JSONObject o = new JSONObject();
            o.put("deviceId", deviceId(ctx));
            o.put("name", name);
            o.put("text", text == null ? "" : text);
            if (metaJson != null) o.put("meta", new JSONObject(metaJson));
            o.put("baseVer", baseVer);
            req("/api/v1/app/flows", "POST", o.toString(), cb);
        } catch (Throwable e) {
            if (cb != null) cb.done(false, String.valueOf(e.getMessage()));
        }
    }

    /** 删一条(服务器是软删除,别的设备能拉到"被删了"这个事实) */
    public static void deleteFlow(Context ctx, String name, Cb cb) {
        final String d = deviceId(ctx);
        req("/api/v1/app/flows/" + android.net.Uri.encode(name) + "?deviceId="
            + android.net.Uri.encode(d), "DELETE", null, cb);
    }

    public static void startupPing(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences("board", Context.MODE_PRIVATE);
        long last = sp.getLong("lastReport", 0);
        // 上次上报超过 6 小时才重报,别每次启动都传 App 列表
        if (System.currentTimeMillis() - last > 6 * 3600_000L) {
            reportPhone(ctx);
            reportApps(ctx);
            sp.edit().putLong("lastReport", System.currentTimeMillis()).apply();
        }
    }
}
