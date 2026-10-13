package com.dsh.touchpad;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 指令库:把每条指令能做什么、参数怎么填、示例是什么都摊开给人看。
 *
 * 用法:
 *   · 点一条  → 打开「参数编辑」(滑块调数值,实时预览生成的那一行)
 *   · 长按一条 → 直接把示例插进编辑框
 */
public class CmdPickerActivity extends Activity {

    public static final String EX_LINE = "line";

    private static final int C_BG   = 0xff0d1117;
    private static final int C_CARD = 0xff161b22;
    private static final int C_LINE = 0xff30363d;
    private static final int C_FG   = 0xffc9d1d9;
    private static final int C_DIM  = 0xff8b949e;
    private static final int C_ACC  = 0xff58a6ff;
    private static final int C_WARN = 0xffffd479;

    private static final int REQ_PARAM = 3001;

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        // 标题栏
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(C_CARD);
        bar.setPadding(dp(8), dp(10), dp(8), dp(10));

        TextView back = new TextView(this);
        back.setText("← 返回");
        back.setTextColor(C_ACC);
        back.setTextSize(15);
        back.setPadding(dp(8), dp(6), dp(12), dp(6));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setResult(RESULT_CANCELED); finish(); }
        });
        bar.addView(back);

        TextView title = new TextView(this);
        title.setText("指令库");
        title.setTextColor(C_FG);
        title.setTextSize(16);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(title);
        root.addView(bar);

        TextView hint = new TextView(this);
        hint.setText("点一条 → 用滑块调参数;长按一条 → 只插入指令本身(参数自己填)\n"
                + "坐标写法:0.5 = 屏幕 50% 处,也可以写 50% 或 16384");
        hint.setTextColor(C_WARN);
        hint.setTextSize(11);
        hint.setPadding(dp(16), dp(10), dp(16), dp(6));
        root.addView(hint);

        ScrollView sc = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(6), dp(12), dp(24));
        sc.addView(box);

        for (String g : CmdLib.GROUPS) {
            List<CmdLib.Cmd> list = CmdLib.byGroup(g);
            if (list.isEmpty()) continue;

            TextView gh = new TextView(this);
            gh.setText(g);
            gh.setTextColor(C_ACC);
            gh.setTextSize(13);
            gh.setPadding(dp(4), dp(14), 0, dp(6));
            box.addView(gh);

            for (final CmdLib.Cmd c : list) {
                box.addView(makeRow(c));
            }
        }

        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private View makeRow(final CmdLib.Cmd c) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(C_CARD);
        card.setPadding(dp(12), dp(10), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        card.setLayoutParams(lp);

        // 第一行:助记符徽标 + 中文名 + 参数形式
        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        r1.setGravity(Gravity.CENTER_VERTICAL);

        TextView badge = new TextView(this);
        badge.setText(c.letter);
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(14);
        badge.setGravity(Gravity.CENTER);
        badge.setBackgroundColor(0xff1f6feb);
        badge.setPadding(dp(8), dp(2), dp(8), dp(2));
        r1.addView(badge);

        TextView nm = new TextView(this);
        nm.setText("  " + c.name);
        nm.setTextColor(C_FG);
        nm.setTextSize(15);
        r1.addView(nm);
        card.addView(r1);

        // 参数形式
        TextView usage = new TextView(this);
        usage.setText("写法:" + c.usage);
        usage.setTextColor(C_WARN);
        usage.setTextSize(12);
        usage.setPadding(0, dp(6), 0, dp(2));
        card.addView(usage);

        // 作用
        TextView what = new TextView(this);
        what.setText(c.what);
        what.setTextColor(C_FG);
        what.setTextSize(13);
        card.addView(what);

        // 详细说明
        if (c.detail != null && !c.detail.isEmpty()) {
            TextView det = new TextView(this);
            det.setText(c.detail);
            det.setTextColor(C_DIM);
            det.setTextSize(11);
            det.setPadding(0, dp(4), 0, 0);
            det.setLineSpacing(dp(2), 1f);
            card.addView(det);
        }

        // 示例
        TextView ex = new TextView(this);
        ex.setText("示例:" + c.example);
        ex.setTextColor(C_ACC);
        ex.setTextSize(12);
        ex.setTypeface(android.graphics.Typeface.MONOSPACE);
        ex.setPadding(0, dp(6), 0, 0);
        card.addView(ex);

        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivityForResult(new Intent(CmdPickerActivity.this,
                        CmdParamActivity.class)
                        .putExtra(CmdParamActivity.EX_LETTER, c.letter), REQ_PARAM);
            }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                // 长按 = 只插入指令本身(不预填参数)
                setResult(RESULT_OK, new Intent().putExtra(EX_LINE,
                        CmdLib.template(c).trim()));
                finish();
                return true;
            }
        });
        return card;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PARAM && res == RESULT_OK && data != null) {
            String line = data.getStringExtra(EX_LINE);
            if (line != null && !line.isEmpty()) {
                setResult(RESULT_OK, new Intent().putExtra(EX_LINE, line));
                finish();
            }
        }
    }
}
