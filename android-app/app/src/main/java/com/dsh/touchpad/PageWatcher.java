package com.dsh.touchpad;

import android.content.Context;
import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;

/**
 * 页面识别(无障碍服务)—— **只看不点**。
 *
 * ══════════════════════════════════════════════════════════════════
 *  为什么需要这个
 *
 *  流程是"盲点":wait 3000 猜页面加载完了,然后按坐标点。
 *  页面慢一点就点空,而且失败了完全不知道为什么。
 *
 *  有了这个,能知道:
 *    · 现在在哪个 App(cn.damai)
 *    · 现在在哪个页面(NcovSkuActivity)
 *    · 这页上有什么文字("预约想看票档""可预约")
 *
 *  ⚠️ 边界:本服务**只读**,不做任何点击/输入/代替操作。
 *     配置里也没有申请 canPerformGestures。
 *     点击始终走 HID 注入(系统级真实触摸,通用性更好)。
 * ══════════════════════════════════════════════════════════════════
 */
public class PageWatcher extends AccessibilityService {

    /** 当前前台 App 的包名 */
    private static volatile String sPkg = "";
    /** 当前页面的 Activity 类名(不含包名) */
    private static volatile String sAct = "";
    /** 有没有拿到过事件(用来区分"服务没开"和"还没切过页面") */
    private static volatile boolean sGotEvent = false;
    /** 服务是否已连接 */
    private static volatile boolean sConnected = false;
    /**
     * 页面变化计数。
     *
     * App 的 pageTick 靠对比它来判断"页面变了" →
     * **一变就立刻推给板子**,这样脚本开头的 waitfor 不用干等。
     */
    private static volatile int sRev = 0;

    // ══════════════════════════════════════════════════════════════
    //  「页面特征」—— 修掉"切页面识别不到"的核心
    //
    //  ⚠️ 只靠 Activity 判断页面是**不够**的:
    //     大麦的「首页 ↔ 我的」是同一个 Activity 里的 Fragment 切换,
    //     Activity 一直是 MainActivity —— 于是:
    //       · 变化检测不触发 → 不重读文字 → 一直是旧页面
    //       · 用户看到"切来切去都是 MainActivity"
    //     现代 App 大量用单 Activity + Fragment,这个问题很普遍。
    //
    //  所以改成:**Activity + 页面上最靠前的一段文字** 一起当"页面特征"。
    //  特征变了才算"换了页面"。
    // ══════════════════════════════════════════════════════════════

    /** 当前页面特征(Activity | 前几段文字),给界面显示用 */
    private static volatile String sSig = "";
    /** 最近一次读到的页面文字(缓存,别重复遍历控件树) */
    private static volatile String sText = "";
    /** 有事件进来就标脏,pageTick 会挑合适的时机重算特征 */
    private static volatile boolean sDirty = true;

    public static String signature() { return sSig; }
    public static String textCached() { return sText; }

    /**
     * 重算页面特征(有变化才真去读控件树)。
     * 由 App 的 pageTick 定期调用 —— 大约 0.7 秒一次。
     *
     * 特征变了就 sRev++,pageTick 据此立刻把新页面推给板子。
     */
    public static void refreshIfDirty() {
        if (!sDirty) return;
        sDirty = false;
        PageWatcher inst = sInst;
        if (inst == null || !sConnected) return;
        try {
            // ⚠️ Activity 名可能一直是空的(刚连上 / 从没切过窗口)。
            //    窗口标题能兜底拿到 —— 只在空的时候查一次,不浪费。
            if (sAct == null || sAct.isEmpty()) {
                try {
                    java.util.List<android.view.accessibility.AccessibilityWindowInfo> ws =
                            inst.getWindows();
                    if (ws != null) {
                        for (android.view.accessibility.AccessibilityWindowInfo w : ws) {
                            if (w == null || w.getType()
                                    != android.view.accessibility.AccessibilityWindowInfo
                                            .TYPE_APPLICATION) continue;
                            CharSequence t = w.getTitle();
                            if (t != null && t.toString().endsWith("Activity")) {
                                sAct = t.toString();
                                break;
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
            String txt = pageText(inst, 40);
            String sig = (sAct == null ? "" : sAct) + " | " + txt;
            if (!sig.equals(sSig)) {
                sSig = sig;
                sText = txt;
                sRev++;                 // → pageTick 立刻推送
            }
        } catch (Throwable ignored) {}
    }

    // ---------------------------------------------------------------- 生命周期

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        sConnected = true;
        sInst = this;
        // 连上时先读一次当前窗口,不用等用户切页面
        refreshFromWindow();
    }

    @Override public boolean onUnbind(android.content.Intent intent) {
        sConnected = false;
        sPkg = ""; sAct = ""; sGotEvent = false;
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        sConnected = false;
        sInst = null;
        super.onDestroy();
    }

    @Override public void onInterrupt() { /* 不需要处理 */ }

    // ---------------------------------------------------------------- 事件

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null) return;
        int t = e.getEventType();
        if (t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && t != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return;
        }
        CharSequence p = e.getPackageName();
        CharSequence c = e.getClassName();
        if (p != null) sPkg = p.toString();
        // TYPE_WINDOW_CONTENT_CHANGED 时 className 常常是控件类,别覆盖掉 Activity 名
        if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && c != null) {
            String nv = c.toString();
            // ⚠️ 只认**看起来像 Activity** 的类名。
            //    WINDOW_STATE_CHANGED 也会为弹窗/浮层触发,那时 className
            //    是 LinearLayout / FrameLayout 这种控件类 ——
            //    实测被污染成"当前页面:LinearLayout",完全没法看了。
            if (nv.endsWith("Activity") || nv.endsWith("ActivityAlias")) {
                if (!nv.equals(sAct)) {
                    sAct = nv;
                    sRev++;          // 页面变了 → 通知 pageTick 立刻推送
                }
                sGotEvent = true;
            }
        }
        if (sPkg != null && !sPkg.isEmpty()) sGotEvent = true;

        // ⚠️ 关键:任何窗口/内容变化都标脏 ——
        //    不再只依赖 Activity 变化(Fragment 切换时 Activity 是不变的)
        sDirty = true;
    }

    /** 主动读一次当前窗口(服务刚连上时用) */
    private void refreshFromWindow() {
        try {
            // ⚠️ Activity 名平时**只能从 WINDOW_STATE_CHANGED 事件里拿**。
            //    刚连上服务、用户还没切过窗口时,它一直是空的
            //    (实测:重启后第一次看,act 是空字符串)。
            //    这里用**窗口标题**兜底 —— API 24+ 的
            //    AccessibilityWindowInfo.getTitle() 通常就是 Activity 名。
            if (sAct == null || sAct.isEmpty()) {
                java.util.List<android.view.accessibility.AccessibilityWindowInfo> ws =
                        getWindows();
                if (ws != null) {
                    for (android.view.accessibility.AccessibilityWindowInfo w : ws) {
                        if (w == null) continue;
                        if (w.getType()
                                != android.view.accessibility.AccessibilityWindowInfo
                                        .TYPE_APPLICATION) continue;
                        CharSequence t = w.getTitle();
                        if (t != null && t.toString().endsWith("Activity")) {
                            sAct = t.toString();
                            break;
                        }
                    }
                }
            }
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r == null) return;
            CharSequence p = r.getPackageName();
            if (p != null) sPkg = p.toString();
            sGotEvent = true;
        } catch (Throwable ignored) {}
    }

    // ---------------------------------------------------------------- 对外查询

    /** 服务在系统设置里开没开 */
    /** **真的连上了**(能读到页面) —— 只有它为 true 时页面推送才有效 */
    public static boolean enabled() {
        return sConnected;
    }

    /**
     * 系统设置里**开关是不是开着**(不代表连上了)。
     *
     * ⚠️ MIUI 有个坑:重装 App 之后,设置里开关还是「开」的,
     *    但服务卡在「正在绑定」永远连不上,页面推送完全无效,
     *    而且**在设置里关掉再打开也没用** —— **只能重启手机**。
     *
     * 所以要把「开关开着」和「真的连上」区分开,否则用户会以为已经开了。
     */
    public static boolean enabledInSettings(Context ctx) {
        try {
            String s = android.provider.Settings.Secure.getString(
                    ctx.getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null) return false;
            String me = ctx.getPackageName() + "/" + PageWatcher.class.getName();
            for (String part : s.split(":")) {
                if (part.equalsIgnoreCase(me)) return true;
            }
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 页面变化计数(变了就 +1) */
    public static int revision() { return sRev; }

    /** 有没有拿到过页面信息 */
    public static boolean hasData() {
        return sConnected && sGotEvent;
    }

    /** 当前包名,如 cn.damai */
    public static String pkg() { return sPkg == null ? "" : sPkg; }

    /** 当前 Activity 的**完整类名**,如 cn.damai.xxx.ProjectDetailActivity */
    public static String activityFull() { return sAct == null ? "" : sAct; }

    /**
     * 复制用的"页面句柄"文本。
     *
     * 优先给**完整类名**(写 ifpage / waitfor 时要用它,而且它是短名的超集);
     * 实在没有就退化成 "包名/短名"。
     */
    public static String handleText() {
        String full = activityFull();
        if (!full.isEmpty()) return full;
        String p = pkg(), a = activityShort();
        if (p.isEmpty() && a.isEmpty()) return "";
        return a.isEmpty() ? p : (p + "/" + a);
    }

    /** 当前 Activity 短名,如 NcovSkuActivity */
    public static String activityShort() {
        String a = sAct == null ? "" : sAct;
        int i = a.lastIndexOf('.');
        return i >= 0 ? a.substring(i + 1) : a;
    }

    /**
     * 一行摘要,给悬浮窗和日志用。
     * 形如 "大麦 · NcovSkuActivity";什么都没拿到就返回 ""。
     */
    /**
     * 悬浮窗上显示的「当前页面」。
     *
     * ⚠️ 光显示 Activity 名是不够的 —— 大麦的首页和「我的」是同一个
     *    MainActivity(Fragment 切换),用户会以为"页面识别坏了"。
     *    所以把**页面文字的前几段**也带上,一眼就能看出页面变了:
     *
     *      cn.damai
     *      MainActivity · 首页 / 推荐 / 演出 / 我的
     *      ────────────────────────────────
     *      MainActivity · 我的 / 登录 / 订单 / 设置      ← 切过来了!
     */
    public static String summary() {
        if (!sConnected) return "";
        String pkg = pkg();
        if (pkg.isEmpty()) return "";
        String app = appLabelOf(pkg);
        String act = activityShort();
        String head = act.isEmpty() ? app : app + " · " + act;

        // 附上页面文字的前几段(最多 4 段 / 24 字),够区分页面
        String tx = sText;
        if (tx != null && !tx.isEmpty()) {
            String[] parts = tx.split(" / ");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length && i < 4; i++) {
                String p = parts[i].trim();
                if (p.isEmpty()) continue;
                if (sb.length() > 0) sb.append(" / ");
                sb.append(p);
                if (sb.length() > 24) break;
            }
            if (sb.length() > 0) head += "  ·  " + sb;
        }
        return head;
    }

    // ══════════════════════════════════════════════════════════════
    //  「上一页的词」—— 帮用户找出**本页独有**的词
    //
    //  ⚠️ 为什么需要:大麦这种 App 是**单 Activity + Fragment**,
    //     「首页」和「我的」的 Activity 都是 MainActivity,
    //     而页面文字里**大部分是共用的**(顶栏城市 + 底栏页签:
    //     北京/搜索/首页/影院·场馆/大麦VIP/发现/我的…)。
    //     所以"取前几段文字当特征"根本分不出页面。
    //
    //  办法:记住**上一次你点 🔍 时的词集**。
    //  在 A 页点一次、切到 B 页再点一次 →
    //  B 页里**没在 A 页出现过的词**就是 B 页独有的,标 ⭐ 推荐当关键字。
    // ══════════════════════════════════════════════════════════════
    private static volatile java.util.Set<String> sPrevWords =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** 本页的词里,哪些是上一页没有的(⭐ 推荐当 waitfor 关键字) */
    public static boolean isNewWord(String w) {
        if (w == null) return false;
        java.util.Set<String> pv = sPrevWords;
        return !pv.isEmpty() && !pv.contains(w.trim());
    }

    /** 取"上一页词集"的快照 —— ⚠️ 必须在 rememberWordsAsPrev() **之前**调,
     *  否则比的是"当前页自己",所有词都会被判成"不新"(实测踩过) */
    public static java.util.Set<String> prevWordsSnapshot() {
        return new java.util.HashSet<String>(sPrevWords);
    }

    /** 把当前这页的词记成"上一页"(每次打开选词面板时调一次) */
    public static void rememberWordsAsPrev() {
        java.util.Set<String> now = new java.util.HashSet<String>(textWords());
        sPrevWords = java.util.Collections.synchronizedSet(now);
    }

    public static int prevWordCount() { return sPrevWords.size(); }

    /** 页面文字拆成词,给「选关键字」用 */
    public static java.util.List<String> textWords() {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        String tx = sText;
        if (tx == null || tx.isEmpty()) return out;
        for (String p : tx.split(" / ")) {
            String q = p.trim();
            if (!q.isEmpty() && !out.contains(q)) out.add(q);
        }
        return out;
    }

    /**
     * 当前页面上的可见文字(去重、限长)。
     *
     * 为什么按需读而不是缓存:控件树可能很大,常驻读取费电;
     * 而且我们只在"要写日志/要看"的时候才需要它。
     *
     * @param max 最多返回多少个词
     */
    public static String pageText(AccessibilityService svc, int max) {
        if (svc == null) return "";
        try {
            AccessibilityNodeInfo root = svc.getRootInActiveWindow();
            if (root == null) return "";
            ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<AccessibilityNodeInfo>();
            ArrayDeque<String> seen = new ArrayDeque<String>();
            q.add(root);
            int visited = 0;
            StringBuilder out = new StringBuilder();
            int count = 0;
            while (!q.isEmpty() && visited < 700 && count < max) {
                AccessibilityNodeInfo n = q.poll();
                visited++;
                // ⚠️ 必须**两个都读**:
                //    · getText()              —— 普通 TextView / EditText
                //    · getContentDescription() —— 自定义按钮 / 图片按钮 / Compose
                //    很多"已预约""立即购买"这种按钮,文字在 contentDescription 里,
                //    只读 getText() 会**完全读不到**(用户实测:"已预约按钮没出现在弹窗中")。
                CharSequence tx = n.getText();
                if (tx == null || tx.toString().trim().isEmpty()) {
                    tx = n.getContentDescription();
                }
                if (tx != null) {
                    String sv = tx.toString().trim();
                    // ⚠️⚠️ **必须过滤"带秒的时间"** ——
                    //     App 自己的标题栏显示 HH:MM:SS,它**每秒都变** ⇒
                    //     页面签名每秒都变 ⇒ 页面推送每 700ms 一次、永不停
                    //     ⇒ 蓝牙控制通道的队列被灌满 ⇒ 读流程列表一直"读取中…"
                    //     (实测:29 条请求只有 2 条拿到响应)。
                    //     带秒 = 实时刷新,不是页面内容。
                    //     只过滤带秒的;大麦的"19:00"这种开抢时间不带秒,不误伤。
                    if (sv.matches(".*\\d{1,2}:\\d{2}:\\d{2}.*")) continue;
                    if (!sv.isEmpty() && sv.length() <= 20 && !seen.contains(sv)) {
                        seen.add(sv);
                        if (out.length() > 0) out.append(" / ");
                        out.append(sv);
                        count++;
                    }
                }
                for (int i = 0; i < n.getChildCount(); i++) {
                    AccessibilityNodeInfo c = n.getChild(i);
                    if (c != null) q.add(c);
                }
            }
            return out.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 给"页面推送"用的静态入口。
     * AccessibilityService 实例由系统持有,我们在 onServiceConnected 里存一份。
     */
    private static volatile PageWatcher sInst = null;
    public static String pageTextRef = "";

    /** 刷新并返回当前页面的可见文字(由 pageTick 定期调) */
    public static String pageTextNow() {
        PageWatcher inst = sInst;
        if (inst == null) return "";
        // 先把特征刷新到最新(可能刚跳过页),再拿缓存
        sDirty = true;
        refreshIfDirty();
        if (sText != null && !sText.isEmpty()) { pageTextRef = sText; return sText; }
        String t = pageText(inst, 24);
        pageTextRef = t;
        return t;
    }

    /** 包名 → 应用名(拿不到就返回包名本身) */
    private static String appLabelOf(String pkg) {
        try {
            android.content.pm.PackageManager pm =
                    MainActivity.appContextStatic();
            if (pm == null) return pkg;
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable e) {
            return pkg;
        }
    }
}
