package com.dsh.touchpad;

import android.app.Activity;
import android.text.util.Linkify;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 「关于」页与隐私协议。
 *
 * 隐私协议按 App **实际申请的能力**撰写(D7:只做流程自动化,不涉及任何具体 App):
 *   - 读取已安装应用列表(用于让你选目标 App)
 *   - 悬浮窗(用于显示日志)
 *   - 网络(用于连板子、校时)
 * 全部数据只在本机与局域网内的板子之间流转,**不上传任何服务器**。
 */
public class About {

    // ⚠️ 以前这里是**写死的常量**,改 AndroidManifest 的版本号时忘了同步,
    //    结果手机上装的是 build 3、菜单还显示 build 2(用户实测反馈)。
    //    现在改成**从系统实时读**,永远不会对不上。
    //    下面两个常量只作为读不到时的兜底。
    public static final String VERSION_NAME_FALLBACK = "2.1";
    public static final int    VERSION_CODE_FALLBACK = 3;
    public static final String AUTHOR       = "一个好人";
    public static final String EMAIL        = "huazhonglijian@163.com";

    /** 实时读版本名(从 AndroidManifest 里读,不再写死) */
    public static String versionName(android.content.Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable e) {
            return VERSION_NAME_FALLBACK;
        }
    }

    /** 实时读版本号 */
    public static int versionCode(android.content.Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Throwable e) {
            return VERSION_CODE_FALLBACK;
        }
    }

    private static int dp(Activity a, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                a.getResources().getDisplayMetrics());
    }

    private static final int C_FG  = 0xffc9d1d9;
    private static final int C_DIM = 0xff8b949e;
    private static final int C_ACC = 0xff58a6ff;
    private static final int C_WARN = 0xffffd479;

    // ================================================================ 关于页

    public static void show(final Activity act) {
        LinearLayout box = Dialogs.column(act);

        box.addView(h(act, "触摸注入板"));
        box.addView(p(act, "版本 " + versionName(act) + " (build " + versionCode(act) + ")"));

        box.addView(h(act, "作者"));
        TextView who = p(act, AUTHOR + "\n" + EMAIL);
        Linkify.addLinks(who, Linkify.EMAIL_ADDRESSES);
        who.setLinkTextColor(C_ACC);
        box.addView(who);

        box.addView(h(act, "这是什么"));
        box.addView(p(act,
                "配合一块 ESP32-S3 板子使用。板子通过蓝牙(或 USB)模拟成手机的触摸屏,"
                + "本 App 负责把「流程」下发给板子执行。\n\n"
                + "因为板子在系统看来就是一块真实触摸屏,所以注入的触摸"
                + "与真人手指点击没有区别,适用于任何界面。"));

        box.addView(h(act, "能做什么"));
        box.addView(p(act,
                "· 流程编辑:点击 / 长按 / 双击 / 直线滑动 / 曲线滑动 / 双指 / 捏合\n"
                + "· App 选择:指定流程操作的目标应用\n"
                + "· 定时执行:指定时刻或倒计时后自动运行\n"
                + "· 频率执行:按毫秒间隔重复运行\n"
                + "· 悬浮窗:实时看日志\n"
                + "· 网页控制台:板子自带,浏览器也能操作"));

        box.addView(h(act, "怎么用"));
        box.addView(p(act,
                "1. 板子通电,手机蓝牙连上「ESP32-S3_XXXX」(老固件叫「ESP32S3 Touch HID」)\n"
                + "2. 在本 App 顶部「地址」填板子 IP(板子串口日志里能看到)\n"
                + "3. 右上角菜单 → App 列表,选目标应用\n"
                + "4. 「手势」页可以边点边看坐标,先试落点\n"
                + "5. 「流程」页新建流程,再手动开启"));

        box.addView(h(act, "已知限制"));
        box.addView(p(act,
                "板子与手机配对后,首次聚焦输入框时,三星系统会弹"
                + "「是否连入外接键盘」。这是三星系统行为,App 无法关闭。\n"
                + "点一下「否」即可,之后输入正常。"));

        box.addView(h(act, "重要提醒"));
        TextView warn = p(act,
                "注入的触摸是「按绝对坐标」打到屏幕上的,它不知道屏幕上是什么。\n"
                + "所以流程的坐标如果落在别的地方,就会点到别的地方。\n"
                + "新写流程请先用「手势」页的坐标板验证落点。");
        warn.setTextColor(C_WARN);
        box.addView(warn);

        box.addView(h(act, "隐私政策"));
        box.addView(p(act, PRIVACY));

        box.addView(h(act, "开源与免责"));
        box.addView(p(act,
                "本工具仅用于个人自动化与学习研究。\n"
                + "请勿用于任何违反法律法规或第三方服务条款的场景。\n"
                + "使用本工具产生的一切后果由使用者自行承担。"));

        Dialogs.show(act, "关于", box, null, null, "关闭");
    }

    // ================================================================ 隐私协议

    public static final String PRIVACY =
            "生效日期:2026-10-02\n\n"
            + "一、我们不收集什么\n"
            + "本应用没有服务器,不收集、不上传、不分享任何个人信息。"
            + "没有账号体系,不需要注册登录。没有广告,没有统计埋点。\n\n"
            + "二、应用会用到哪些能力,以及为什么\n"
            + "1. 读取已安装应用列表\n"
            + "   用途:让你从列表里选择流程要操作的目标应用。\n"
            + "   范围:仅读取应用名称、包名与图标。\n"
            + "   去向:仅在本机显示,不对外发送。\n\n"
            + "2. 悬浮窗\n"
            + "   用途:在你使用其他应用时,悬浮显示运行日志与状态。\n"
            + "   范围:仅显示本应用自己产生的日志。\n"
            + "   说明:悬浮窗被设为「不可触摸」,不会拦截你的操作。\n\n"
            + "3. 网络访问\n"
            + "   用途:① 连接你局域网内的控制板;② 通过 NTP 校准时间。\n"
            + "   范围:仅访问你填写的板子地址所在局域网,以及公共 NTP 服务器"
            + "(如 ntp.aliyun.com)。\n"
            + "   说明:与板子之间传输的只有流程内容与状态;校时请求不含任何身份信息。\n\n"
            + "4. 蓝牙\n"
            + "   用途:与触摸注入板通信。\n"
            + "   范围:仅与你配对的那一块板子。\n\n"
            + "三、数据存储\n"
            + "流程内容保存在控制板的 Flash 里。\n"
            + "本 App 仅在本机保存:板子地址、时区选择、界面偏好。\n"
            + "卸载应用即全部清除。\n\n"
            + "四、数据共享\n"
            + "不向任何第三方提供、出售或共享你的数据。\n\n"
            + "五、关于被操作的应用\n"
            + "本应用提供的是通用的触摸注入与流程编排能力,"
            + "不针对任何特定应用做适配,也不读取、不修改任何第三方应用的数据。\n\n"
            + "六、你的权利\n"
            + "你可以随时在系统设置里撤销悬浮窗、蓝牙、网络等权限,"
            + "或直接卸载本应用。\n\n"
            + "七、联系方式\n"
            + "如对本政策有疑问,请联系:" + EMAIL;

    // ================================================================ 小工具

    private static TextView h(Activity a, String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextColor(C_ACC);
        t.setTextSize(13);
        t.setPadding(0, dp(a, 14), 0, dp(a, 4));
        return t;
    }

    private static TextView p(Activity a, String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextColor(C_FG);
        t.setTextSize(13);
        t.setLineSpacing(dp(a, 4), 1.0f);
        return t;
    }
}
