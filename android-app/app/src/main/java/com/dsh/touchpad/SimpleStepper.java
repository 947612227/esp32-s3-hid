package com.dsh.touchpad;

import android.app.Activity;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 数值步进器(标签 + [−] 值 [+])。
 *
 * 为什么用步进器而不是输入框:板子配对后,系统会弹「是否连入外接键盘」,
 * 键盘输入体验很差。数值类参数一律用 +/- 调整,尽量不唤起键盘。
 */
public class SimpleStepper {

    public interface Get { int get(); }
    public interface Set { void set(int v); }

    private static int dp(Activity a, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                a.getResources().getDisplayMetrics());
    }

    public static void attach(final Activity act, LinearLayout parent, String label,
                              final Get get, final Set set, int def, final int min, final int max) {
        attach(act, parent, label, get, set, def, min, max, false);
    }

    /**
     * @param editable true = 中间的数字**可以直接点进去改**(除了 +/- 之外)
     *
     * 为什么默认 false:板子配对后系统会弹「是否连入外接键盘」,键盘体验差,
     * 所以大多数数值只用 +/- 调。需要精确输入的(比如时长)传 true。
     */
    public static void attach(final Activity act, LinearLayout parent, String label,
                              final Get get, final Set set, int def,
                              final int min, final int max, boolean editable) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(act, 6), 0, dp(act, 6));

        TextView t = new TextView(act);
        t.setText(label);
        t.setTextColor(0xff8b949e);
        t.setTextSize(13);
        t.setWidth(dp(act, 78));
        row.addView(t);

        // 中间那格:editable 时是可输入的 EditText,否则只是展示用的 TextView
        final TextView val;
        final EditText ed;
        if (editable) {
            ed = new EditText(act);
            ed.setInputType(InputType.TYPE_CLASS_NUMBER);
            ed.setTextColor(0xffc9d1d9);
            ed.setTextSize(15);
            ed.setGravity(Gravity.CENTER);
            // 去掉 EditText 默认下划线框,但**留一条淡下划线**提示"这里可以输入"
            android.graphics.drawable.GradientDrawable ul =
                    new android.graphics.drawable.GradientDrawable();
            ul.setColor(0x00000000);
            ul.setStroke(dp(act, 1), 0x3330363d);
            ul.setCornerRadius(dp(act, 4));
            ed.setBackground(ul);
            ed.setPadding(0, dp(act, 2), 0, dp(act, 2));
            ed.setMinHeight(0);
            ed.setMinimumHeight(0);
            ed.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(ed);
            val = ed;   // 同一个对象,统一走 setText
        } else {
            ed = null;
            TextView tv = new TextView(act);
            tv.setTextColor(0xffc9d1d9);
            tv.setTextSize(15);
            tv.setGravity(Gravity.CENTER);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(tv);
            val = tv;
        }

        final Runnable paint = new Runnable() {
            @Override public void run() {
                if (ed != null && ed.hasFocus()) return;   // 正在输入时别覆盖用户打的字
                val.setText(String.valueOf(get.get()));
            }
        };
        paint.run();

        if (ed != null) {
            // 输入完(回车 / 失焦)就取值,并夹到 [min, max]
            final Runnable commit = new Runnable() {
                @Override public void run() {
                    int nv;
                    try { nv = Integer.parseInt(ed.getText().toString().trim()); }
                    catch (Throwable e) { nv = get.get(); }
                    if (nv < min) nv = min;
                    if (nv > max) nv = max;
                    set.set(nv);
                    ed.setText(String.valueOf(nv));
                    ed.clearFocus();
                }
            };
            ed.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                @Override public boolean onEditorAction(TextView v, int action, android.view.KeyEvent e) {
                    if (action == EditorInfo.IME_ACTION_DONE
                            || action == EditorInfo.IME_ACTION_NEXT
                            || (e != null && e.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER)) {
                        commit.run();
                        return true;
                    }
                    return false;
                }
            });
            ed.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean hasFocus) {
                    if (!hasFocus) commit.run();
                }
            });
        }

        Button minus = new Button(act);
        minus.setText("−");
        minus.setTextSize(18);
        minus.setMinimumWidth(0);
        minus.setPadding(0, 0, 0, 0);
        minus.setLayoutParams(new LinearLayout.LayoutParams(dp(act, 54), dp(act, 44)));
        minus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int nv = Math.max(min, get.get() - 1);
                set.set(nv); paint.run();
            }
        });
        row.addView(minus);

        Button plus = new Button(act);
        plus.setText("+");
        plus.setTextSize(18);
        plus.setMinimumWidth(0);
        plus.setPadding(0, 0, 0, 0);
        plus.setLayoutParams(new LinearLayout.LayoutParams(dp(act, 54), dp(act, 44)));
        plus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int nv = Math.min(max, get.get() + 1);
                set.set(nv); paint.run();
            }
        });
        row.addView(plus);

        parent.addView(row);
    }
}
