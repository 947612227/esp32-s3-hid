package com.dsh.touchpad;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 板子 HTTP 客户端。
 *
 * 板子(ESP32-S3)在局域网里开了一个 REST 服务,本类把它包成普通的方法调用。
 * 所有网络请求都放到后台线程执行,结果通过 Handler 回到主线程,便于更新界面。
 */
public class Board {

    public interface Cb {
        /** ok=true 时 body 是响应文本;ok=false 时 body 是错误说明 */
        void done(boolean ok, String body);
    }

    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler ui = new Handler(Looper.getMainLooper());

    // 默认**留空**:不写死任何 IP。
    // 首次使用由「自动发现」(UDP 广播)找到板子后写入;
    // 换网络后旧地址失效,同样靠自动发现找回。
    private String host = "";
    private int port = 80;
    private int timeoutMs = 6000;

    public void setHost(String h) {
        if (h == null) return;
        h = h.trim();
        // 允许用户直接粘贴 "http://1.2.3.4/" 或 "1.2.3.4:80" 这种完整写法
        if (h.startsWith("http://")) h = h.substring(7);
        if (h.startsWith("https://")) h = h.substring(8);
        int slash = h.indexOf('/');
        if (slash >= 0) h = h.substring(0, slash);
        int colon = h.indexOf(':');
        if (colon > 0) {
            try { port = Integer.parseInt(h.substring(colon + 1)); } catch (Exception ignored) {}
            h = h.substring(0, colon);
        }
        if (h.length() > 0) this.host = h;
    }

    public String host() { return host; }
    public int port() { return port; }
    public String baseUrl() { return "http://" + host + ":" + port; }

    /** 把在线脚本执行的时间放宽一点,避免脚本还在跑就判定超时 */
    public void setTimeout(int ms) { this.timeoutMs = ms; }

    // ---------------------------------------------------------------- 请求
    public void get(String path, Cb cb) { request("GET", path, null, cb); }

    public void post(String path, Cb cb) { request("POST", path, "", cb); }

    /** POST 文本请求体(保存脚本用),需要 Content-Type: text/plain */
    public void postText(String path, String body, Cb cb) {
        request("POST", path, body, cb);
    }

    /**
     * 全局网络锁。
     *
     * ══════════════════════════════════════════════════════════════
     *  为什么必须串行化
     *
     *  板子的 WebServer 是**单线程**的 —— 同时只能处理一个请求。
     *  而 App 里有 **3 个独立的 Board 实例**(MainActivity / FlowEditActivity /
     *  DebugReceiver),各有各的线程池:
     *    · MainActivity 每 2 秒轮询 /api/status,每 700ms 推 /api/page
     *    · FlowEditActivity 点编辑时读 /api/script
     *
     *  两个连接同时打到板子上 → 其中一个被挤掉 →
     *  **偶发「连接失败」**(用户实测反馈的就是这个)。
     *
     *  板子本来就是串行的,所以在 App 侧也串起来是**正确**的做法 ——
     *  请求会排队,而不是互相踩。
     * ══════════════════════════════════════════════════════════════
     */
    private static final Object NET_LOCK = new Object();

    /** 失败重试次数(网络抖动/板子正忙时很有用) */
    private static final int RETRY = 2;

    // ================================================================ 传输层选择
    //
    //  有两条路能读/写板子:
    //    ① WiFi HTTP  —— 带宽大,适合传长流程;但手机要连板子 WiFi(会断网)
    //    ② BLE 控制   —— 不用 WiFi;但速度慢一点
    //
    //  ⚠️ 注意:这里的"选蓝牙"指的是**控制链路**,
    //     和"触摸注入走蓝牙还是 USB"是两件事(那是刷哪个固件决定的)。

    public static final int T_AUTO = 0;   // 蓝牙可用就走蓝牙,否则 WiFi
    public static final int T_BLE  = 1;   // 强制蓝牙
    public static final int T_WIFI = 2;   // 强制 WiFi

    private int transport = T_AUTO;

    public void setTransport(int t) { transport = t; }
    public int  getTransport()      { return transport; }

    /** 这条请求能不能走蓝牙 */
    private boolean bleUsable() {
        if (transport == T_WIFI) return false;
        Ble b = Ble.get();
        if (!b.ready) {
            // AUTO 模式下顺手触发一次连接(connect 内部有去重,重复调也安全),
            // 但**本条请求**仍然老实走 WiFi —— 不等蓝牙
            if (transport == T_AUTO && !b.unsupported && ctx != null) {
                b.connect(ctx, null);
            }
            return false;
        }
        return true;
    }

    private Context ctx;
    /** 由 Activity 调一次,给蓝牙连接用 */
    public void attach(Context c) { ctx = c.getApplicationContext(); }

    /**
     * 把 HTTP 路径翻译成 BLE 命令。
     *
     * ⚠️ 固件那边的 BLE 响应**刻意做成和 HTTP 同形**(见 ble_command.cpp),
     *    所以翻译完两边返回的 JSON 是一样的,调用方完全无感。
     *
     * @return null 表示这条没有 BLE 对应(那就老实走 WiFi)
     */
    private static String toBleCmd(String method, String path, String body) {
        if (path == null) return null;
        int q = path.indexOf('?');
        String p = q < 0 ? path : path.substring(0, q);
        String query = q < 0 ? "" : path.substring(q + 1);

        if (p.equals("/api/status"))   return "STATUS";
        if (p.equals("/api/scripts"))  return "LIST";
        if (p.equals("/api/logs"))     return "LOG";
        if (p.equals("/api/stop"))     return "STOP";
        if (p.equals("/api/logs/clear")) return "LOGCLR";
        if (p.equals("/api/led")) {
            // ⚠️ 参数在 query 里(query 变量已在上方拆好)
            String m = arg(query, "mode");
            if (m.equalsIgnoreCase("off"))  return "LED OFF";
            if (m.equalsIgnoreCase("auto")) return "LED AUTO";
            if (query.isEmpty())            return "LED";        // 找板子
            return "LED " + arg(query, "r") + " " + arg(query, "g")
                 + " " + arg(query, "b") + " " + arg(query, "ms");
        }

        if (p.equals("/api/script"))   return "GET " + arg(query, "index");
        if (p.equals("/api/run"))      return "RUN " + arg(query, "index");
        if (p.equals("/api/delete"))   return "DEL " + arg(query, "name");

        if (p.equals("/api/page")) {
            // ⚠️ 这条的参数在 **query** 里(见 pushPage),不在正文里 ——
            //    一开始读 body,结果推上去的是**空的**,
            //    板子的 waitfor 永远匹配不到(实测踩过)。
            return "PAGE " + arg(query, "act") + "\n" + arg(query, "text");
        }
        if (p.equals("/api/save")) {
            // HTTP 是 ?name=X + 正文 ;BLE 是 SAVE -1\n名字\n正文
            return "SAVE -1\n" + arg(query, "name") + "\n" + (body == null ? "" : body);
        }
        return null;      // 其它(改 WiFi 密码 / OTA 等)只能走 WiFi
    }

    /** 从 query 里取一个参数(URL 解码) */
    private static String arg(String query, String key) {
        if (query == null || query.isEmpty()) return "";
        for (String kv : query.split("&")) {
            int e = kv.indexOf('=');
            if (e < 0) continue;
            if (kv.substring(0, e).equals(key)) {
                try {
                    return java.net.URLDecoder.decode(kv.substring(e + 1), "UTF-8");
                } catch (Throwable t) {
                    return kv.substring(e + 1);
                }
            }
        }
        return "";
    }

    // ================================================================ 请求合并 + 短缓存
    //
    //  ⚠️⚠️ 为什么必须做:
    //      App 里有 **8 个地方**会调 fetchStatus(),pullLogs() 也有 5 个。
    //      实测 90 秒内发了 43 个 STATUS(每 2 秒一个),而蓝牙是**串行**的:
    //      板子侧一次只花 112ms(实测),但排 20 个就是 2.2 秒 ⇒
    //      调用方等到的"超时"其实是在排队。
    //      ⇒ 把重复请求合并掉,队列自然就短了。
    //
    //  两层:
    //    ① **在途合并** —— 同一个路径已有请求在飞,后来的只挂回调,不再发
    //    ② **短缓存**   —— 刚拿到结果(<1.5 秒)的只读接口直接复用,连发都不发
    private final java.util.HashMap<String, java.util.ArrayList<Cb>> inflight
            = new java.util.HashMap<String, java.util.ArrayList<Cb>>();
    private final java.util.HashMap<String, String> cacheBody
            = new java.util.HashMap<String, String>();
    private final java.util.HashMap<String, Long> cacheAt
            = new java.util.HashMap<String, Long>();
    private static final long CACHE_MS = 1500;

    /** 只读、可以合并和缓存的接口 */
    private static boolean mergeable(String method, String path) {
        if (!"GET".equals(method) || path == null) return false;
        return path.startsWith("/api/status") || path.startsWith("/api/scripts")
            || path.startsWith("/api/logs")   || path.startsWith("/api/script?");
    }

    private void request(final String method, final String path, final String body,
                         final Cb cb) {
        // ---- ① 短缓存 ----
        if (mergeable(method, path)) {
            Long at = cacheAt.get(path);
            String c = cacheBody.get(path);
            if (at != null && c != null && System.currentTimeMillis() - at < CACHE_MS) {
                if (cb != null) {
                    final boolean ok = true;
                    final String out = c;
                    ui.post(new Runnable() {
                        @Override public void run() { cb.done(ok, out); }
                    });
                }
                return;
            }
        }

        // ---- ② 在途合并 ----
        if (mergeable(method, path)) {
            java.util.ArrayList<Cb> wait = inflight.get(path);
            if (wait != null) {
                if (cb != null) wait.add(cb);       // 只挂回调,不再发请求
                return;
            }
            wait = new java.util.ArrayList<Cb>();
            if (cb != null) wait.add(cb);
            inflight.put(path, wait);
        }
        // 不可合并的接口(写操作等)不进上面的表,但 finish() 要能通知它
        if (!mergeable(method, path) && cb != null) {
            java.util.ArrayList<Cb> one = new java.util.ArrayList<Cb>();
            one.add(cb);
            inflight.put(path, one);
        }
        if (cb != null) dispatch(method, path, body, cb, true);
    }

    /**
     * 一次请求结束:写缓存 + 通知**所有**等待者(包括合并进来的)。
     *
     * ⚠️ leader 的 cb 也在等待者列表里,所以这里统一通知;
     *    原来那两处 `cb.done(...)` 必须删掉,否则会通知两次。
     */
    private void finish(String path, boolean ok, String body) {
        if (ok && path != null) {
            cacheBody.put(path, body);
            cacheAt.put(path, System.currentTimeMillis());
        }
        java.util.ArrayList<Cb> wait = inflight.remove(path);
        if (wait == null) return;
        for (final Cb w : wait) {
            if (w == null) continue;
            final boolean fok = ok;
            final String fbody = body;
            ui.post(new Runnable() {
                @Override public void run() { w.done(fok, fbody); }
            });
        }
    }

    /** 真正发出去;把结果同时通知"合并进来的"那些回调 */
    private void dispatch(final String method, final String path, final String body,
                          final Cb cb, final boolean isLead) {
        // ---- 走蓝牙?----
        final String bleCmd = bleUsable() ? toBleCmd(method, path, body) : null;
        if (bleCmd != null) {
            android.util.Log.i("TouchLink", "→ BLE  " + path
                    + "  ⇒  " + bleCmd.substring(0, Math.min(24, bleCmd.length())));
            Ble.get().request(bleCmd, AUTO_BLE_TIMEOUT(path), new Ble.Cb() {
                @Override public void done(final String resp) {
                    final boolean ok = resp != null;
                    android.util.Log.i("TouchLink", "← BLE  " + path + "  "
                            + (ok ? "OK " + resp.length() + "B" : "失败/超时"));
                    final String out = ok ? resp : "蓝牙请求失败/超时";
                    finish(path, ok, out);
                }
            });
            return;
        }

        pool.execute(new Runnable() {
            @Override public void run() {
                String result = null;
                boolean ok = false;

                for (int attempt = 0; attempt < RETRY && !ok; attempt++) {
                    if (attempt > 0) {
                        try { Thread.sleep(180); } catch (InterruptedException ignored) {}
                    }
                    // ⚠️ 串行化:同一时刻只允许一个请求打到板子
                    synchronized (NET_LOCK) {
                        HttpURLConnection c = null;
                        try {
                            URL u = new URL(baseUrl() + path);
                            c = (HttpURLConnection) u.openConnection();
                            c.setRequestMethod(method);
                            c.setConnectTimeout(timeoutMs);
                            c.setReadTimeout(timeoutMs);
                            c.setRequestProperty("Accept", "application/json");
                            c.setRequestProperty("Connection", "close");
                            if (body != null) {
                                c.setDoOutput(true);
                                c.setRequestProperty("Content-Type",
                                        "text/plain; charset=utf-8");
                                byte[] out = body.getBytes("UTF-8");
                                c.setFixedLengthStreamingMode(out.length);
                                c.getOutputStream().write(out);
                            }
                            int code = c.getResponseCode();
                            BufferedReader br = new BufferedReader(new InputStreamReader(
                                    code >= 400 ? c.getErrorStream() : c.getInputStream(),
                                    "UTF-8"));
                            StringBuilder sb = new StringBuilder();
                            String line;
                            while ((line = br.readLine()) != null) sb.append(line).append('\n');
                            br.close();
                            result = sb.toString().trim();
                            ok = code >= 200 && code < 300;
                            if (!ok && result.isEmpty()) result = "HTTP " + code;
                        } catch (Exception e) {
                            result = "连接失败: " + e.getMessage();
                            ok = false;
                        } finally {
                            if (c != null) c.disconnect();
                        }
                    }
                }

                android.util.Log.i("TouchLink", (ok ? "← HTTP " : "✗ HTTP ")
                        + path + "  " + (result == null ? "" : result.length() + "B"));
                final boolean fok = ok;
                final String fres = result;
                // ⚠️ cb 可能是 null(pushPage 这类"不关心结果"的调用)。
                //    以前这里直接 cb.done(...) → **每 700ms 崩一次**,
                //    表现是"App 自己消失 + 无障碍绑定卡死 + waitfor 永远超时"。
                //    排查了很久才在 logcat 的 crash buffer 里看到。
                finish(path, fok, fres);
            }
        });
    }

    /** 蓝牙请求的超时:写流程比读状态慢得多 */
    private static int AUTO_BLE_TIMEOUT(String path) {
        if (path != null && (path.startsWith("/api/save") || path.startsWith("/api/script"))) {
            return 9000;
        }
        return 5000;
    }

    /** 空回调:调用方不关心结果时用它 —— **不要传 null** */
    public static final Cb NOOP = new Cb() {
        @Override public void done(boolean ok, String body) { }
    };

    // ---------------------------------------------------------------- 便捷方法
    public void status(Cb cb) { get("/api/status", cb); }

    public void scripts(Cb cb) { get("/api/scripts", cb); }

    public void run(int index, int repeat, int intervalMs, Cb cb) {
        post("/api/run?index=" + index + "&repeat=" + repeat
                + "&interval=" + intervalMs, cb);
    }

    public void stop(Cb cb) { post("/api/stop", cb); }

    public void control(String what, Cb cb) {
        post("/api/control?do=" + what, cb);
    }

    public void gesture(String op, String params, Cb cb) {
        post("/api/gesture?op=" + op + (params == null ? "" : "&" + params), cb);
    }

    /** 点击归一化坐标 0..1 */
    public void tap(float x, float y, Cb cb) {
        gesture("tap", "a=" + x + "&b=" + y + "&ms=60", cb);
    }

    public void longPress(float x, float y, Cb cb) {
        gesture("long", "a=" + x + "&b=" + y + "&ms=900", cb);
    }

    public void swipe(float x1, float y1, float x2, float y2, int ms, Cb cb) {
        gesture("swipe", "a=" + x1 + "&b=" + y1 + "&c=" + x2 + "&d=" + y2
                + "&ms=" + ms, cb);
    }

    /** 保存脚本:name 会被 URL 编码,支持中文名 */
    public void save(String name, String text, Cb cb) {
        try {
            postText("/api/save?name=" + URLEncoder.encode(name, "UTF-8"), text, cb);
        } catch (Exception e) {
            cb.done(false, "名称编码失败: " + e.getMessage());
        }
    }

    public void delete(String name, Cb cb) {
        try {
            post("/api/delete?name=" + URLEncoder.encode(name, "UTF-8"), cb);
        } catch (Exception e) {
            cb.done(false, "名称编码失败: " + e.getMessage());
        }
    }

    public void scriptText(int index, Cb cb) {
        get("/api/script?index=" + index, cb);
    }

    /** 单个按键:kind = key(键盘) / consumer(多媒体) */
    public void key(String kind, int usage, Cb cb) {
        post("/api/key?kind=" + kind + "&usage=" + usage, cb);
    }

    /** 往板子日志里塞一行(给"定时触发"这类事后排查用) */
    public void appendLog(String text, Cb cb) {
        request("POST", "/api/log", text == null ? "" : text, cb);
    }

    public void logs(Cb cb) { get("/api/logs", cb); }

    public void clearLogs(Cb cb) { post("/api/logs/clear", cb); }

    /** 频率:每 ms 毫秒重跑一遍 */
    public void schedule(int index, int ms, Cb cb) {
        schedule(index, ms, 0L, cb);
    }

    /**
     * 开启频率执行。
     * @param durMs 连续执行多久(毫秒);0 = 不限,一直跑到手动停
     */
    public void schedule(int index, int ms, long durMs, Cb cb) {
        String u = "/api/schedule?index=" + index + "&ms=" + ms;
        if (durMs > 0) u += "&durMs=" + durMs;
        post(u, cb);
    }

    public void scheduleStop(Cb cb) { post("/api/schedule/stop", cb); }

    /**
     * 让板子从**控制中心**下载固件并升级。
     * 板子会边下边写进备用分区,写完自动重启 —— 这里会等比较久(约 20 秒)。
     */
    public void otaFromServer(Cb cb) {
        post("/api/ota/from-server", cb);
    }

    /**
     * 把"当前页面"推给板子,供脚本里的 waitfor / ifpage 使用。
     * 用 GET + fire-and-forget,不关心返回。
     */
    public void pushPage(String act, String text) {
        try {
            String u = "/api/page?act=" + java.net.URLEncoder.encode(act == null ? "" : act, "UTF-8")
                     + "&text=" + java.net.URLEncoder.encode(text == null ? "" : text, "UTF-8");
            post(u, NOOP);   // 不关心结果,给个空回调(**不能传 null**)
        } catch (Throwable ignored) {}
    }

    /**
     * 点亮板子的灯 —— 用来在几块同名板子里认出这一块。
     *
     * 走当前生效的传输(WiFi 或蓝牙控制通道),所以是**立刻生效**的。
     * (服务器那边也能点,但它够不到内网的板子,要等板子心跳来取,最多 2 分钟。)
     */
    public void led(Cb cb) { get("/api/led", cb); }              // 找板子(闪)

    /**
     * 统一的 LED 入口。
     *
     * @param what "blink" = 找板子 / "off" = 关灯 / "auto" = 回自动
     *             / "r,g,b" = 指定颜色(一直保持)
     */
    public void ledBase(String what, Cb cb) {
        if (what == null || what.isEmpty() || "blink".equals(what)) { led(cb); return; }
        if ("off".equals(what))  { ledOff(cb);  return; }
        if ("auto".equals(what)) { ledAuto(cb); return; }
        String[] p = what.split(",");
        if (p.length == 3) {
            try {
                ledColor(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()),
                         Integer.parseInt(p[2].trim()), 0, cb);
                return;
            } catch (Throwable ignored) {}
        }
        led(cb);
    }

    /** 关灯(手动保持熄灭,不会被状态色点亮) */
    public void ledOff(Cb cb) { get("/api/led?mode=off", cb); }

    /** 回到自动 —— 灯重新反映板子状态(未连接蓝 / 已连接绿 / 执行中黄) */
    public void ledAuto(Cb cb) { get("/api/led?mode=auto", cb); }

    /** 指定颜色;holdMs=0 表示一直保持 */
    public void ledColor(int r, int g, int b, int holdMs, Cb cb) {
        get("/api/led?r=" + r + "&g=" + g + "&b=" + b + "&ms=" + holdMs, cb);
    }

    /** 强制重置板子的蓝牙连接(修"连不上/内核没设备") */
    public void resetBle(Cb cb) { post("/api/ble?op=reset", cb); }

    /** 给板子配网 */
    public void wifi(String ssid, String pass, Cb cb) {
        try {
            post("/api/wifi?ssid=" + URLEncoder.encode(ssid, "UTF-8")
                    + "&pass=" + URLEncoder.encode(pass, "UTF-8"), cb);
        } catch (Exception e) {
            cb.done(false, "编码失败:" + e.getMessage());
        }
    }

    public void system(String op, Cb cb) { post("/api/system?op=" + op, cb); }

    // ---------------------------------------------------------------- 解析辅助
    public static String jstr(JSONObject o, String k) {
        return o == null ? "" : o.optString(k, "");
    }

    public static int jint(JSONObject o, String k) {
        return o == null ? 0 : o.optInt(k, 0);
    }

    public static boolean jbool(JSONObject o, String k) {
        return o != null && o.optBoolean(k, false);
    }

    /** 从文本里提取提示信息(板子的接口都返回 {"message": "..."} ) */
    public static String messageOf(String body) {
        try {
            JSONObject o = new JSONObject(body);
            String m = o.optString("message", "");
            if (!m.isEmpty()) return m;
        } catch (Exception ignored) {}
        return body == null ? "" : body;
    }
}
