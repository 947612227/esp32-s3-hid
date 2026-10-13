package com.dsh.touchpad;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * App 列表(独立全屏页面)。
 *
 * 为什么不做成弹窗:实测在 AlertDialog 里,**只要输入法弹出,
 * 弹窗上的所有点击都会被"收起输入法"这一步吃掉**(连"取消"按钮都点不动),
 * 换成 ListView、修窗口位置、改成可点击的行都没用。
 * 做成独立 Activity 后彻底没有这个问题,交互也更自然(返回键直接退出)。
 */
public class AppListActivity extends Activity {

    private static final int C_BG  = 0xff0d1117;
    private static final int C_CARD= 0xff161b22;
    private static final int C_FG  = 0xffc9d1d9;
    private static final int C_DIM = 0xff8b949e;
    private static final int C_ACC = 0xff58a6ff;
    private static final int C_LINE= 0xff30363d;

    private LinearLayout rows;
    private TextView status;
    private final List<AppPicker.AppInfo> all = new ArrayList<AppPicker.AppInfo>();
    private String kw = "";
    private String currentPkg = "";
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    /** 输入停顿多久后自动收起键盘(毫秒) */
    private static final long AUTO_HIDE_MS = 1300;

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        SharedPreferences sp = getSharedPreferences("board", MODE_PRIVATE);
        currentPkg = sp.getString("appPkg", "");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        // ---- 标题栏 ----
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(C_CARD);
        bar.setPadding(dp(8), dp(10), dp(8), dp(10));

        TextView back = new TextView(this);
        back.setText("← 返回");
        back.setTextColor(C_ACC);
        back.setTextSize(15);
        back.setPadding(dp(8), dp(6), dp(14), dp(6));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        bar.addView(back);

        TextView title = new TextView(this);
        title.setText("选择目标 App");
        title.setTextColor(C_FG);
        title.setTextSize(16);
        bar.addView(title);
        root.addView(bar);

        // ---- 搜索 ----
        LinearLayout searchBox = new LinearLayout(this);
        searchBox.setOrientation(LinearLayout.VERTICAL);
        searchBox.setPadding(dp(16), dp(10), dp(16), dp(6));

        EditText search = new EditText(this);
        search.setHint("搜索应用名或包名");
        search.setTextSize(14);
        search.setSingleLine();
        searchBox.addView(search);
        searchBoxRef = search;

        // 说明:某些输入法(实测讯飞)弹出时会吃掉列表的点击,
        // 所以这里在输入停顿后自动收起键盘,保证列表能正常点选。
        TextView tip = new TextView(this);
        tip.setText("提示:停止输入约 1 秒后会自动收起键盘,然后再点列表");
        tip.setTextColor(0xffffd479);
        tip.setTextSize(10);
        tip.setPadding(0, dp(6), 0, 0);
        searchBox.addView(tip);

        status = new TextView(this);
        status.setTextColor(C_DIM);
        status.setTextSize(11);
        status.setPadding(0, dp(6), 0, 0);
        searchBox.addView(status);
        root.addView(searchBox);

        // ---- 列表(可点击的行,不用 ListView)----
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        ScrollView sc = new ScrollView(this);
        sc.addView(rows);
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int a,int c,int d){}
            @Override public void onTextChanged(CharSequence s,int a,int c,int d){}
            @Override public void afterTextChanged(Editable e) {
                kw = e.toString().trim().toLowerCase(Locale.ROOT);
                render();
                // 输入停顿 → 自动收起键盘
                ui.removeCallbacks(hideIme);
                ui.postDelayed(hideIme, AUTO_HIDE_MS);
            }
        });

        status.setText("正在读取应用列表…");
        load();
    }

    private EditText searchBoxRef;

    /** 收起软键盘 */
    private void hideKeyboard() {
        View v = getCurrentFocus();
        if (v == null) v = searchBoxRef;
        if (v == null) return;
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        v.clearFocus();
    }

    private final Runnable hideIme = new Runnable() {
        @Override public void run() { hideKeyboard(); }
    };

    private void load() {
        new Thread(new Runnable() {
            @Override public void run() {
                final List<AppPicker.AppInfo> list = AppPicker.loadNow(AppListActivity.this);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        all.clear();
                        all.addAll(list);
                        render();
                    }
                });
            }
        }).start();
    }

    private void render() {
        rows.removeAllViews();
        int shown = 0;
        for (AppPicker.AppInfo a : all) {
            if (!kw.isEmpty()
                    && !a.name.toLowerCase(Locale.ROOT).contains(kw)
                    && !a.pkg.toLowerCase(Locale.ROOT).contains(kw)) {
                continue;
            }
            rows.addView(makeRow(a));
            shown++;
        }
        status.setText(shown + " 个应用" + (shown == 0 ? "(换个关键词试试)" : ""));
    }

    private View makeRow(final AppPicker.AppInfo a) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));

        final boolean selected = a.pkg.equals(currentPkg);
        if (selected) row.setBackgroundColor(0xff1c2128);

        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                getSharedPreferences("board", MODE_PRIVATE).edit()
                        .putString("appName", a.name)
                        .putString("appPkg", a.pkg)
                        .apply();
                android.widget.Toast.makeText(AppListActivity.this,
                        "已选择:" + a.name, android.widget.Toast.LENGTH_SHORT).show();
                finish();
            }
        });

        ImageView iv = new ImageView(this);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(42)));
        if (a.icon != null) iv.setImageDrawable(a.icon);
        row.addView(iv);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, 0, 0);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView n = new TextView(this);
        n.setText(a.name + (selected ? "  ✓" : ""));
        n.setTextColor(selected ? C_ACC : C_FG);
        n.setTextSize(15);
        col.addView(n);

        TextView p = new TextView(this);
        p.setText(a.pkg);
        p.setTextColor(C_DIM);
        p.setTextSize(11);
        col.addView(p);
        row.addView(col);

        // 分隔线
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View line = new View(this);
        line.setBackgroundColor(C_LINE);
        wrap.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return wrap;
    }
}
