package com.dsh.touchpad;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 定时设置(独立全屏页面)。
 * 三个页签:不定时 / 指定时刻 / 倒计时。
 * 结果通过 {@link #EX_MODE} 等 extra 回传。
 */
public class ScheduleActivity extends Activity {

    public static final String EX_NAME   = "name";
    public static final String EX_MODE   = "mode";
    public static final String EX_AT     = "at";
    public static final String EX_DELAY  = "delay";
    public static final String EX_REPEAT = "repeat";
    // ---- 频率(从"流程列表的频率按钮"挪进来的)----
    public static final String EX_FREQ_ON  = "freqOn";
    public static final String EX_FREQ_MS  = "freqMs";
    public static final String EX_FREQ_DUR = "freqDur";

    private static final int C_BG   = 0xff0d1117;
    private static final int C_CARD = 0xff161b22;
    private static final int C_LINE = 0xff30363d;
    private static final int C_FG   = 0xffc9d1d9;
    private static final int C_DIM  = 0xff8b949e;
    private static final int C_ACC  = 0xff58a6ff;

    private int mode = FlowStore.MODE_OFF;
    private long atMillis;
    private int delayH, delayM, delayS;
    private int repeat = 1;

    // 频率配置(和定时模式无关,所以放在三个页签下面共用)
    private boolean freqOn = false;
    private int     freqMs = 100;
    private long    freqDur = 60000;

    private LinearLayout[] pages = new LinearLayout[3];
    private TextView[] tabs = new TextView[3];
    private EditText atEd;

    // 进入页面时**原本**的设定(用来显示"当前已设定:…"),
    // 和上面那几个会被用户改动的字段区分开
    private int  origMode = FlowStore.MODE_OFF;
    private long origAt = 0, origDelay = 0;
    private int  origRepeat = 1;

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent it = getIntent();
        String name = it.getStringExtra(EX_NAME);
        if (name == null) name = "";

        // 已传进来的初始值(编辑时)
        mode = it.getIntExtra(EX_MODE, FlowStore.MODE_OFF);
        atMillis = it.getLongExtra(EX_AT, 0);
        long delay = it.getLongExtra(EX_DELAY, 0);
        repeat = it.getIntExtra(EX_REPEAT, 1);
        freqOn  = it.getBooleanExtra(EX_FREQ_ON, false);
        freqMs  = it.getIntExtra(EX_FREQ_MS, 100);
        freqDur = it.getLongExtra(EX_FREQ_DUR, 60000);
        // 存档"进来时"的设定 —— 界面上要显示它
        origMode = mode; origAt = atMillis;
        origDelay = delay; origRepeat = repeat;

        if (atMillis == 0) atMillis = System.currentTimeMillis() + 300000;
        if (delay == 0) { delayH = 0; delayM = 5; delayS = 0; }
        else {
            delayH = (int) (delay / 3600000);
            delayM = (int) ((delay % 3600000) / 60000);
            delayS = (int) ((delay % 60000) / 1000);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

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
        title.setText("定时设置");
        title.setTextColor(C_FG);
        title.setTextSize(16);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(title);

        Button ok = new Button(this);
        ok.setText("确定");
        ok.setTextSize(14);
        ok.setAllCaps(false);
        ok.setMinimumWidth(0);
        ok.setPadding(dp(16), 0, dp(16), 0);
        ok.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { done(); }
        });
        bar.addView(ok);
        root.addView(bar);

        // ---- 页签 ----
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setBackgroundColor(C_CARD);
        String[] names = {"不定时", "指定时刻", "倒计时"};
        for (int i = 0; i < 3; i++) {
            final int mi = i;
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextSize(14);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(12), 0, dp(12));
            t.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { setMode(mi); }
            });
            tabs[i] = t;
            tabRow.addView(t);
        }
        root.addView(tabRow);
        View line = new View(this);
        line.setBackgroundColor(C_LINE);
        root.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // ---- 内容 ----
        ScrollView sc = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(24));
        sc.addView(box);

        // 页 0:不定时
        pages[0] = new LinearLayout(this);
        pages[0].setOrientation(LinearLayout.VERTICAL);
        pages[0].addView(hint("不设定时间,只在你点「运行」时执行"));
        box.addView(pages[0]);

        // 页 1:指定时刻
        pages[1] = new LinearLayout(this);
        pages[1].setOrientation(LinearLayout.VERTICAL);
        pages[1].addView(hint("到点执行(格式 2026-10-02 12:00:00)"));
        atEd = new EditText(this);
        atEd.setText(DateTimePick.fmt(atMillis));
        atEd.setTextSize(15);
        pages[1].addView(atEd);
        pages[1].addView(hint("快捷"));
        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        int[] mins = {1, 5, 30};
        for (final int m : mins) {
            Button qb = new Button(this);
            qb.setText(m + " 分钟后");
            qb.setTextSize(13);
            qb.setAllCaps(false);
            qb.setLayoutParams(new LinearLayout.LayoutParams(0, dp(44), 1f));
            qb.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    atEd.setText(DateTimePick.fmt(System.currentTimeMillis() + m * 60000L));
                }
            });
            quick.addView(qb);
        }
        pages[1].addView(quick);
        SimpleStepper.attach(this, pages[1], "执行次数",
                new SimpleStepper.Get() { @Override public int get() { return repeat; } },
                new SimpleStepper.Set() { @Override public void set(int v) { repeat = v; } },
                1, 1, 99);
        pages[1].addView(currentSchedView());
        box.addView(pages[1]);

        // 页 2:倒计时
        pages[2] = new LinearLayout(this);
        pages[2].setOrientation(LinearLayout.VERTICAL);
        pages[2].addView(hint("从现在起 N 时 N 分 N 秒之后执行"));
        SimpleStepper.attach(this, pages[2], "小时",
                new SimpleStepper.Get() { @Override public int get() { return delayH; } },
                new SimpleStepper.Set() { @Override public void set(int v) { delayH = v; } },
                0, 0, 23);
        SimpleStepper.attach(this, pages[2], "分钟",
                new SimpleStepper.Get() { @Override public int get() { return delayM; } },
                new SimpleStepper.Set() { @Override public void set(int v) { delayM = v; } },
                5, 0, 59);
        SimpleStepper.attach(this, pages[2], "秒",
                new SimpleStepper.Get() { @Override public int get() { return delayS; } },
                new SimpleStepper.Set() { @Override public void set(int v) { delayS = v; } },
                0, 0, 59);
        SimpleStepper.attach(this, pages[2], "执行次数",
                new SimpleStepper.Get() { @Override public int get() { return repeat; } },
                new SimpleStepper.Set() { @Override public void set(int v) { repeat = v; } },
                1, 1, 99);
        pages[2].addView(currentSchedView());
        box.addView(pages[2]);

        // ---- 频率(不分定时模式,三种都可能要用)----
        box.addView(freqView());

        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        setMode(mode);
    }

    /**
     * 频率设置区 —— 从"流程列表的频率按钮"挪到这里。
     *
     * 用户要求:「将流程中的频率功能挪到定时页面中」。
     *
     * 语义没变,还是**流程自己的属性**:
     *   · 不启用 → 这个流程被运行/定时触发时**只执行一次**
     *   · 启用   → 按「每 N 毫秒一次」循环,连续跑设定的时长后自动停
     *              (由**板子**计时,App 被杀也会按时停)
     */
    private View freqView() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(0, dp(12), 0, dp(4));

        TextView title = new TextView(this);
        title.setText("🔁 执行频率");
        title.setTextColor(0xff58a6ff);
        title.setTextSize(14);
        c.addView(title);

        TextView sub = new TextView(this);
        sub.setText("启用后:这个流程每次被运行/定时触发时,按下面的配置循环执行。\n"
                + "不启用 → 运行一次就结束。");
        sub.setTextColor(0xff8b949e);
        sub.setTextSize(11);
        sub.setPadding(0, dp(2), 0, dp(6));
        c.addView(sub);

        final TextView sw = new TextView(this);
        sw.setTextSize(14);
        sw.setPadding(0, dp(4), 0, dp(6));
        final Runnable paint = new Runnable() {
            @Override public void run() {
                sw.setText(freqOn ? "✅ 已启用(点一下关闭)" : "⭕ 未启用(点一下启用)");
                sw.setTextColor(freqOn ? 0xff3fb950 : 0xff8b949e);
            }
        };
        paint.run();
        sw.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { freqOn = !freqOn; paint.run(); }
        });
        c.addView(sw);

        SimpleStepper.attach(this, c, "间隔",
                new SimpleStepper.Get() { @Override public int get() { return freqMs; } },
                new SimpleStepper.Set() { @Override public void set(int v) { freqMs = v; } },
                100, 10, 2000, true);
        TextView tip = new TextView(this);
        tip.setText("下限 10 毫秒(实测 BLE 单帧最小间隔约 11ms,再小没有意义)。\n"
                + "「间隔」只是两次执行之间的**最小**间隔:流程本身耗时更长时会连续执行,不会报错。");
        tip.setTextColor(0xffffd479);
        tip.setTextSize(10);
        tip.setPadding(0, dp(2), 0, dp(8));
        c.addView(tip);

        TextView durTitle = new TextView(this);
        durTitle.setText("连续执行多久");
        durTitle.setTextColor(0xffc9d1d9);
        durTitle.setTextSize(13);
        c.addView(durTitle);

        // 时长拆成 时/分/秒(freqDur 是毫秒)
        final int[] h = {(int) (freqDur / 3600000L)};
        final int[] mnt = {(int) ((freqDur % 3600000L) / 60000L)};
        final int[] sec = {(int) ((freqDur % 60000L) / 1000L)};
        SimpleStepper.attach(this, c, "小时",
                new SimpleStepper.Get() { @Override public int get() { return h[0]; } },
                new SimpleStepper.Set() {
                    @Override public void set(int v) {
                        h[0] = v; freqDur = (h[0] * 3600L + mnt[0] * 60L + sec[0]) * 1000L; } },
                0, 0, 23, true);
        SimpleStepper.attach(this, c, "分钟",
                new SimpleStepper.Get() { @Override public int get() { return mnt[0]; } },
                new SimpleStepper.Set() {
                    @Override public void set(int v) {
                        mnt[0] = v; freqDur = (h[0] * 3600L + mnt[0] * 60L + sec[0]) * 1000L; } },
                1, 0, 59, true);
        SimpleStepper.attach(this, c, "秒",
                new SimpleStepper.Get() { @Override public int get() { return sec[0]; } },
                new SimpleStepper.Set() {
                    @Override public void set(int v) {
                        sec[0] = v; freqDur = (h[0] * 3600L + mnt[0] * 60L + sec[0]) * 1000L; } },
                0, 0, 59, true);

        TextView tip2 = new TextView(this);
        tip2.setText("到时间由板子自己停(App 被杀也会按时停)。三个都填 0 = 不限时长。\n"
                + "💡 中间的数字可以直接点进去输入,也可以用 − / + 调。");
        tip2.setTextColor(0xffffd479);
        tip2.setTextSize(10);
        tip2.setPadding(0, dp(2), 0, dp(4));
        c.addView(tip2);

        return c;
    }

    /**
     * 「当前已设定:…」那一行(放在执行次数下面)。
     *
     * 目的:编辑一个**已经有定时**的流程时,一眼能看到原来定的是什么,
     * 不用凭记忆或退出去看列表。
     */
    private View currentSchedView() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, dp(10), 0, 0);

        View line = new View(this);
        line.setBackgroundColor(C_LINE);
        wrap.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        TextView t = new TextView(this);
        t.setTextSize(12);
        t.setPadding(0, dp(10), 0, 0);

        if (origMode == FlowStore.MODE_OFF) {
            t.setText("当前已设定:没有定时(只在你点「运行」时执行)");
            t.setTextColor(C_DIM);
        } else {
            String when;
            if (origMode == FlowStore.MODE_AT) {
                when = DateTimePick.fmt(origAt);
            } else {
                long h = origDelay / 3600000, mi = (origDelay % 3600000) / 60000,
                     se = (origDelay % 60000) / 1000;
                when = "倒计时 " + h + " 时 " + mi + " 分 " + se + " 秒后";
            }
            String extra = origRepeat > 1 ? " · 执行 " + origRepeat + " 次" : "";
            // 过期判断:指定时刻模式下,已过目标时刻超过 1 分钟
            boolean overdue = origMode == FlowStore.MODE_AT && origAt > 0
                    && System.currentTimeMillis() > origAt + 60_000L;
            t.setText("当前已设定:" + when + extra + (overdue ? "  ⚠️ 已过期" : ""));
            t.setTextColor(0xffd29922);
        }
        wrap.addView(t);
        return wrap;
    }

    private void setMode(int m) {
        mode = m;
        for (int i = 0; i < 3; i++) {
            pages[i].setVisibility(i == m ? View.VISIBLE : View.GONE);
            tabs[i].setTextColor(i == m ? C_ACC : C_DIM);
            tabs[i].setBackgroundColor(i == m ? 0xff1c2128 : C_CARD);
        }
    }

    private void done() {
        Intent r = new Intent();
        r.putExtra(EX_MODE, mode);
        r.putExtra(EX_REPEAT, repeat);
        r.putExtra(EX_FREQ_ON, freqOn);
        r.putExtra(EX_FREQ_MS, freqMs);
        r.putExtra(EX_FREQ_DUR, freqDur);
        if (mode == FlowStore.MODE_AT) {
            long t = DateTimePick.parse(atEd.getText().toString());
            if (t <= 0) { toast("时间格式不对,应为 2026-10-02 12:00:00"); return; }
            r.putExtra(EX_AT, t);
            r.putExtra(EX_DELAY, 0L);
        } else if (mode == FlowStore.MODE_DELAY) {
            long d = (delayH * 3600L + delayM * 60L + delayS) * 1000L;
            if (d <= 0) { toast("倒计时不能为 0"); return; }
            r.putExtra(EX_DELAY, d);
            r.putExtra(EX_AT, 0L);
        }
        setResult(RESULT_OK, r);
        finish();
    }

    private TextView hint(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(C_DIM);
        t.setTextSize(12);
        t.setPadding(0, dp(8), 0, dp(4));
        return t;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
