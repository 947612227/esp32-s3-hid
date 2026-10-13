package com.dsh.touchpad;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 时间管家:负责 NTP 同步、时区、以及"每秒通知一次"的走时。
 *
 * 设计要点:
 *   - **不改系统时钟**(普通 App 也没权限),而是保存 offset = 网络时间 - 本机时间
 *   - 显示时统一用 {@link #nowMs()},两次同步之间靠本机时钟平滑走时
 *   - 时区可选(城市 + 时区),默认取系统时区
 */
public class TimeKeeper {

    public interface Tick { void onTick(String text); }
    public interface SyncCb { void done(boolean ok, String message); }

    private static final String PREF = "time";
    private static final String K_ZONE = "zone";
    private static final String K_CITY = "city";

    /** 常用城市 → 时区 ID。放在前面的是国内常用 */
    public static final String[][] CITIES = {
            {"北京 / 上海", "Asia/Shanghai"},
            {"香港", "Asia/Hong_Kong"},
            {"台北", "Asia/Taipei"},
            {"东京", "Asia/Tokyo"},
            {"首尔", "Asia/Seoul"},
            {"新加坡", "Asia/Singapore"},
            {"曼谷", "Asia/Bangkok"},
            {"迪拜", "Asia/Dubai"},
            {"伦敦", "Europe/London"},
            {"巴黎 / 柏林", "Europe/Paris"},
            {"莫斯科", "Europe/Moscow"},
            {"纽约", "America/New_York"},
            {"芝加哥", "America/Chicago"},
            {"洛杉矶", "America/Los_Angeles"},
            {"圣保罗", "America/Sao_Paulo"},
            {"悉尼", "Australia/Sydney"},
            {"奥克兰", "Pacific/Auckland"},
            {"UTC", "UTC"},
    };

    private final SharedPreferences sp;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private long offsetMs = 0;          // 网络时间 - 本机时间
    private long lastSyncAt = 0;        // 本机时间戳
    private String server = "";
    private long rttMs = 0;
    private boolean synced = false;
    private boolean syncing = false;

    private TimeZone zone = TimeZone.getDefault();
    private String city = "";

    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);

    private Runnable tickTask;
    private Tick tickListener;

    public TimeKeeper(Context ctx) {
        sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String zid = sp.getString(K_ZONE, "");
        city = sp.getString(K_CITY, "");
        if (!zid.isEmpty()) {
            TimeZone tz = TimeZone.getTimeZone(zid);
            if (tz != null) zone = tz;
        }
        if (city.isEmpty()) city = cityOf(zone.getID());
    }

    // ---------------------------------------------------------------- 时间
    /** 当前网络时间(毫秒) */
    public long nowMs() {
        return System.currentTimeMillis() + offsetMs;
    }

    /** 格式化后的当前时间,精确到秒 */
    public String nowText() {
        fmt.setTimeZone(zone);
        return fmt.format(new Date(nowMs()));
    }

    /** 日期 + 时间,用于悬浮窗等 */
    public String nowFullText() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
        f.setTimeZone(zone);
        return f.format(new Date(nowMs()));
    }

    // ---------------------------------------------------------------- 时区
    public TimeZone zone() { return zone; }

    public String zoneId() { return zone.getID(); }

    public String city() { return city; }

    public void setZone(String cityName, String zoneId) {
        TimeZone tz = TimeZone.getTimeZone(zoneId);
        if (tz == null) return;
        zone = tz;
        city = cityName;
        sp.edit().putString(K_ZONE, zoneId).putString(K_CITY, cityName).apply();
        if (tickListener != null) tickListener.onTick(nowText());
    }

    public static String cityOf(String zoneId) {
        for (String[] c : CITIES) {
            if (c[1].equals(zoneId)) return c[0];
        }
        return zoneId;
    }

    /** 当前时区的 UTC 偏移文本,如 UTC+8 */
    public String zoneOffsetText() {
        int off = zone.getOffset(nowMs()) / 3600000;
        int min = Math.abs(zone.getOffset(nowMs()) / 60000 % 60);
        return String.format(Locale.CHINA, "UTC%s%d%s",
                off >= 0 ? "+" : "-", Math.abs(off), min == 0 ? "" : ":" + min);
    }

    // ---------------------------------------------------------------- 同步
    public boolean synced() { return synced; }

    public boolean syncing() { return syncing; }

    public String server() { return server; }

    public long rttMs() { return rttMs; }

    /** 距上次同步过了多久(毫秒),没同步过返回 -1 */
    public long sinceSyncMs() {
        return synced ? System.currentTimeMillis() - lastSyncAt : -1;
    }

    /** 同步状态描述 */
    public String statusText() {
        if (syncing) return "正在同步…";
        if (!synced) return "未同步(显示本机时间)";
        long d = sinceSyncMs();
        String ago = d < 60000 ? (d / 1000) + " 秒前"
                : d < 3600000 ? (d / 60000) + " 分钟前"
                : (d / 3600000) + " 小时前";
        return "已同步(" + ago + " · " + server + " · " + rttMs + "ms)";
    }

    /** 与网络的偏差描述 */
    public String offsetText() {
        if (!synced) return "—";
        long o = offsetMs;
        String sign = o >= 0 ? "+" : "-";
        long a = Math.abs(o);
        if (a < 1000) return sign + a + " ms";
        return String.format(Locale.CHINA, "%s%.2f 秒", sign, a / 1000.0);
    }

    /** 异步同步一次 */
    public void sync(final SyncCb cb) {
        if (syncing) {
            if (cb != null) cb.done(false, "正在同步中");
            return;
        }
        syncing = true;
        pool.execute(new Runnable() {
            @Override public void run() {
                boolean ok;
                String msg;
                try {
                    NtpClient.Result r = NtpClient.sync(5000);
                    offsetMs = r.epochMs - System.currentTimeMillis();
                    lastSyncAt = System.currentTimeMillis();
                    server = r.server;
                    rttMs = r.roundTripMs;
                    synced = true;
                    ok = true;
                    msg = "已同步:" + r.server + "(偏差 " + offsetText() + ")";
                } catch (Exception e) {
                    ok = false;
                    msg = "同步失败:" + e.getMessage();
                }
                final boolean fok = ok;
                final String fmsg = msg;
                syncing = false;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (tickListener != null) tickListener.onTick(nowText());
                        if (cb != null) cb.done(fok, fmsg);
                    }
                });
            }
        });
    }

    // ---------------------------------------------------------------- 走时
    /** 开始每秒回调一次 */
    public void startTicking(Tick t) {
        tickListener = t;
        stopTicking();
        tickTask = new Runnable() {
            @Override public void run() {
                if (tickListener != null) {
                    tickListener.onTick(nowText());
                    ui.postDelayed(this, 1000 - (nowMs() % 1000));
                }
            }
        };
        ui.post(tickTask);
    }

    public void stopTicking() {
        if (tickTask != null) ui.removeCallbacks(tickTask);
        tickTask = null;
    }
}
