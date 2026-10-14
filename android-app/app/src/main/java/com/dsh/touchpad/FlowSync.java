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
        pull(act, board, false, cb);
    }

    /**
     * @param force true = **忽略本地版本号,拉全量**
     *
     *   ⚠️ 为什么需要 force:
     *      「刷新列表」的语义是"我要看服务器上最新的流程"。
     *      只拉增量的话,如果服务器上只是**改**了一条(mac 相同、ver 变大),
     *      增量也拿得到;但如果本地版本号已经追平,增量就是空的,
     *      用户点刷新会觉得"没反应"。
     *      强制全量 = 一定把服务器的当前状态完整对一遍。
     */
    public static void pull(final Activity act, final Board board, final boolean force,
                            final Cb cb) {
        final long since = force ? 0 : lastVer(act);
        Server.fetchFlows(act, since, new Server.Cb() {
            @Override public void done(final boolean ok, final String body) {
                if (!ok) { if (cb != null) cb.done(false, "拉取失败:" + body); return; }
                act.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            JSONObject d = new JSONObject(body);
                            JSONArray arr = d.optJSONArray("flows");
                            final long ver = d.optLong("ver", 0);
                            final FlowStore store = new FlowStore(act);

                            // 先处理元数据 + 收集要写板子的
                            final java.util.List<String[]> toWrite = new java.util.ArrayList<>();
                            int removed = 0;
                            for (int i = 0; arr != null && i < arr.length(); i++) {
                                JSONObject f = arr.getJSONObject(i);
                                String name = f.optString("name");
                                if (name.isEmpty()) continue;
                                if (f.optBoolean("deleted", false)) {
                                    store.remove(name);
                                    if (board != null) board.delete(name, null);
                                    removed++;
                                    continue;
                                }
                                JSONObject m = f.optJSONObject("meta");
                                if (m != null) store.put(FlowStore.Meta.fromJson(m));
                                String text = f.optString("text", "");
                                if (board != null && !text.isEmpty()) {
                                    toWrite.add(new String[]{name, text});
                                }
                            }

                            // ⚠️⚠️ 板子必须**一条一条串行写**,不能一次全丢出去。
                            //      踩过的坑:一次性 submit 之后,Board 的请求队列
                            //      被 20 秒轮询和中继占满(实测 monitor contention
                            //      6 秒),后面的 save 全超时;而我的计数器不看结果
                            //      就写"板子写入 N 条",日志在骗人 ——
                            //      板子上其实一条都没多。
                            writeSequential(act, board, toWrite, 0, 0, 0, ver,
                                            since, removed, cb);
                        } catch (Throwable t) {
                            if (cb != null) cb.done(false, "解析失败:" + t.getMessage());
                        }
                    }
                });
            }
        });
    }

    /** 串行把流程写进板子(每写完一条再写下一条) */
    private static void writeSequential(final Activity act, final Board board,
                                        final java.util.List<String[]> list, final int i,
                                        final int okN, final int failN, final long ver,
                                        final long since, final int removed, final Cb cb) {
        if (board == null || i >= list.size()) {
            setLastVer(act, ver);
            String info = (since == 0 ? "全量" : "增量") + " " + list.size() + " 条"
                    + "(板子写入成功 " + okN + (failN > 0 ? ",失败 " + failN : "")
                    + ",删除 " + removed + ") → 版本 " + ver;
            if (cb != null) cb.done(true, info);
            return;
        }
        final String[] kv = list.get(i);
        board.save(kv[0], kv[1], new Board.Cb() {
            @Override public void done(boolean ok, String body) {
                // 每写完一条回主线程再写吓一条 —— 保持串行
                act.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        writeSequential(act, board, list, i + 1,
                                okN + (ok ? 1 : 0), failN + (ok ? 0 : 1),
                                ver, since, removed, cb);
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
