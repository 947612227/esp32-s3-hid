package com.dsh.touchpad;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 统一弹窗样式。
 *
 * 需求约定(用户明确要求):
 *   ① 取消 / 保存按钮放在弹窗**顶部**
 *   ② **点击弹窗外部不关闭**(避免误触丢失正在填的内容)
 *   ③ 右上角有关闭(×)
 *
 * 所以不能用 AlertDialog 的默认按钮区(它在底部),这里整块自绘。
 */
public class Dialogs {

    public interface OnOk { void onOk(); }

    private static final int C_CARD = 0xff161b22;
    private static final int C_FG   = 0xffc9d1d9;
    private static final int C_DIM  = 0xff8b949e;
    private static final int C_ACC  = 0xff58a6ff;
    private static final int C_LINE = 0xff30363d;

    private static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    /**
     * 构建一个统一风格的弹窗。
     *
     * @param title   标题(左上)
     * @param body    内容区(可为 null)
     * @param okText  主按钮文字,null 表示不显示
     * @param onOk    主按钮回调(为 null 时点了只关闭)
     * @param cancelText 取消按钮文字,null 表示不显示
     */
    public static AlertDialog show(final Context ctx, String title, View body,
                                   String okText, final OnOk onOk, String cancelText) {
        return show(ctx, title, body, okText, onOk, cancelText, true, false, null);
    }

    /** 带右上角「×」关闭按钮(点了什么都不做,只是关掉) */
    public static AlertDialog showWithClose(final Context ctx, String title, View body,
                                            String okText, final OnOk onOk,
                                            String extraText, final OnOk onExtra) {
        return show(ctx, title, body, okText, onOk, extraText, false, true, onExtra);
    }

    /**
     * @param wrapScroll 是否把内容包进 ScrollView。
     *        ⚠️ 内容里如果有 ListView,必须传 false ——
     *        ListView 套在 ScrollView 里**收不到点击事件**(父容器会抢走触摸),
     *        表现为"点了列表项没反应"。实测踩过。
     */
    public static AlertDialog show(final Context ctx, String title, View body,
                                   String okText, final OnOk onOk, String cancelText,
                                   boolean wrapScroll) {
        return show(ctx, title, body, okText, onOk, cancelText, wrapScroll, false, null);
    }

    public static AlertDialog show(final Context ctx, String title, View body,
                                   String okText, final OnOk onOk, String cancelText,
                                   boolean wrapScroll, boolean withClose,
                                   final OnOk onExtraMaybe) {
        final AlertDialog dlg = new AlertDialog.Builder(ctx).create();

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_CARD);

        // ---- 顶部条:标题 + 按钮 ----
        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8));

        TextView t = new TextView(ctx);
        t.setText(title == null ? "" : title);
        t.setTextColor(C_FG);
        t.setTextSize(16);
        t.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(t);

        if (cancelText != null) {
            Button cancel = barButton(ctx, cancelText, false);
            final OnOk extra = onExtraMaybe;
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (extra != null) extra.onOk();
                    else dlg.dismiss();
                }
            });
            top.addView(cancel);
        }
        if (okText != null) {
            Button ok = barButton(ctx, okText, true);
            ok.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (onOk != null) onOk.onOk();
                    // 回调里若自己 dismiss 了就不重复
                    if (dlg.isShowing()) dlg.dismiss();
                }
            });
            top.addView(ok);
        }
        if (withClose) {
            TextView close = new TextView(ctx);
            close.setText("✕");
            close.setTextColor(C_DIM);
            close.setTextSize(18);
            close.setGravity(Gravity.CENTER);
            close.setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 38));
            clp.leftMargin = dp(ctx, 6);
            close.setLayoutParams(clp);
            close.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { dlg.dismiss(); }
            });
            top.addView(close);
        }

        root.addView(top);

        View line = new View(ctx);
        line.setBackgroundColor(C_LINE);
        root.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1)));

        // ---- 内容 ----
        if (body != null) {
            if (wrapScroll) {
                ScrollView sc = new ScrollView(ctx);
                sc.addView(body);
                root.addView(sc, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                // 内容自带滚动(例如 ListView),直接放,不要再套一层
                root.addView(body, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }

        dlg.setView(root);

        // 需求:点外部不关闭
        dlg.setCanceledOnTouchOutside(false);
        // 返回键允许关闭(否则可能卡住);如要禁用改成 false
        dlg.setCancelable(true);

        dlg.show();
        return dlg;
    }

    /** 顶部小按钮 */
    private static Button barButton(Context ctx, String text, boolean primary) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTextColor(primary ? Color.WHITE : C_FG);
        b.setBackgroundColor(primary ? 0xff1f6feb : 0xff21262d);
        b.setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0);
        b.setMinimumWidth(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 38));
        lp.leftMargin = dp(ctx, 6);
        b.setLayoutParams(lp);
        return b;
    }

    /** 纯提示弹窗(只有一个「知道了」) */
    public static AlertDialog info(Context ctx, String title, String message) {
        TextView t = new TextView(ctx);
        t.setText(message);
        t.setTextColor(C_DIM);
        t.setTextSize(14);
        t.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 16));
        t.setLineSpacing(dp(ctx, 4), 1f);
        return show(ctx, title, t, "知道了", null, null);
    }

    /** 二选一确认弹窗 */
    public static AlertDialog confirm(Context ctx, String title, String message,
                                     String okText, OnOk onOk) {
        TextView t = new TextView(ctx);
        t.setText(message);
        t.setTextColor(C_DIM);
        t.setTextSize(14);
        t.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 16));
        t.setLineSpacing(dp(ctx, 4), 1f);
        return show(ctx, title, t, okText == null ? "确定" : okText, onOk, "取消");
    }

    /** 分隔用:带下边距的容器 */
    public static LinearLayout column(Context ctx) {
        LinearLayout v = new LinearLayout(ctx);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 16));
        return v;
    }

    public static TextView label(Context ctx, String s) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextColor(C_DIM);
        t.setTextSize(12);
        t.setPadding(0, dp(ctx, 8), 0, dp(ctx, 4));
        return t;
    }

    public static TextView value(Context ctx, String s, int color) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextColor(color == 0 ? C_ACC : color);
        t.setTextSize(14);
        return t;
    }
}
