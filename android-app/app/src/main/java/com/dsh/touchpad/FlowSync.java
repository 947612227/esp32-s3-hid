package com.dsh.touchpad;

import android.app.Activity;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 流程同步:服务器 ←→ App ←→ 板子。
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  架构(第 ⑥ 项定的)
 *
 *    · **服务器是权威** —— 流程正文 + 状态都存它那(表 flows)
 *    · **App 是缓存 + 中转** —— 它同时连着服务器和板子
 *    · **板子是执行端** —— 从 App 拿到流程后执行
 *
 *  为什么必须有"App 中转"这一层:
 *      板子在内网(甚至只有热点),**服务器够不到它**。
 *      所以服务器不能推,只能等 App 来拉,再由 App 写进板子。
 *      (和"后台点灯"那条路是同一个道理。)
 *
 *  冲突策略:**最后写入者赢 + 服务器存版本号**。
 *      不做自动合并 —— 那是另一个量级的复杂度,而现在用的人很少,冲突概率极低。
 *      真冲突了服务器返回 409 带上当前内容,这里选择"以服务器为准"。
 * ══════════════════════════════════════════════════════════════════════════
 */
public class FlowSync {

    private static final String PREF = "flow_sync";
    private static final String KEY_VER = "lastVer";

    /** 上次同步到的版本号 */
    public static long lastVer(Activity a) {
        return a.getSharedPreferences(PREF, 0).getLong(KEY_VER, 0);
    }
    private static void setLastVer(Activity a, long v) {
        a.getSharedPreferences(PREF, 0).edit().putLong(KEY_VER, v).apply();
    }

    public interface Cb { void done(boolean ok, String info); }

    /**
     * 从服务器拉增量,写进本地元数据 + 板子。
     *
     * @param board 板子(可以为 null —— 那就只更新本地,等板子连上再说)
     */
    public static void pull(final Activity act, final Board board, final Cb cb) {
        final long since = lastVer(act);
        Server.fetchFlows(act, since, new Server.Cb() {
            @Override public void done(final boolean ok, final String body) {
                if (!ok) { if (cb != null) cb.done(false, "拉取失败:" + body); return; }
                act.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            JSONObject d = new JSONObject(body);
                            JSONArray arr = d.optJSONArray("flows");
                            long ver = d.optLong("ver", 0);
                            final FlowStore store = new FlowStore(act);
                            int pulled = 0, saved = 0, removed = 0;

                            if (arr != null) {
                                for (int i = 0; i < arr.length(); i++) {
                                    JSONObject f = arr.getJSONObject(i);
                                    String name = f.optString("name");
                                    if (name.isEmpty()) continue;
                                    pulled++;

                                    if (f.optBoolean("deleted", false)) {
                                        // 服务器说删了 ⇒ 本地也删,板子上也删
                                        store.remove(name);
                                        if (board != null) board.delete(name, null);
                                        removed++;
                                        continue;
                                    }
                                    // ① 元数据(状态)写本地
                                    JSONObject m = f.optJSONObject("meta");
                                    if (m != null) store.put(FlowStore.Meta.fromJson(m));
                                    // ② 正文写板子
                                    String text = f.optString("text", "");
                                    if (board != null && !text.isEmpty()) {
                                        board.save(name, text, null);
                                        saved++;
                                    }
                                }
                            }
                            setLastVer(act, ver);
                            String info = (since == 0 ? "全量" : "增量")
                                    + " " + pulled + " 条(板子写入 " + saved
                                    + ",删除 " + removed + ") → 版本 " + ver;
                            if (cb != null) cb.done(true, info);
                        } catch (Throwable t) {
                            if (cb != null) cb.done(false, "解析失败:" + t.getMessage());
                        }
                    }
                });
            }
        });
    }

    /**
     * 把一条流程推给服务器。
     *
     * @param baseVer 本地记的服务器版本;0 = 没见过(新建/导入)
     */
    public static void push(Activity act, String name, String text,
                            FlowStore.Meta meta, long baseVer, Cb cb) {
        String mj = meta == null ? null : meta.toJson().toString();
        Server.pushFlow(act, name, text, mj, baseVer, new Server.Cb() {
            @Override public void done(boolean ok, String body) {
                if (cb != null) cb.done(ok, body);
            }
        });
    }
}
