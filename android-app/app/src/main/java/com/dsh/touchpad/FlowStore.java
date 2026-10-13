package com.dsh.touchpad;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * 流程的"附加信息":开启状态、定时、目标 App。
 *
 * 为什么存在 App 侧而不是板子侧:
 *   - 定时到点要"启动 App",这件事只有手机能做,板子参与不了;
 *   - 板子只负责"执行流程",保持简单,不用动固件;
 *   代价:换手机 / 重装 App 会丢这些设置(流程内容本身在板子上,不会丢)。
 *
 * 用流程**名字**做键(板子也是按名字存流程的)。
 */
public class FlowStore {

    public static final int MODE_OFF = 0;       // 不定时
    public static final int MODE_AT = 1;        // 指定时刻
    public static final int MODE_DELAY = 2;     // 倒计时

    private static final String PREF = "flows";

    /** 单个流程的附加信息 */
    public static class Meta {
        public String name = "";
        public boolean enabled = false;         // 开启/暂停(全局只允许一个开启)
        public String appPkg = "";
        public String appName = "";

        public int schedMode = MODE_OFF;
        public long atMillis = 0;               // MODE_AT:目标时刻(epoch ms)
        public long delayMs = 0;                // MODE_DELAY:延时时长
        public long startAtMillis = 0;          // MODE_DELAY:计时起点(epoch ms)
        public int repeatCount = 1;             // 执行 M 次
        public int intervalMs = 100;            // 间隔 X 毫秒
        public int intervalTimes = 1;           // 间隔执行 Y 次
        public long firedAt = 0;                // 最近一次触发时间(0=没触发过)
        public long createdAt = 0;              // 创建时间(用于"新建的排最前")
        public boolean appLaunched = false;     // 是否已在 T-5 分钟启动过目标 App
        public boolean fired = false;           // 是否已到点触发过

        // ---- 频率(流程自己的属性,不是临时会话) ----
        // ⚠️ 设计要点:频率**跟着流程走**,只有这个流程被"运行"或"定时触发"时才生效。
        //    对话框里只有「保存」,没有开始/停止 —— 用户明确要求这样。
        public boolean freqOn = false;          // 是否启用频率
        public int  freqIntervalMs = 100;       // 每 N 毫秒重跑一遍(下限 10)
        public long freqDurationMs = 60000;     // 连续执行多久(0 = 不限时长)

        /** 目标时刻(两种模式统一算成一个绝对时刻;0 表示没设定时) */
        public long targetMillis() {
            if (schedMode == MODE_AT) return atMillis;
            if (schedMode == MODE_DELAY && startAtMillis > 0) return startAtMillis + delayMs;
            return 0;
        }

        /**
         * 清掉一次性的执行状态。
         *
         * 场景:用户重新编辑了定时 —— 新的时间还没到,
         * 但上一次的 fired/appLaunched 还留着,会导致**新定时永远不会执行**。
         * 所以改完定时必须重置。
         */
        public void resetRunState() {
            fired = false;
            appLaunched = false;
            firedAt = 0;
        }

        /** 是否已过期(目标时刻已过) */
        public boolean expired(long now) {
            long t = targetMillis();
            return t > 0 && now > t + 60_000;    // 超过 1 分钟算过期
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("name", name);
                o.put("enabled", enabled);
                o.put("appPkg", appPkg);
                o.put("appName", appName);
                o.put("schedMode", schedMode);
                o.put("atMillis", atMillis);
                o.put("delayMs", delayMs);
                o.put("startAtMillis", startAtMillis);
                o.put("repeatCount", repeatCount);
                o.put("intervalMs", intervalMs);
                o.put("intervalTimes", intervalTimes);
                o.put("firedAt", firedAt);
                o.put("createdAt", createdAt);
                o.put("appLaunched", appLaunched);
                o.put("fired", fired);
                o.put("freqOn", freqOn);
                o.put("freqIntervalMs", freqIntervalMs);
                o.put("freqDurationMs", freqDurationMs);
            } catch (Exception ignored) {}
            return o;
        }

        public static Meta fromJson(JSONObject o) {
            Meta m = new Meta();
            if (o == null) return m;
            m.name = o.optString("name", "");
            m.enabled = o.optBoolean("enabled", false);
            m.appPkg = o.optString("appPkg", "");
            m.appName = o.optString("appName", "");
            m.schedMode = o.optInt("schedMode", MODE_OFF);
            m.atMillis = o.optLong("atMillis", 0);
            m.delayMs = o.optLong("delayMs", 0);
            m.startAtMillis = o.optLong("startAtMillis", 0);
            m.repeatCount = o.optInt("repeatCount", 1);
            m.intervalMs = o.optInt("intervalMs", 100);
            m.intervalTimes = o.optInt("intervalTimes", 1);
            m.firedAt = o.optLong("firedAt", 0);
            m.createdAt = o.optLong("createdAt", 0);
            m.appLaunched = o.optBoolean("appLaunched", false);
            m.fired = o.optBoolean("fired", false);
            m.freqOn = o.optBoolean("freqOn", false);
            m.freqIntervalMs = o.optInt("freqIntervalMs", 100);
            m.freqDurationMs = o.optLong("freqDurationMs", 60000);
            return m;
        }
    }

    private final SharedPreferences sp;

    public FlowStore(Context c) {
        sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public Meta get(String name) {
        String s = sp.getString(name, null);
        if (s == null) { Meta m = new Meta(); m.name = name; return m; }
        try {
            return Meta.fromJson(new JSONObject(s));
        } catch (Exception e) {
            Meta m = new Meta(); m.name = name; return m;
        }
    }

    public void put(Meta m) {
        sp.edit().putString(m.name, m.toJson().toString()).apply();
    }

    public void remove(String name) {
        sp.edit().remove(name).apply();
    }

    /**
     * 开启某个流程。**全局只允许一个开启**,所以会先把其他流程关掉。
     * 返回被顶掉的那个流程名(没有则返回 null)。
     */
    public String enableOnly(String name) {
        String displaced = null;
        for (String k : sp.getAll().keySet()) {
            if (k.equals(name)) continue;
            Meta o = get(k);
            if (o.enabled) {
                o.enabled = false;
                put(o);
                displaced = k;
            }
        }
        Meta m = get(name);
        m.enabled = true;
        put(m);
        return displaced;
    }

    public void disable(String name) {
        Meta m = get(name);
        m.enabled = false;
        put(m);
    }

    /** 所有流程名 */
    public java.util.List<String> allNames() {
        return new java.util.ArrayList<String>(sp.getAll().keySet());
    }

    /** 当前处于开启状态的流程名,没有返回 null */
    public String enabledName() {
        for (String k : sp.getAll().keySet()) {
            Meta m = get(k);
            if (m.enabled) return k;
        }
        return null;
    }

    /**
     * **所有**已启用的流程名,按"目标时刻先后"排序。
     *
     * ⚠️ 为什么需要:原来调度器只用 {@link #enabledName()} 取**一个** ——
     *    而它遍历 SharedPreferences 的顺序是**不确定**的。
     *    结果:同时开启两个定时时,可能永远只调度其中一个,
     *    你新设的那个到点**根本不会跑**
     *    (用户实测:"设置一分钟后执行并开启,时间到了并没有点立即提交")。
     *
     *    多个定时本来就是正常用法,所以必须全部遍历。
     */
    public java.util.List<String> enabledNames() {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        for (String k : sp.getAll().keySet()) {
            Meta m = get(k);
            if (m != null && m.enabled) out.add(k);
        }
        java.util.Collections.sort(out, new java.util.Comparator<String>() {
            @Override public int compare(String a, String b) {
                Meta ma = get(a), mb = get(b);
                long ta = ma == null ? Long.MAX_VALUE : ma.targetMillis();
                long tb = mb == null ? Long.MAX_VALUE : mb.targetMillis();
                return Long.compare(ta, tb);
            }
        });
        return out;
    }
}
