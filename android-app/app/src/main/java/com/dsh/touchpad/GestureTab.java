package com.dsh.touchpad;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 「手势」页签(坐标板 / 手动坐标 / 两点滑动 / 连点)。
 *
 * 从 MainActivity 拆出来的:这一页只用到通用控件(card/hint/btnRow/stepper)
 * 和 board,跟别的页签没有共享状态。滑动时长、连点次数仍由控制页的
 * stepper 改 MainActivity 里的 swipeMs / repeatTimes,这里只读。
 */
final class GestureTab {
    private final MainActivity a;
    private TextView padInfo;           // 坐标板下面那行"刚才做了什么"

    GestureTab(MainActivity a) { this.a = a; }

    /** 把整页控件加到 content 里(每次切到本页签都重建) */
    void build(LinearLayout content) {
        LinearLayout c = a.card("坐标板:在这里操作 = 在手机屏幕对应位置操作");
        c.addView(a.hint("按下=点击 · 长按=长按 · 拖动=滑动"));

        final View pad = new View(a);
        pad.setBackgroundColor(0xff010409);
        c.addView(pad, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, a.dp(220)));

        padInfo = a.hint("等待操作…");
        c.addView(padInfo);

        pad.setOnTouchListener(makePadListener());
        content.addView(c);

        LinearLayout manual = a.card("手动坐标");
        final EditText ex = new EditText(a);
        ex.setHint("x,如 0.5 或 50%");
        ex.setTextSize(13);
        final EditText ey = new EditText(a);
        ey.setHint("y,如 0.5 或 50%");
        ey.setTextSize(13);
        manual.addView(ex);
        manual.addView(ey);
        manual.addView(a.btnRow(null, new String[][]{
                {"点击", "tap"}, {"长按", "long"}, {"双击", "double"}
        }, new View.OnClickListener() {
            @Override public void onClick(View v) {
                float x = parseCoord(ex.getText().toString(), 0.5f);
                float y = parseCoord(ey.getText().toString(), 0.5f);
                String act = String.valueOf(v.getTag());
                if ("long".equals(act)) a.board.longPress(x, y, a.cb);
                else if ("double".equals(act)) a.board.gesture("double", "a=" + x + "&b=" + y, a.cb);
                else a.board.tap(x, y, a.cb);
                padInfo.setText(String.format("%s %.3f,%.3f", act, x, y));
            }
        }));
        content.addView(manual);

        LinearLayout two = a.card("两点滑动(填起点终点)");
        final EditText[] f = new EditText[4];
        String[] ph = {"起点 x", "起点 y", "终点 x", "终点 y"};
        for (int i = 0; i < 4; i++) {
            f[i] = new EditText(a);
            f[i].setHint(ph[i]);
            f[i].setTextSize(13);
            two.addView(f[i]);
        }
        two.addView(a.btnRow(null, new String[][]{{"滑动", "go"}},
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        float[] v4 = new float[4];
                        for (int i = 0; i < 4; i++) {
                            v4[i] = parseCoord(f[i].getText().toString(), 0.5f);
                        }
                        a.board.swipe(v4[0], v4[1], v4[2], v4[3], a.swipeMs, a.cb);
                        padInfo.setText(String.format("滑动 %.2f,%.2f → %.2f,%.2f",
                                v4[0], v4[1], v4[2], v4[3]));
                    }
                }));
        content.addView(two);

        LinearLayout rep = a.card("连续执行(连点/连滑)");
        rep.addView(a.stepper("次数", new String[]{"repeat"}, 3));
        rep.addView(a.hint("配合上面的「点击」使用:先设次数,再连点"));
        rep.addView(a.btnRow(null, new String[][]{
                {"连点 x N", "multi"}
        }, new View.OnClickListener() {
            @Override public void onClick(View v) {
                final float x = parseCoord(ex.getText().toString(), 0.5f);
                final float y = parseCoord(ey.getText().toString(), 0.5f);
                final int n = a.repeatTimes;
                padInfo.setText("连点 " + n + " 次…");
                for (int i = 0; i < n; i++) {
                    final int k = i;
                    a.ui.postDelayed(new Runnable() {
                        @Override public void run() {
                            a.board.tap(x, y, a.silent);
                            if (k == n - 1) padInfo.setText("连点完成 " + n + " 次");
                        }
                    }, i * 400L);
                }
            }
        }));
        content.addView(rep);
    }

    /** 坐标板触摸:没移动且 <600ms = 点击,≥600ms = 长按,移动过 = 滑动(时长按真实手指时长,80~1500ms) */
    private View.OnTouchListener makePadListener() {
        return new View.OnTouchListener() {
            private float dX, dY;       // 按下点(0~1 归一化)
            private long dT;            // 按下时刻
            private boolean moved;      // 是否拖动超过 2%

            @Override public boolean onTouch(View v, MotionEvent e) {
                float w = v.getWidth(), h = v.getHeight();
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dX = e.getX() / w;
                        dY = e.getY() / h;
                        dT = System.currentTimeMillis();
                        moved = false;
                        padInfo.setText(String.format("按下 %.3f,%.3f", dX, dY));
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(e.getX() / w - dX) > 0.02f
                                || Math.abs(e.getY() / h - dY) > 0.02f) {
                            moved = true;
                            padInfo.setText(String.format("拖到 %.3f,%.3f",
                                    e.getX() / w, e.getY() / h));
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        float ux = e.getX() / w, uy = e.getY() / h;
                        long dt = System.currentTimeMillis() - dT;
                        if (moved) {
                            a.board.swipe(dX, dY, ux, uy, (int) Math.min(Math.max(dt, 80), 1500), a.cb);
                            padInfo.setText(String.format("滑动 %.2f,%.2f → %.2f,%.2f",
                                    dX, dY, ux, uy));
                        } else if (dt >= 600) {
                            a.board.longPress(ux, uy, a.cb);
                            padInfo.setText(String.format("长按 %.3f,%.3f", ux, uy));
                        } else {
                            a.board.tap(ux, uy, a.cb);
                            padInfo.setText(String.format("点击 %.3f,%.3f", ux, uy));
                        }
                        return true;
                }
                return false;
            }
        };
    }

    /** 解析坐标输入:"50%" / 0~1 小数 / >1 视为 HID 原始值(÷32767);解析失败用 def */
    static float parseCoord(String s, float def) {
        if (s == null) return def;
        s = s.trim();
        if (s.isEmpty()) return def;
        try {
            if (s.endsWith("%")) return Float.parseFloat(s.substring(0, s.length() - 1)) / 100f;
            float v = Float.parseFloat(s);
            if (v > 1f) v /= 32767f;
            return Math.max(0f, Math.min(1f, v));
        } catch (Exception e) { return def; }
    }
}
