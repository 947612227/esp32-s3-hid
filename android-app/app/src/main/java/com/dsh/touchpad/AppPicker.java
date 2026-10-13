package com.dsh.touchpad;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App 选择器:列出已安装应用(名字 / 包名 / 图标),支持搜索,单选。
 *
 * 实现要点(都是踩过坑之后的选择):
 *   1. 只列「有启动入口」的应用(与桌面一致),而不是所有包;
 *   2. 图标逐个读取较慢,所以全程异步,加载时先显示"读取中";
 *   3. 不用 ListView —— 实测在弹窗里 ListView 的 item 点击收不到
 *      (即使不套 ScrollView、即使窗口位置固定),换成一堆"可点击的行"
 *      直接挂 OnClickListener,最可靠;
 *   4. 窗口固定不随输入法移动,并靠上贴边,避免"看到的和点到的错位"。
 */
public class AppPicker {

    public interface OnPick { void onPick(String name, String pkg); }

    public static class AppInfo {
        public final String name;
        public final String pkg;
        public Drawable icon;
        AppInfo(String n, String p) { name = n; pkg = p; }
    }

    private static List<AppInfo> cached = null;

    private static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    private static final int C_FG  = 0xffc9d1d9;
    private static final int C_DIM = 0xff8b949e;
    private static final int C_ACC = 0xff58a6ff;

    public static void show(final Activity act, String currentPkg, final OnPick cb) {
        LinearLayout box = Dialogs.column(act);

        final EditText search = new EditText(act);
        search.setHint("搜索应用名或包名");
        search.setTextSize(14);
        search.setSingleLine();
        box.addView(search);

        final TextView status = new TextView(act);
        status.setTextColor(C_DIM);
        status.setTextSize(11);
        status.setPadding(0, dp(act, 6), 0, dp(act, 4));
        box.addView(status);

        final LinearLayout rows = new LinearLayout(act);
        rows.setOrientation(LinearLayout.VERTICAL);
        ScrollView sc = new ScrollView(act);
        sc.addView(rows);
        int listH = (int) (act.getResources().getDisplayMetrics().heightPixels * 0.50);
        box.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, listH));

        if (currentPkg != null && !currentPkg.isEmpty()) {
            TextView cur = new TextView(act);
            cur.setTextColor(C_ACC);
            cur.setTextSize(11);
            cur.setPadding(0, dp(act, 6), 0, 0);
            cur.setText("当前已选:" + currentPkg);
            box.addView(cur);
        }

        final android.app.AlertDialog dlg = Dialogs.show(act, "选择目标 App", box,
                null, null, "取消", false);

        if (dlg.getWindow() != null) {
            android.view.Window w = dlg.getWindow();
            // ⚠️ 原来设的是 SOFT_INPUT_ADJUST_NOTHING —— 键盘弹出来会盖住按钮,
            //    而且"点了没反应"其实是点在键盘窗口上。改成 adjustResize,
            //    让弹窗自己缩上去,按钮始终可见可点。
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.gravity = Gravity.TOP;
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.y = 0;
            w.setAttributes(lp);
        }

        final List<AppInfo> all = new ArrayList<AppInfo>();
        final String[] kw = {""};

        final Runnable render = new Runnable() {
            @Override public void run() {
                rows.removeAllViews();
                int shown = 0;
                for (AppInfo a : all) {
                    if (!kw[0].isEmpty()) {
                        String s = kw[0];
                        if (!a.name.toLowerCase(Locale.ROOT).contains(s)
                                && !a.pkg.toLowerCase(Locale.ROOT).contains(s)) continue;
                    }
                    rows.addView(makeRow(act, a, dlg, cb));
                    shown++;
                }
                status.setText(shown + " 个应用" + (shown == 0 ? "(换个关键词试试)" : ""));
            }
        };

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                kw[0] = e.toString().trim().toLowerCase(Locale.ROOT);
                render.run();
            }
        });

        status.setText("正在读取应用列表…");
        loadAsync(act, new LoadCb() {
            @Override public void done(List<AppInfo> apps) {
                act.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        all.clear();
                        all.addAll(apps);
                        render.run();
                    }
                });
            }
        });
    }

    private static View makeRow(final Activity act, final AppInfo a,
                                final android.app.AlertDialog dlg, final OnPick cb) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(act, 8), dp(act, 10), dp(act, 8), dp(act, 10));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                cb.onPick(a.name, a.pkg);
                dlg.dismiss();
            }
        });

        ImageView iv = new ImageView(act);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(act, 38), dp(act, 38)));
        if (a.icon != null) iv.setImageDrawable(a.icon);
        row.addView(iv);

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(act, 12), 0, 0, 0);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView n = new TextView(act);
        n.setText(a.name);
        n.setTextColor(C_FG);
        n.setTextSize(15);
        col.addView(n);

        TextView p = new TextView(act);
        p.setText(a.pkg);
        p.setTextColor(C_DIM);
        p.setTextSize(11);
        col.addView(p);
        row.addView(col);
        return row;
    }

    interface LoadCb { void done(List<AppInfo> apps); }

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static void loadAsync(final Context ctx, final LoadCb cb) {
        if (cached != null) { cb.done(cached); return; }
        POOL.execute(new Runnable() {
            @Override public void run() {
                final List<AppInfo> out = loadNow(ctx);
                cached = out;
                UI.post(new Runnable() {
                    @Override public void run() { cb.done(out); }
                });
            }
        });
    }

    public static List<AppInfo> loadNow(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN, null);
        main.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
        List<AppInfo> out = new ArrayList<AppInfo>();
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        String self = ctx.getPackageName();

        for (ResolveInfo ri : ris) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || pkg.equals(self)) continue;
            if (!seen.add(pkg)) continue;
            AppInfo a = new AppInfo(String.valueOf(ri.loadLabel(pm)), pkg);
            try { a.icon = ri.loadIcon(pm); } catch (Throwable ignored) { a.icon = null; }
            out.add(a);
        }

        final Collator col = Collator.getInstance(Locale.CHINA);
        Collections.sort(out, new Comparator<AppInfo>() {
            @Override public int compare(AppInfo a, AppInfo b) { return col.compare(a.name, b.name); }
        });
        return out;
    }

    public static void clearCache() { cached = null; }
}
