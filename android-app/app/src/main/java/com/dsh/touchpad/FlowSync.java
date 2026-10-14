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

    /**
     * 正在刷新中 —— **防抖**。
     *
     * ⚠️ 用户实测:"点了多次刷新列表,app 貌似崩溃了"。
     *    每点一次都会起一次全量同步(要从服务器拉 + 逐条写板子),
     *    连点会同时跑好几个,既拖垮蓝牙通道,也容易触发各种竞态。
     *    所以刷新期间直接忽略后续点击。
     */
    private static volatile boolean refreshing = false;

    public static boolean isRefreshing() { return refreshing; }

    /**
     * ⚠️ **必须保证 refreshing 一定被清掉** ——
     *    踩过的坑:上一次刷新在某个失败分支里直接 return 了,
     *    refreshing 留在 true,于是下一次点刷新被防抖挡掉,
     *    日志显示"服务器拉取失败:正在刷新中,请稍候",用户以为按钮坏了。
     */
    private static void clearRefreshing() { refreshing = false; }

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
        if (force) {
            if (refreshing) {                      // 防抖:刷新期间忽略重复点击
                if (cb != null) cb.done(false, "正在刷新中,请稍候");
                return;
            }
            refreshing = true;
            // ⚠️⚠️ 全量刷新**必须先做"上行"** ——
            //     板子上有、服务器没有的流程要先传上去,
            //     否则下面第 ④ 步的对齐会把它当成"服务器已删除"清掉。
            //     (这就是上次丢数据的根因)
            pushBoardOnlyFlows(act, board, new Cb() {
                @Override public void done(boolean ok, String info) {
                    android.util.Log.i("TouchPad", "刷新:上行 " + info);
                    pullInner(act, board, true, cb);
                }
            });
            return;
        }
        pullInner(act, board, false, cb);
    }

    /**
     * 「上行」:把板子上有、服务器上没有的流程传上去。
     *
     * 顺序:读板子名字 → 拉服务器清单 → 逐个读正文并 push
     */
    private static void pushBoardOnlyFlows(final Activity act, final Board board, final Cb cb) {
        if (board == null) { cb.done(true, "没有板子,跳过"); return; }
        board.scripts(new Board.Cb() {
            @Override public void done(boolean ok, String body) {
                if (!ok) { cb.done(false, "读板子失败:" + body); return; }
                final java.util.List<String> names = new java.util.ArrayList<>();
                final java.util.List<Integer> idxs = new java.util.ArrayList<>();
                try {
                    org.json.JSONObject o = new org.json.JSONObject(body);
                    org.json.JSONArray arr = o.optJSONArray("scripts");
                    for (int i = 0; arr != null && i < arr.length(); i++) {
                        org.json.JSONObject so = arr.optJSONObject(i);
                        if (so == null) continue;
                        String n = so.optString("name", "");
                        if (!n.isEmpty()) { names.add(n); idxs.add(so.optInt("index", i)); }
                    }
                } catch (Throwable t) { cb.done(false, "解析失败:" + t.getMessage()); return; }

                // 拉服务器清单(只看名字)
                Server.fetchFlows(act, 0, new Server.Cb() {
                    @Override public void done(boolean ok2, String body2) {
                        java.util.Set<String> onServer = new java.util.HashSet<>();
                        if (ok2) {
                            try {
                                org.json.JSONArray arr = new org.json.JSONObject(body2)
                                        .optJSONArray("flows");
                                for (int i = 0; arr != null && i < arr.length(); i++) {
                                    onServer.add(arr.getJSONObject(i).optString("name", ""));
                                }
                            } catch (Throwable ignored) {}
                        }
                        final java.util.List<String> todo = new java.util.ArrayList<>();
                        final java.util.List<Integer> todoIdx = new java.util.ArrayList<>();
                        for (int i = 0; i < names.size(); i++) {
                            if (!onServer.contains(names.get(i))) {
                                todo.add(names.get(i)); todoIdx.add(idxs.get(i));
                            }
                        }
                        if (todo.isEmpty()) { cb.done(true, "0 条(板子上没有服务器缺的)"); return; }
                        pushOne(act, board, todo, todoIdx, 0, 0, cb);
                    }
                });
            }
        });
    }

    /** 串行:读一条正文 → push → 下一条 */
    private static void pushOne(final Activity act, final Board board,
                                final java.util.List<String> names,
                                final java.util.List<Integer> idxs,
                                final int i, final int done, final Cb cb) {
        if (i >= names.size()) { cb.done(true, done + " 条已上传"); return; }
        final String name = names.get(i);
        board.scriptText(idxs.get(i), new Board.Cb() {
            @Override public void done(boolean ok, String body) {
                if (!ok || body == null || body.trim().isEmpty()) {
                    pushOne(act, board, names, idxs, i + 1, done, cb);
                    return;
                }
                // 板子返回的是 JSON {text: "..."} 或纯文本,这里都兼容
                String txt = body;
                try {
                    org.json.JSONObject o = new org.json.JSONObject(body);
                    if (o.has("text")) txt = o.optString("text", body);
                } catch (Throwable ignored) {}
                final String text = txt;
                Server.pushFlow(act, name, text, null, 0, new Server.Cb() {
                    @Override public void done(boolean ok2, String r) {
                        pushOne(act, board, names, idxs, i + 1, done + (ok2 ? 1 : 0), cb);
                    }
                });
            }
        });
    }

    private static void pullInner(final Activity act, final Board board, final boolean force,
                                  final Cb cb) {
        final long since = force ? 0 : lastVer(act);
        Server.fetchFlows(act, since, new Server.Cb() {
            @Override public void done(final boolean ok, final String body) {
                if (!ok) {
                    clearRefreshing();
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

                            // 先处理元数据 + 收集要写板子的
                            final java.util.List<String[]> toWrite = new java.util.ArrayList<>();
                            // 服务器这次给的全部名字(用来做全量对齐)
                            // 服务器**明确标了** disabled/deleted 的名字(用来对齐清理)
                            final java.util.List<String> serverDisabled = new java.util.ArrayList<>();
                            final java.util.List<String> serverEnabled  = new java.util.ArrayList<>();
                            int removed = 0;
                            for (int i = 0; arr != null && i < arr.length(); i++) {
                                JSONObject f = arr.getJSONObject(i);
                                String name = f.optString("name");
                                if (name.isEmpty()) continue;

                                // ⚠️ **禁用 / 删除的都要从板子上移除** ——
                                //    服务器用 status 控制"客户端能拉到哪些流程":
                                //      enabled  → 下发正文,可以执行
                                //      disabled → 不下发正文(服务器保留记录)
                                //      deleted  → 连记录都删掉
                                //    后两种在板子上都不该留着,否则会继续被执行。
                                String status = f.optString("status", "enabled");
                                if (!"enabled".equals(status) || f.optBoolean("deleted", false)) {
                                    serverDisabled.add(name);
                                } else {
                                    serverEnabled.add(name);
                                }
                                if (f.optBoolean("deleted", false)
                                        || "deleted".equals(status)
                                        || "disabled".equals(status)) {
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
                                            since, removed, cb, force, serverDisabled,
                                            serverEnabled);
                        } catch (Throwable t) {
                            clearRefreshing();
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
                                        final long since, final int removed, final Cb cb,
                                        final boolean forceAlign,
                                        final java.util.List<String> serverDisabled,
                                        final java.util.List<String> serverEnabled) {
        if (board == null || i >= list.size()) {
            setLastVer(act, ver);
            // ⚠️⚠️⚠️ **保险:服务器返回空列表时绝不做对齐清理!**
            //
            //   血的教训(我自己刚踩的):加了对齐之后,有一次服务器返回 0 条
            //   (deviceId 不匹配 / 接口异常 / 刚清过数据),
            //   对齐逻辑就把**本地和板子上所有流程全删了** ——
            //   6 个流程瞬间消失,而且板子上也没了。
            //
            //   "服务器没有" 有两种可能:
            //     ① 真的被禁用/删除了 ⇒ 该清
            //     ② 服务器这次没给数据(异常/空库/换设备)⇒ **绝对不能清**
            //   分不清的时候,**宁可留着**。所以加这道闸:
            //   只有服务器**明确给了非空清单**才允许做对齐清理。
            // ⚠️⚠️⚠️ **强加固:服务器"一条启用的都没有"时,绝不做任何删除。**
            //
            //   这已经是第二次踩了:服务器因为各种原因(被批量改成 disabled /
            //   数据异常 / 换设备)返回"零条启用",对齐就把板子清空。
            //
            //   判断依据:
            //     · 正常情况:服务器上总有几条是启用的 ⇒ 可以按标记清理
            //     · 全部禁用/删除 ⇒ **可疑**,几乎一定是数据出问题了,
            //       不是用户真想"把板子清空"。这时**宁可什么都不删**。
            //
            //   真要清空板子,用户会在服务器上**逐个**禁用 —— 那时
            //   启用的还有别的,逻辑正常工作。全空是异常态。
            final boolean serverHasNoEnabled = serverEnabled.isEmpty();
            if (serverHasNoEnabled && !serverDisabled.isEmpty()) {
                android.util.Log.w("TouchPad",
                        "服务器启用列表为空(" + serverDisabled.size()
                        + " 条禁用/删除)—— 判定为异常,跳过对齐清理");
            }
            if (forceAlign && !serverDisabled.isEmpty() && !serverHasNoEnabled) {
                // ⚠️⚠️ **全量刷新要对齐服务器**:本地有、服务器没有的,
                //    一定是服务器上被**禁用/删除**了(或者别人删的).
                //    用户明确要求:"禁用了流程,客户端刷新要全量加载服务器的数据,
                //    不保留本地数据"。所以这里把多出来的也一并清掉,
                //    否则板子上会留着服务器已经不认的流程。
                try {
                    for (String n2 : new java.util.ArrayList<String>(serverDisabled)) {
                        new FlowStore(act).remove(n2);
                        if (board != null) board.delete(n2, null);
                    }
                } catch (Throwable t) {
                    android.util.Log.w("TouchPad", "全量对齐失败:" + t.getMessage());
                }
            }
            refreshing = false;
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
                                ver, since, removed, cb, forceAlign, serverDisabled,
                                serverEnabled);
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
