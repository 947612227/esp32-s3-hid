package com.dsh.touchpad;

import android.app.Activity;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 「帮助」页。
 *
 * ══════════════════════════════════════════════════════════════════
 *  为什么单独做这个页
 *
 *  原来设置弹窗里混了一堆**说明性文字**(键盘弹窗怎么解、连不上 WiFi 怎么办、
 *  使用前须知…),越堆越长,真正的**功能开关**反而找不到。
 *
 *  现在:
 *    · **设置** = 只有能点的开关和入口
 *    · **帮助** = 所有"为什么/怎么办"的解释
 * ══════════════════════════════════════════════════════════════════
 */
public class Help {

    /** 各主题的 key */
    public static final String T_PAGE    = "page";     // 页面识别
    public static final String T_KEYBOARD = "keyboard"; // 外接键盘弹窗
    public static final String T_WIFI    = "wifi";     // 连不上 WiFi
    public static final String T_NOTICE  = "notice";   // 使用前须知
    public static final String T_SLEEP   = "sleep";    // 为什么不息屏
    public static final String T_FLOAT   = "float";    // 悬浮窗为什么不可触摸
    public static final String T_EMPTY   = "empty";    // 流程点空了
    public static final String T_DATA    = "data";     // 数据存在哪
    public static final String T_KILL    = "kill";     // App 老被杀

    public interface OnPick { void onPick(String key); }

    private static int dp(Activity a, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                a.getResources().getDisplayMetrics());
    }

    private static final int C_FG   = 0xffc9d1d9;
    private static final int C_DIM  = 0xff8b949e;
    private static final int C_ACC  = 0xff58a6ff;
    private static final int C_WARN = 0xffffd479;

    // ================================================================ 目录页

    public static void show(final Activity act, final OnPick pick) {
        LinearLayout box = Dialogs.column(act);
        box.addView(Dialogs.label(act,
                "设置里只放「能点的开关」,这里放「为什么 / 怎么办」。\n点下面任意一条看详细说明。"));

        item(act, box, "🛡", "App 老是被杀 / 页面识别老断", "MIUI 必须配的 3 个权限(很关键)", T_KILL, pick);
        item(act, box, "🔍", "页面识别(无障碍)", "怎么开、有什么用、为什么有时不灵", T_PAGE, pick);
        item(act, box, "⌨️", "打字时弹「是否连入外接键盘」", "根因是 HID 自称键盘,已修", T_KEYBOARD, pick);
        item(act, box, "📶", "板子连不上 WiFi", "首次配网 / 换网络的完整步骤", T_WIFI, pick);
        item(act, box, "📋", "使用前须知", "权限 / 登录 / 抢票预约 / 购票人", T_NOTICE, pick);
        item(act, box, "⚡", "执行时为什么不息屏", "手机息屏最大只有分钟级,怎么办", T_SLEEP, pick);
        item(act, box, "🪟", "悬浮窗为什么点不动", "面板故意设成「不可触摸」的原因", T_FLOAT, pick);
        item(act, box, "🎯", "流程点空了怎么办", "按原因逐条排查", T_EMPTY, pick);
        item(act, box, "💾", "数据存在哪 / 重装会丢什么", "什么在板子上、什么在手机上", T_DATA, pick);

        Dialogs.show(act, "帮助", box, null, null, "关闭");
    }

    private static void item(Activity act, LinearLayout box, String icon,
                             String title, String sub, final String key, final OnPick pick) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(act, 10), 0, dp(act, 10));
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pick.onPick(key); }
        });

        TextView t = new TextView(act);
        t.setText(icon + "  " + title);
        t.setTextColor(C_ACC);
        t.setTextSize(14);
        row.addView(t);

        TextView s = new TextView(act);
        s.setText("     " + sub);
        s.setTextColor(C_DIM);
        s.setTextSize(11);
        s.setPadding(0, dp(act, 2), 0, 0);
        row.addView(s);

        // 底部分隔线
        View line = new View(act);
        line.setBackgroundColor(0x33ffffff);
        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row);
        wrap.addView(line, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(act, 1)));
        box.addView(wrap);
    }

    // ================================================================ 各主题详情

    /** 页面识别:怎么开、有什么用 */
    public static void page(Activity act, boolean enabledNow) {
        LinearLayout box = Dialogs.column(act);
        box.addView(t(act, enabledNow ? "✅ 当前已开启" : "❌ 当前未开启",
                enabledNow ? C_ACC : 0xfff85149));

        box.addView(h(act, "它是干什么的"));
        box.addView(p(act,
                "流程是「盲点」:wait 3000 猜页面加载完了,然后按坐标点。\n"
                + "页面慢一点就点空,而且失败了完全不知道为什么。\n\n"
                + "开启后,App 能知道:\n"
                + "  · 现在在哪个 App(cn.damai)\n"
                + "  · 现在在哪个页面(NcovSkuActivity)\n"
                + "  · 这页上有什么文字(「预约想看票档」「可预约」)"));

        box.addView(h(act, "能带来什么"));
        box.addView(p(act,
                "① 悬浮窗直接显示「现在在哪个页面」\n"
                + "② 菜单底部点版本号旁边能复制页面句柄\n"
                + "③ 脚本里可以用两个新指令:\n"
                + "      waitfor 关键字 [超时ms]    等到页面出现再继续\n"
                + "      ifpage 关键字 … else … endif   按页面分支\n"
                + "   (关键字写 Activity 短名就行,包含匹配、忽略大小写)"));

        box.addView(h(act, "怎么开"));
        box.addView(p(act,
                "设置 → 页面识别 → 点「去开启」\n"
                + "→ 进「已下载的服务」→ 点「触摸注入板 · 页面识别」\n"
                + "→ 打开右上角开关\n"
                + "→ 弹「危险」警告时,勾选「我已知晓」,等 4 秒倒计时走完,点「确定」"));

        box.addView(h(act, "⚠️ 只读,不点"));
        box.addView(p(act,
                "本服务配置里只申请了「读窗口内容」,\n"
                + "没有 canPerformGestures(代替点击的能力)。\n\n"
                + "点击始终走 HID 注入 —— 那是系统级真实触摸,通用性最好。"));

        box.addView(h(act, "⚠️ 显示「已开启」但没反应?"));
        box.addView(p(act,
                "MIUI 的无障碍有时会卡在「正在绑定」状态(设置里显示已启用,\n"
                + "但服务其实没连上)。触发条件通常是 App 进程被杀过。\n\n"
                + "解决办法:重启手机。(实测有效;在设置里关掉再打开不一定行)"));

        Dialogs.show(act, "页面识别", box, null, null, "知道了");
    }

    /**
     * MIUI 后台/权限配置。
     *
     * ⚠️ 这是**最容易踩、后果最严重**的一项:
     *    不配的话 App 一切到后台就被 MIUI 杀掉 →
     *      ① 页面识别停止 → 板子收不到页面 → **waitfor 永远超时**
     *      ② 定时任务不会触发
     *      ③ 更糟:进程被杀后 MIUI 的**无障碍绑定会卡死**,
     *         设置里显示"已开启"但永远连不上,**只能重启手机**
     */
    public static void kill(Activity act) {
        LinearLayout box = Dialogs.column(act);
        box.addView(t(act, "不配这三项,页面识别基本用不了", 0xfff85149));

        box.addView(h(act, "为什么会这样"));
        box.addView(p(act,
                "跑流程时目标 App(大麦)在前台,本 App 在后台。\n"
                + "MIUI 对后台进程管得很严,切走几秒就把 App 杀掉。\n\n"
                + "进程一死:\n"
                + "  · 页面识别停止 → 板子收不到页面 → waitfor 永远超时\n"
                + "  · 定时任务不会触发\n"
                + "  · 无障碍绑定会卡死(设置里显示已开启,其实连不上),\n"
                + "    而且在设置里关掉再打开也没用 —— 只能重启手机"));

        // ⚠️ 按品牌给步骤 —— 小米和三星的设置路径**完全不一样**,
        //    只写一套的话另一台机器上根本找不到。
        String brand = "";
        try { brand = android.os.Build.MANUFACTURER == null ? ""
                : android.os.Build.MANUFACTURER.toLowerCase(); } catch (Throwable ignored) {}

        box.addView(h(act, "必须配的(在系统设置里)"));
        if (brand.contains("samsung")) {
            box.addView(p(act,
                    "【① 无障碍(页面识别)】\n"
                    + "   设置 → 辅助功能 → 已安装的应用程序 → 触摸注入板 → 开启\n\n"
                    + "【② 电池:不受限制】← 三星杀后台主要靠这个\n"
                    + "   设置 → 电池和设备维护 → 电池 → 后台使用限制\n"
                    + "     · 「自动运行应用程序」→ 把「触摸注入板」**打开**\n"
                    + "     · 「使未使用的应用程序进入休眠」→ 确认没有勾上它\n"
                    + "   再补一道:设置 → 应用程序 → 触摸注入板 → 电池 → 选「不受限制」\n\n"
                    + "【③ 通知权限】\n"
                    + "   首次打开 App 会弹窗,选「允许」\n"
                    + "   (不给我也能跑,但看不到「App 还活着」的常驻通知)\n\n"
                    + "【④ 锁定后台】\n"
                    + "   打开最近任务 → 长按「触摸注入板」卡片 → 点「锁定」\n\n"
                    + "【⑤ 关闭「自动优化」】\n"
                    + "   设置 → 电池和设备维护 → 右上角 ⋮ → 自动化 → 关闭「自动优化」\n"
                    + "   (开着的话三星会按习惯把不常用的 App 冻结)"));
        } else if (brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco")) {
            box.addView(p(act,
                    "【① 自启动】最关键的\n"
                    + "   设置 → 应用设置 → 应用管理 → 触摸注入板 → 自启动 → 允许\n\n"
                    + "【② 省电策略】\n"
                    + "   同上页面 → 省电策略 → 选「无限制」\n\n"
                    + "【③ 锁定后台】\n"
                    + "   打开最近任务 → 把「触摸注入板」卡片下拉/长按加锁\n"
                    + "   (加锁后点「×」清后台不会清掉它)"));
        } else {
            box.addView(p(act,
                    "不同品牌路径不一样,按这三件事找:\n\n"
                    + "【① 自启动 / 自动运行】\n"
                    + "   让 App 能被系统自动拉起\n\n"
                    + "【② 电池:不受限制 / 无限制】\n"
                    + "   别让系统为了省电把它冻结或杀掉\n\n"
                    + "【③ 锁定后台】\n"
                    + "   最近任务里把本 App 加锁,清后台时不清理它\n\n"
                    + "【④ 通知权限】允许\n"
                    + "   (Android 13+,不给也能跑,但看不到常驻通知)"));
        }

        box.addView(h(act, "怎么确认配好了"));
        box.addView(p(act,
                "配好后:把 App 切到后台,去开大麦,等 30 秒,\n"
                + "然后回来看设置里的「页面识别」那行是不是还是 ✅ 已开启。\n\n"
                + "如果变成了「⚠️ 开关是开的,但没连上」→\n"
                + "说明又被杀了,重启手机恢复,然后把上面三项再检查一遍。"));

        box.addView(h(act, "本 App 做了什么"));
        box.addView(p(act,
                "App 会在「页面识别开着」或「流程执行中」时启动一个前台服务\n"
                + "(状态栏会有一条「触摸注入板 · 页面识别」的通知)。\n\n"
                + "前台服务能大幅降低被杀的概率,但 MIUI 的「自启动」权限仍然是必需的 ——\n"
                + "系统级限制 App 自己绕不过去。"));

        box.addView(h(act, "⚠️ 重装 App 之后一定要重启手机"));
        box.addView(p(act,
                "实测:每次重装 APK,MIUI 都会把无障碍绑定弄坏。\n"
                + "装完 → 重启手机 才恢复。\n\n"
                + "所以别频繁重装,能用就别动。"));

        Dialogs.show(act, "App 老是被杀", box, null, null, "知道了");
    }

    /** 为什么执行时不息屏 */
    public static void sleep(Activity act) {
        LinearLayout box = Dialogs.column(act);
        box.addView(h(act, "问题"));
        box.addView(p(act,
                "手机设置里息屏时间最大只到「分钟」级,没有「永不」选项。\n"
                + "抢票等到点的时候手机一息屏,流程就白跑了。"));

        box.addView(h(act, "为什么常用的办法不管用"));
        box.addView(p(act,
                "跑流程时目标 App 在前台、本 App 在后台,所以\n"
                + "Activity 上的 FLAG_KEEP_SCREEN_ON 不起作用\n"
                + "(那是给前台窗口用的)。"));

        box.addView(h(act, "本 App 做的三重保活"));
        box.addView(p(act,
                "① 改系统息屏时间(主力)\n"
                + "   执行开始把 screen_off_timeout 改成 2147483647(约 24.8 天 ≈ 永不),\n"
                + "   执行结束自动改回原值。需要「修改系统设置」权限。\n\n"
                + "② 悬浮窗压住息屏(零权限兜底)\n"
                + "   悬浮窗只要可见就压住息屏,不需要任何权限。\n\n"
                + "③ 进程被杀也能恢复\n"
                + "   执行中 App 被杀,息屏时间会卡在「永不」;\n"
                + "   下次启动会自动恢复成你原来的值。"));

        box.addView(h(act, "权限怎么给"));
        box.addView(p(act,
                "设置 → 执行时保持屏幕常亮 →\n"
                + "如果显示红色「缺少「修改系统设置」权限」,点它跳到系统设置,\n"
                + "找到「触摸注入板」并允许。\n\n"
                + "⚠️ 重装 App 会把权限重置,需要重新给一次。"));

        Dialogs.show(act, "执行时不息屏", box, null, null, "知道了");
    }

    /** 悬浮窗为什么点不动 */
    public static void floatWindow(Activity act) {
        LinearLayout box = Dialogs.column(act);
        box.addView(h(act, "面板是故意设成「不可触摸」的"));
        box.addView(p(act,
                "悬浮窗分两个部分:\n"
                + "  · 把柄(左边那一条按钮)—— 可触摸\n"
                + "  · 面板(显示时间/日志的那块)—— 不可触摸\n\n"
                + "面板加了 FLAG_NOT_TOUCHABLE,意思是触摸事件完全穿透过去。\n\n"
                + "为什么要这样:板子注入的点击是绝对坐标。\n"
                + "如果面板能接收触摸,注入的点击落到面板上就会被它吃掉,\n"
                + "流程就点空了。"));

        box.addView(h(act, "所以"));
        box.addView(p(act,
                "所有操作都做在把柄上(5 个按钮):\n"
                + "  ⠿ 拖动 · 🙈 让路 15 秒 · 📐 坐标拾取 · 🔍 复制页面句柄 · ◀ 收到边框\n\n"
                + "面板只负责看(时间、目标 App、当前页面、下次定时、最近拾取的坐标)。"));

        Dialogs.show(act, "悬浮窗", box, null, null, "知道了");
    }

    /** 流程点空了怎么排查 */
    public static void empty(Activity act) {
        LinearLayout box = Dialogs.column(act);
        box.addView(h(act, "按原因逐条排查"));

        box.addView(p(act,
                "① 页面还没加载完\n"
                + "   把 wait 改长,或者用 waitfor 关键字 5000\n"
                + "   (开着页面识别才能用 waitfor)\n\n"
                + "② 界面改版 / 换机型\n"
                + "   坐标是绝对的,分辨率或布局一变就失效 → 重新拾取坐标\n\n"
                + "③ 坐标单位不对\n"
                + "   拾取器用的是全屏归一化,和板子一致 —— 别手工换算\n\n"
                + "④ 悬浮窗挡住了\n"
                + "   点 🙈 让它让路 15 秒\n\n"
                + "⑤ 流程运行时你手动碰了手机\n"
                + "   注入的是绝对坐标,你碰了会打架 —— 跑的时候别碰\n\n"
                + "⑥ 前台不是目标 App\n"
                + "   流程开跑前 App 会先启动目标应用;确认绑定的 App 是对的"));

        box.addView(h(act, "最好的办法"));
        box.addView(p(act,
                "开启页面识别,然后用 waitfor / ifpage:\n\n"
                + "    waitfor 预约想看票档 10000\n"
                + "    ifpage NcovSkuActivity\n"
                + "        T 0.5 0.96 60 抢票按钮\n"
                + "    else\n"
                + "        T 0.5 0.96 60 不在预约页,点底部\n"
                + "    endif\n\n"
                + "这样页面没到就等,页面不对就走另一支,不会盲点。"));

        Dialogs.show(act, "流程点空了", box, null, null, "知道了");
    }

    /** 数据存在哪 */
    public static void data(Activity act) {
        LinearLayout box = Dialogs.column(act);
        box.addView(h(act, "两部分,存在不同地方"));

        box.addView(p(act,
                "① 流程内容 → 板子 Flash(断电不丢)\n"
                + "  · 每个流程一个文件,存在板子 /scripts/ 下\n"
                + "  · 换手机不用重录,连上就能看到\n"
                + "  · 板子刷固件不会丢(OTA 不碰文件系统)\n\n"
                + "② 流程的「附加信息」 → 手机 App 本地\n"
                + "  · 目标 App、定时设置、开启/暂停状态、流程排序\n"
                + "  · 坐标拾取历史\n"
                + "  · 悬浮窗开关、屏幕常亮开关"));

        box.addView(h(act, "所以"));
        box.addView(p(act,
                "· 卸载 App 或清数据 → 流程内容还在板子上,但附加信息会丢\n"
                + "  (定时没了、目标 App 要重选)\n"
                + "· 恢复出厂 / 清空流程 → 流程内容没了(附加信息还在,但没用了)\n"
                + "· 想完整备份 → 流程导出成文本自己存一份,附加信息设一遍就行"));

        Dialogs.show(act, "数据存在哪", box, null, null, "知道了");
    }

    // ================================================================ 小工具

    private static TextView h(Activity act, String s) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextColor(C_ACC);
        t.setTextSize(14);
        t.setPadding(0, dp(act, 14), 0, dp(act, 4));
        return t;
    }

    private static TextView p(Activity act, String s) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextColor(C_FG);
        t.setTextSize(12);
        return t;
    }

    private static TextView t(Activity act, String s, int color) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(13);
        return t;
    }
}
