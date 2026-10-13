package com.dsh.touchpad;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.TranslateAnimation;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 右侧滑出的「三横菜单」。
 *
 * 需求:
 *   - 顶部右侧三横图标点开
 *   - 目前有:App 列表 / 设置 / 关于
 *   - **菜单底部固定展示 App 版本信息**
 *   - 后期可扩展(加条目只需在 items 里追加)
 *   - 点击菜单外部关闭
 */
public class MenuPanel {

    public static final String ITEM_APP_LIST = "app_list";
    public static final String ITEM_SETTINGS = "settings";
    public static final String ITEM_ABOUT    = "about";
    public static final String ITEM_CHECK_UPDATE = "check_update";
    public static final String ITEM_HELP     = "help";

    public interface OnPick { void onPick(String key); }

    private static final int C_MASK = 0x99000000;
    private static final int C_PANEL = 0xff161b22;
    private static final int C_FG   = 0xffc9d1d9;
    private static final int C_DIM  = 0xff8b949e;
    private static final int C_ACC  = 0xff58a6ff;
    private static final int C_LINE = 0xff30363d;
    private static final int C_WARN = 0xffffd479;

    private static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    /**
     * 把菜单挂到 root(FrameLayout)上并滑出。
     *
     * @param selectedApp 已选中的 App 名字,null 表示还没选(显示在 App 列表条目下)
     */
    public static void show(final Activity act, final FrameLayout root, int statusBarHeight,
                            String selectedApp, final OnPick onPick) {

        final FrameLayout overlay = new FrameLayout(act);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 半透明遮罩:点它关闭菜单
        View mask = new View(act);
        mask.setBackgroundColor(C_MASK);
        mask.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { close(root, overlay); }
        });
        overlay.addView(mask, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 右侧面板
        final LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(C_PANEL);
        panel.setPadding(0, statusBarHeight, 0, 0);

        int panelW = (int) (act.getResources().getDisplayMetrics().widthPixels * 0.78);

        // ---- 头部 ----
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(act, 20), dp(act, 22), dp(act, 20), dp(act, 16));

        TextView title = new TextView(act);
        title.setText("触摸注入板");
        title.setTextColor(C_FG);
        title.setTextSize(19);
        head.addView(title);

        TextView sub = new TextView(act);
        sub.setText(selectedApp == null || selectedApp.isEmpty()
                ? "尚未选择目标 App" : "目标 App:" + selectedApp);
        sub.setTextColor(selectedApp == null || selectedApp.isEmpty() ? C_DIM : C_ACC);
        sub.setTextSize(12);
        sub.setPadding(0, dp(act, 4), 0, 0);
        head.addView(sub);
        panel.addView(head);

        divider(act, panel);

        // ---- 菜单条目 ----
        ScrollView sc = new ScrollView(act);
        LinearLayout items = new LinearLayout(act);
        items.setOrientation(LinearLayout.VERTICAL);
        items.setPadding(0, dp(act, 6), 0, dp(act, 6));

        items.addView(entry(act, "📱", "App 列表", "选择要操作的目标应用", ITEM_APP_LIST,
                overlay, root, onPick));
        items.addView(entry(act, "⚙️", "设置", "悬浮窗 / 给板子配网", ITEM_SETTINGS,
                overlay, root, onPick));
        items.addView(entry(act, "❓", "帮助", "页面识别 / 配网 / 排障说明", ITEM_HELP,
                overlay, root, onPick));
        items.addView(entry(act, "ℹ️", "关于", "版本 / 作者 / 隐私协议", ITEM_ABOUT,
                overlay, root, onPick));

        sc.addView(items);
        panel.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- 底部:版本信息(固定在菜单底部)----
        // ⚠️ 整块**可点击 = 检查更新**(用户要求:点底部的版本号就能检查)
        //    以前这里不可点,用户点了没反应。
        divider(act, panel);
        LinearLayout foot = new LinearLayout(act);
        foot.setOrientation(LinearLayout.VERTICAL);
        foot.setPadding(dp(act, 20), dp(act, 12), dp(act, 20), dp(act, 18));
        foot.setClickable(true);
        foot.setBackgroundColor(0x00000000);
        foot.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                if (onPick != null) onPick.onPick(ITEM_CHECK_UPDATE);
            }
        });

        TextView ver = new TextView(act);
        ver.setText("版本 " + About.versionName(act) + " (build " + About.versionCode(act) + ")");
        ver.setTextColor(C_ACC);
        ver.setTextSize(13);
        foot.addView(ver);

        TextView upd = new TextView(act);
        upd.setText("🔄 点这里检查更新");
        upd.setTextColor(C_WARN);
        upd.setTextSize(11);
        upd.setPadding(0, dp(act, 4), 0, 0);
        foot.addView(upd);

        TextView who = new TextView(act);
        who.setText("作者:" + About.AUTHOR);
        who.setTextColor(C_DIM);
        who.setTextSize(11);
        who.setPadding(0, dp(act, 6), 0, 0);
        foot.addView(who);

        // 里面的每一行也都要能点(点在字上也算)
        android.view.View.OnClickListener chk = new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                if (onPick != null) onPick.onPick(ITEM_CHECK_UPDATE);
            }
        };
        ver.setOnClickListener(chk);
        upd.setOnClickListener(chk);
        who.setOnClickListener(chk);

        panel.addView(foot);

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                panelW, ViewGroup.LayoutParams.MATCH_PARENT);
        plp.gravity = Gravity.END;
        overlay.addView(panel, plp);

        root.addView(overlay);

        // 滑入动画
        TranslateAnimation anim = new TranslateAnimation(panelW, 0, 0, 0);
        anim.setDuration(180);
        panel.startAnimation(anim);
    }

    private static void close(FrameLayout root, View overlay) {
        root.removeView(overlay);
    }

    /** 单个菜单条目 */
    private static View entry(final Activity act, String icon, final String name,
                              String desc, final String key, final View overlay,
                              final FrameLayout root, final OnPick onPick) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(act, 20), dp(act, 14), dp(act, 16), dp(act, 14));

        TextView ic = new TextView(act);
        ic.setText(icon);
        ic.setTextSize(20);
        ic.setWidth(dp(act, 40));
        row.addView(ic);

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(act);
        t.setText(name);
        t.setTextColor(C_FG);
        t.setTextSize(15);
        col.addView(t);

        TextView d = new TextView(act);
        d.setText(desc);
        d.setTextColor(C_DIM);
        d.setTextSize(11);
        d.setPadding(0, dp(act, 2), 0, 0);
        col.addView(d);
        row.addView(col);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                close(root, overlay);
                if (onPick != null) onPick.onPick(key);
            }
        });
        return row;
    }

    private static void divider(Activity act, LinearLayout parent) {
        View line = new View(act);
        line.setBackgroundColor(C_LINE);
        parent.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 1)));
    }

    /** 三横图标按钮(给顶栏用) */
    public static TextView hamburger(Activity act) {
        TextView b = new TextView(act);
        b.setText("☰");
        b.setTextSize(22);
        b.setTextColor(C_FG);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(act, 12), 0, dp(act, 12), 0);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(act, 44)));
        return b;
    }
}
