package com.dsh.touchpad;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 坐标拾取器:盖一层全屏透明窗口,点哪儿记哪儿。
 *
 * ══════════════════════════════════════════════════════════════
 *  为什么这样做:
 *  Android **不允许普通 App 观察"发给别的 App 的触摸"**,所以
 *  "一边正常用 App、一边把每次点击都录下来"是做不到的
 *  (那需要无障碍服务或 root)。常见录制类 App 用的是无障碍。
 *
 *  但"取一个坐标"这件事可以用更简单可靠的办法:
 *  临时盖一层**可触摸**的全屏透明窗口 —— 你点哪儿,这一下就被这层
 *  拿走并记录,然后它自己消失。缺点是这一次点击**不会下发给下面的 App**
 *  (所以是"取完点再返回",不是"边用边录")。
 *
 *  ⚠️ 这层窗口可触摸,所以**只在拾取的那一瞬间存在**,取完立刻移除,
 *     不会影响正常的触摸注入。
 * ══════════════════════════════════════════════════════════════
 */
public class CoordPicker {

    public interface OnPick {
        /** x/y 都是 0~1 的归一化坐标 */
        void onPick(float x, float y);
        void onCancel();
    }

    private static final int C_CARD = 0xCC161b22;
    private static final int C_FG   = 0xffc9d1d9;
    private static final int C_ACC  = 0xff58a6ff;
    private static final int C_WARN = 0xffffd479;

    /**
     * 最近拾取到的坐标(最新的在前),供编辑流程时直接取用。
     * **持久化**:存 SharedPreferences —— 否则 App 一重启记录就没了,
     * 而"先在目标 App 里取点、再回 App 编辑流程"中间很可能要切来切去。
     */
    private static final List<float[]> HISTORY = new ArrayList<float[]>();
    private static final int HISTORY_MAX = 12;
    private static final String PREF = "picks";
    private static final String KEY = "list";
    private static android.content.SharedPreferences SP = null;
    private static boolean loaded = false;

    private final Context ctx;
    private final WindowManager wm;
    private final OnPick cb;

    private View root;
    private WindowManager.LayoutParams lp;
    private boolean showing = false;

    public CoordPicker(Context ctx, OnPick cb) {
        this.ctx = ctx;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        this.cb = cb;
    }

    /**
     * 取**真实屏幕**尺寸。
     *
     * ⚠️ 不能用 getResources().getDisplayMetrics() —— 在悬浮窗/应用上下文里
     *    它返回的是"去掉状态栏和导航栏的内容区"(实测 2088),不是整屏(2316),
     *    算出来的 Y 会偏大。而板子的触摸注入是按整屏归一化的,两边必须一致。
     */
    private int[] realScreen() {
        int[] out = new int[2];
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Rect b = wm.getMaximumWindowMetrics().getBounds();
                out[0] = b.width();
                out[1] = b.height();
            } else {
                android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
                wm.getDefaultDisplay().getRealMetrics(dm);
                out[0] = dm.widthPixels;
                out[1] = dm.heightPixels;
            }
        } catch (Throwable t) {
            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            out[0] = dm.widthPixels;
            out[1] = dm.heightPixels;
        }
        if (out[0] <= 0 || out[1] <= 0) {
            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            out[0] = dm.widthPixels;
            out[1] = dm.heightPixels;
        }
        return out;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }

    // ================================================================ 历史记录

    private static void ensureLoaded(Context c) {
        if (loaded || c == null) return;
        loaded = true;
        SP = c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String raw = SP.getString(KEY, "");
        HISTORY.clear();
        for (String seg : raw.split(";")) {
            String[] p = seg.split(",");
            if (p.length != 2) continue;
            try {
                HISTORY.add(new float[]{Float.parseFloat(p[0]), Float.parseFloat(p[1])});
            } catch (Exception ignored) {}
        }
    }

    private static void persist() {
        if (SP == null) return;
        StringBuilder sb = new StringBuilder();
        for (float[] f : HISTORY) {
            if (sb.length() > 0) sb.append(';');
            sb.append(f[0]).append(',').append(f[1]);
        }
        SP.edit().putString(KEY, sb.toString()).apply();
    }

    /** 记录一次拾取(持久化) */
    public static void addHistory(Context c, float x, float y) {
        ensureLoaded(c);
        HISTORY.add(0, new float[]{x, y});
        while (HISTORY.size() > HISTORY_MAX) HISTORY.remove(HISTORY.size() - 1);
        persist();
    }

    /** 最近拾取列表(只读)。传 context 可确保先把历史读出来 */
    public static List<float[]> history(Context c) {
        ensureLoaded(c);
        return HISTORY;
    }

    public static void clearHistory() {
        HISTORY.clear();
        persist();
    }

    /** 格式化:同时给出归一化值和百分比,方便直接写进流程 */
    public static String fmt(float x, float y) {
        return String.format(Locale.US, "%.3f %.3f", x, y);
    }

    public static String fmtPct(float x, float y) {
        return String.format(Locale.US, "%.1f%%, %.1f%%", x * 100, y * 100);
    }

    // ================================================================ 开始拾取

    public void start() {
        if (showing) return;
        showing = true;

        FrameLayout layer = new FrameLayout(ctx);
        // 很轻的一层暗色 —— 既提示"正在拾取",又能看清下面的 App
        layer.setBackgroundColor(0x33000000);

        // 顶部提示条(可点取消)
        LinearLayout tip = new LinearLayout(ctx);
        tip.setOrientation(LinearLayout.VERTICAL);
        tip.setBackgroundColor(C_CARD);
        tip.setPadding(dp(16), dp(12), dp(16), dp(12));

        TextView t1 = new TextView(ctx);
        t1.setText("📍 点屏幕上你要的位置");
        t1.setTextColor(C_WARN);
        t1.setTextSize(15);
        tip.addView(t1);

        TextView t2 = new TextView(ctx);
        t2.setText("取完自动返回。点这里取消");
        t2.setTextColor(C_ACC);
        t2.setTextSize(12);
        t2.setPadding(0, dp(4), 0, 0);
        tip.addView(t2);

        tip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(true); }
        });

        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.TOP;
        layer.addView(tip, tlp);

        // 全屏接收触摸
        layer.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    // ⚠️ 必须用 **屏幕绝对坐标** 除以 **屏幕尺寸**。
                    //    不能用 e.getX()/v.getWidth() —— 这层窗口的高度不等于屏幕
                    //    高度(会被状态栏挤掉一截),用它算出来的 Y 会偏大。
                    //    实测:点屏幕 y=1800,按视图算是 0.817,按屏幕算才是 0.777。
                    int[] scr = realScreen();
                    float x = Math.max(0f, Math.min(1f, e.getRawX() / scr[0]));
                    float y = Math.max(0f, Math.min(1f, e.getRawY() / scr[1]));
                    pick(x, y);
                    return true;
                }
                return true;
            }
        });

        root = layer;
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                // 可触摸(要接这一下点击) + 不抢焦点
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        try {
            wm.addView(root, lp);
        } catch (Throwable t) {
            showing = false;
            if (cb != null) cb.onCancel();
        }
    }

    private void pick(float x, float y) {
        addHistory(ctx, x, y);
        finish(false);
        if (cb != null) cb.onPick(x, y);
    }

    private void finish(boolean cancelled) {
        if (!showing) return;
        showing = false;
        try { wm.removeView(root); } catch (Throwable ignored) {}
        if (cancelled && cb != null) cb.onCancel();
    }

    public void cancel() { finish(true); }

    public boolean isShowing() { return showing; }
}
