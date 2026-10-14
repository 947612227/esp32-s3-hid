package com.dsh.touchpad;

import android.app.Activity;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 流程同步 —— 服务器 ←→ App ←→ 板子。
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  规格(用户定的,以此为准)
 *
 *    **板子 = 流程真正存放的地方**;App 只是"读出来显示 + 写回去"。
 *
 *      · 新建 / 删除流程   → **只在 App 和板子之间**,不牵扯服务器
 *      · 刷新列表          → 从服务器拉**启用的**流程,写进板子
 *                            ⚠️ **绝不删除任何东西**
 *      · 「上传流程」按钮   → 把板子上所有流程传上去(同名覆盖、不同名新建)
 *      · 「清空本地流程」   → 删掉板子上所有流程
 *
 *  ⚠️⚠️ 这里删掉过一整套"三方对齐"逻辑 —— 它按服务器的状态去删板子上的流程,
 *      结果**两次把板子清空**(服务器那边数据异常 / 被批量禁用时)。
 *      现在的原则非常简单:**刷新只增不删**,删除必须由用户明确点按钮。
 * ══════════════════════════════════════════════════════════════════════════
 */
public class FlowSync {

    private static final String PREF = "flow_sync";
    private static final String KEY_VER = "lastVer";

    /** 「刷新」/「上传」正在跑 —— 防抖,避免用户连点导致重入 */
    private static volatile boolean busy = false;

    public static boolean isBusy() { return busy; }
    private static void end()      { busy = false; }

    public interface Cb { void done(boolean ok, String info); }

    // ---------------------------------------------------------------- 版本号

    private static long lastVer(Activity a) {
        return a.getSharedPreferences(PREF, 0).getLong(KEY_VER, 0);
    }
    private static void setLastVer(Activity a, long v) {
        a.getSharedPreferences(PREF, 0).edit().putLong(KEY_VER, v).apply();
    }

    // ================================================================ 刷新(下载)

    /**
     * 从服务器拉**启用的**流程,写进板子。
     *
     * **只增不删** —— 服务器没给的流程,板子上照旧留着。
     * 想删板子上的流程,用 {@link #clearBoard} 或让用户在 App/板子里删。
     */
    public static void pull(final Activity act, final Board board, final boolean force,
                            final Cb cb) {
        if (busy) {
            if (cb != null) cb.done(false, "正在同步中,请稍候");
            return;
        }
        busy = true;
        final long since = force ? 0 : lastVer(act);

        Server.fetchFlows(act, since, new Server.Cb() {
            @Override public void done(final boolean ok, final String body) {
                if (!ok) {
                    end();
                    if (cb != null) cb.done(false, "拉取失败:" + body);
                    return;
                }
                act.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            JSONObject d = new JSONObject(body);
                            JSONArray arr = d.optJSONArray("flows");
                            final long ver = d.optLong("ver", 0);
                            final FlowStore store = new FlowStore(act);

                            // 收集要写进板子的(只要启用的、有正文的)
                            final java.util.List<String[]> toWrite = new java.util.ArrayList<>();
                            for (int i = 0; arr != null && i < arr.length(); i++) {
                                JSONObject f = arr.getJSONObject(i);
                                String name = f.optString("name", "");
                                if (name.isEmpty()) continue;
                                if ("disabled".equals(f.optString("status", "enabled"))
                                        || f.optBoolean("deleted", false)) {
                                    continue;              // 禁用的跳过,不用管
                                }
                                JSONObject m = f.optJSONObject("meta");
                                if (m != null) {
                                    FlowStore.Meta mm = FlowStore.Meta.fromJson(m);
                                    if (mm.name == null || mm.name.isEmpty()) mm.name = name;
                                    store.put(mm);
                                }
                                String text = f.optString("text", "");
                                if (board != null && !text.isEmpty()) {
                                    toWrite.add(new String[]{name, text});
                                }
                            }
                            // 串行写(板子慢,一次全丢出去会全超时)
                            writeOne(act, board, toWrite, 0, 0, 0, ver, since, cb);
                        } catch (Throwable t) {
                            end();
                            if (cb != null) cb.done(false, "解析失败:" + t.getMessage());
                        }
                    }
                });
            }
        });
    }

    private static void writeOne(final Activity act, final Board board,
                                 final java.util.List<String[]> list, final int i,
                                 final int okN, final int failN, final long ver,
                                 final long since, final Cb cb) {
        if (board == null || i >= list.size()) {
            setLastVer(act, ver);
            end();
            String info = (since == 0 ? "全量" : "增量") + " " + list.size() + " 条"
                    + "(写入板子 " + okN + (failN > 0 ? ",失败 " + failN : "") + ")";
            if (cb != null) cb.done(true, info);
            return;
        }
        final String[] kv = list.get(i);
        board.save(kv[0], kv[1], new Board.Cb() {
            @Override public void done(boolean ok, String body) {
                act.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        writeOne(act, board, list, i + 1,
                                okN + (ok ? 1 : 0), failN + (ok ? 0 : 1),
                                ver, since, cb);
                    }
                });
            }
        });
    }

    // ================================================================ 上传

    /**
     * 把**板子上所有流程**上传到服务器。
     *
     * 同名 **覆盖**,不同名 **新建** —— 正是用户要的语义 ✅
     * (`Server.pushFlow` 传 baseVer=0 = 盲写,一定覆盖成功)
     */
    public static void uploadAll(final Activity act, final Board board, final Cb cb) {
        if (busy) { if (cb != null) cb.done(false, "正在同步中,请稍候"); return; }
        if (board == null) { if (cb != null) cb.done(false, "板子没连上"); return; }
        busy = true;

        board.scripts(new Board.Cb() {
            @Override public void done(boolean ok, String body) {
                if (!ok) { end(); if (cb != null) cb.done(false, "读板子失败:" + body); return; }
                final java.util.List<String> names = new java.util.ArrayList<>();
                final java.util.List<Integer> idxs = new java.util.ArrayList<>();
                try {
                    JSONArray arr = new JSONObject(body).optJSONArray("scripts");
                    for (int i = 0; arr != null && i < arr.length(); i++) {
                        JSONObject so = arr.optJSONObject(i);
                        if (so == null) continue;
                        String n = so.optString("name", "");
                        if (!n.isEmpty()) { names.add(n); idxs.add(so.optInt("index", i)); }
                    }
                } catch (Throwable t) {
                    end(); if (cb != null) cb.done(false, "解析失败:" + t.getMessage()); return;
                }
                if (names.isEmpty()) {
                    end(); if (cb != null) cb.done(true, "板子上没有流程"); return;
                }
                upOne(act, board, names, idxs, 0, 0, 0, cb);
            }
        });
    }

    private static void upOne(final Activity act, final Board board,
                              final java.util.List<String> names,
                              final java.util.List<Integer> idxs,
                              final int i, final int okN, final int failN, final Cb cb) {
        if (i >= names.size()) {
            end();
            if (cb != null) cb.done(true, "上传完成:" + okN + " 条成功"
                    + (failN > 0 ? "," + failN + " 条失败" : ""));
            return;
        }
        final String name = names.get(i);
        board.scriptText(idxs.get(i), new Board.Cb() {
            @Override public void done(boolean ok, String body) {
                if (!ok || body == null || body.trim().isEmpty()) {
                    upOne(act, board, names, idxs, i + 1, okN, failN + 1, cb);
                    return;
                }
                // 板子返回的可能是 JSON {text:...} 也可能是纯文本
                String txt = body;
                try {
                    JSONObject o = new JSONObject(body);
                    if (o.has("text")) txt = o.optString("text", body);
                } catch (Throwable ignored) {}
                final String text = txt;
                Server.pushFlow(act, name, text, null, 0, new Server.Cb() {
                    @Override public void done(boolean ok2, String r) {
                        upOne(act, board, names, idxs, i + 1,
                                okN + (ok2 ? 1 : 0), failN + (ok2 ? 0 : 1), cb);
                    }
                });
            }
        });
    }

    // ================================================================ 清空本地

    /** 删掉**板子上所有流程**(用户明确点「清空本地流程」才执行) */
    public static void clearBoard(final Activity act, final Board board, final Cb cb) {
        if (busy) { if (cb != null) cb.done(false, "正在同步中,请稍候"); return; }
        if (board == null) { if (cb != null) cb.done(false, "板子没连上"); return; }
        busy = true;

        board.scripts(new Board.Cb() {
            @Override public void done(boolean ok, String body) {
                if (!ok) { end(); if (cb != null) cb.done(false, "读板子失败:" + body); return; }
                final java.util.List<String> names = new java.util.ArrayList<>();
                try {
                    JSONArray arr = new JSONObject(body).optJSONArray("scripts");
                    for (int i = 0; arr != null && i < arr.length(); i++) {
                        String n = arr.getJSONObject(i).optString("name", "");
                        if (!n.isEmpty()) names.add(n);
                    }
                } catch (Throwable t) {
                    end(); if (cb != null) cb.done(false, "解析失败:" + t.getMessage()); return;
                }
                delOne(act, board, names, 0, 0, cb);
            }
        });
    }

    private static void delOne(final Activity act, final Board board,
                               final java.util.List<String> names, final int i,
                               final int done, final Cb cb) {
        if (i >= names.size()) {
            end();
            FlowStore store = new FlowStore(act);
            for (String n : names) store.remove(n);
            if (cb != null) cb.done(true, "已清空 " + done + " 条");
            return;
        }
        board.delete(names.get(i), new Board.Cb() {
            @Override public void done(boolean ok, String body) {
                delOne(act, board, names, i + 1, done + (ok ? 1 : 0), cb);
            }
        });
    }
}
