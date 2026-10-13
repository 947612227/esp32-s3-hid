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
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * 指令参数编辑(可视化)。
 *
 * 每个参数一个滑块,底部**实时预览**将会写入编辑框的那一行。
 * 这一步的目的是:不用记参数顺序,也不用会算坐标 —— 拖着调就行。
 *
 * goto 这种没有数值参数的指令,退化成输入框。
 */
public class CmdParamActivity extends Activity {

    public static final String EX_LETTER = "letter";
    public static final String EX_LINE   = "line";
    /** 传入已有的一行(编辑时用),会用它初始化滑块 */
    public static final String EX_INIT   = "init";

    private static final int C_BG   = 0xff0d1117;
    private static final int C_CARD = 0xff161b22;
    private static final int C_FG   = 0xffc9d1d9;
    private static final int C_DIM  = 0xff8b949e;
    private static final int C_ACC  = 0xff58a6ff;
    private static final int C_WARN = 0xffffd479;

    private CmdLib.Cmd cmd;
    private float[] vals;
    private TextView preview;
    private EditText labelEd;      // 仅 goto 用
    private String labelInit = "循环";
    private EditText noteEd;          // 备注(可选)
    private String noteInit = "";
    /** 每个参数的滑块 / 数值 / 刷新动作,用于"填入拾取坐标后原地刷新" */
    private final java.util.List<SeekBar> sliders = new java.util.ArrayList<SeekBar>();
    private final java.util.List<Runnable> painters = new java.util.ArrayList<Runnable>();
    private final java.util.List<CmdLib.Param> pList = new java.util.ArrayList<CmdLib.Param>();
    private final java.util.List<Integer> pIndex = new java.util.ArrayList<Integer>();

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String letter = getIntent().getStringExtra(EX_LETTER);
        String init = getIntent().getStringExtra(EX_INIT);

        // 优先用传入的已有行来初始化(编辑模式);否则用指令默认值(新增模式)
        CmdLib.Parsed p0 = (init != null && !init.isEmpty()) ? CmdLib.parse(init) : null;
        if (p0 != null) {
            cmd = p0.cmd;
            vals = p0.vals;
        } else {
            cmd = CmdLib.find(letter);
            if (cmd == null) { finish(); return; }
            vals = CmdLib.defaults(cmd);
        }
        labelInit = (p0 != null && p0.label != null) ? p0.label : "循环";
        noteInit = (p0 != null && p0.note != null) ? p0.note : "";

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
            @Override public void onClick(View v) { finish(); }
        });
        bar.addView(back);

        TextView title = new TextView(this);
        title.setText(cmd.name + "(" + cmd.letter + ")"
                + ((init != null && !init.isEmpty()) ? " · 编辑" : ""));
        title.setTextColor(C_FG);
        title.setTextSize(16);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(title);

        Button ins = new Button(this);
        ins.setText("插入");
        ins.setTextSize(14);
        ins.setAllCaps(false);
        ins.setMinimumWidth(0);
        ins.setPadding(dp(16), 0, dp(16), 0);
        ins.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        ins.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doInsert(); }
        });
        bar.addView(ins);
        root.addView(bar);

        ScrollView sc = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(24));
        sc.addView(box);

        // 说明
        TextView what = new TextView(this);
        what.setText(cmd.what);
        what.setTextColor(C_FG);
        what.setTextSize(14);
        box.addView(what);

        TextView usage = new TextView(this);
        usage.setText("写法:" + cmd.usage);
        usage.setTextColor(C_WARN);
        usage.setTextSize(12);
        usage.setPadding(0, dp(4), 0, dp(4));
        box.addView(usage);

        if (cmd.detail != null && !cmd.detail.isEmpty()) {
            TextView det = new TextView(this);
            det.setText(cmd.detail);
            det.setTextColor(C_DIM);
            det.setTextSize(11);
            det.setLineSpacing(dp(2), 1f);
            box.addView(det);
        }

        // 参数
        if (cmd.params.length == 0) {
            // 只有 goto 会走到这里(要一个标号)
            TextView lb = new TextView(this);
            lb.setText("标号(在流程里用「#标号」单独写一行定义)");
            lb.setTextColor(C_DIM);
            lb.setTextSize(12);
            lb.setPadding(0, dp(14), 0, dp(4));
            box.addView(lb);
            labelEd = new EditText(this);
            labelEd.setText(labelInit);
            box.addView(labelEd);
        } else {
            for (int i = 0; i < cmd.params.length; i++) {
                box.addView(makeParamRow(i));
            }
        }

        // ---- 备注(可选)----
        TextView nl = new TextView(this);
        nl.setText("备注(可选)—— 写这一步在干什么,日志会优先显示它");
        nl.setTextColor(C_DIM);
        nl.setTextSize(12);
        nl.setPadding(0, dp(16), 0, dp(4));
        box.addView(nl);
        noteEd = new EditText(this);
        noteEd.setHint("例:点我的 / 上滑列表 / 等页面加载");
        noteEd.setText(noteInit);
        noteEd.setSingleLine();
        noteEd.setTextSize(14);
        box.addView(noteEd);
        noteEd.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) { updatePreview(); }
        });

        // ---- 用「最近拾取」的坐标(来自悬浮窗的 📐 按钮)----
        addPickShortcuts(box);

        // 实时预览
        TextView pl = new TextView(this);
        pl.setText("将要插入的这一行:");
        pl.setTextColor(C_DIM);
        pl.setTextSize(12);
        pl.setPadding(0, dp(18), 0, dp(4));
        box.addView(pl);

        preview = new TextView(this);
        preview.setTextColor(C_ACC);
        preview.setTextSize(15);
        preview.setTypeface(android.graphics.Typeface.MONOSPACE);
        preview.setBackgroundColor(C_CARD);
        preview.setPadding(dp(12), dp(12), dp(12), dp(12));
        box.addView(preview);

        // 复制按钮放在**这里**(App 前台)—— 悬浮窗里那次复制属于后台,会被系统拦
        Button copy = new Button(this);
        copy.setText("📋 复制这一行");
        copy.setTextSize(13);
        copy.setAllCaps(false);
        LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        clp2.topMargin = dp(6);
        copy.setLayoutParams(clp2);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String txt = preview.getText().toString();
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager)
                                getSystemService(CLIPBOARD_SERVICE);
                if (cm == null) { toast("剪切板不可用"); return; }
                cm.setPrimaryClip(android.content.ClipData.newPlainText("指令", txt));
                toast("已复制:" + txt);
            }
        });
        box.addView(copy);

        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        updatePreview();
    }

    /**
     * 如果这条指令有坐标参数,而悬浮窗又拾取过坐标,
     * 就在这里给出「一键填入」的按钮 —— 省得手动敲数字。
     *
     * 约定:第 1 个拾取点填第一对坐标,第 2 个填第二对,以此类推。
     */
    private void addPickShortcuts(LinearLayout box) {
        java.util.List<float[]> hist = CoordPicker.history(this);
        if (hist.isEmpty()) return;

        // 找出"连续的坐标参数"分成几对
        java.util.List<int[]> pairs = new java.util.ArrayList<int[]>();
        int i = 0;
        while (i < cmd.params.length) {
            if (cmd.params[i].kind == CmdLib.KIND_COORD
                    && i + 1 < cmd.params.length
                    && cmd.params[i + 1].kind == CmdLib.KIND_COORD) {
                pairs.add(new int[]{i, i + 1});
                i += 2;
            } else {
                i++;
            }
        }
        if (pairs.isEmpty()) return;

        TextView lb = new TextView(this);
        lb.setText("从悬浮窗 📐 拾取到的坐标(点=填入,长按=只复制)");
        lb.setTextColor(C_DIM);
        lb.setTextSize(12);
        lb.setPadding(0, dp(16), 0, dp(4));
        box.addView(lb);

        int n = Math.min(pairs.size(), hist.size());
        for (int k = 0; k < n; k++) {
            final int[] pair = pairs.get(k);
            final float[] pt = hist.get(k);

            Button b = new Button(this);
            b.setText("📍 第" + (k + 1) + "个点:" + CoordPicker.fmt(pt[0], pt[1])
                    + "  (" + CoordPicker.fmtPct(pt[0], pt[1]) + ")");
            b.setTextSize(12);
            b.setAllCaps(false);
            b.setMinimumWidth(0);
            b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            b.setPadding(dp(10), 0, dp(10), 0);
            b.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    vals[pair[0]] = pt[0];
                    vals[pair[1]] = pt[1];
                    rebuild();          // 重建界面让滑块跟上新值
                    toast("已填入:" + CoordPicker.fmt(pt[0], pt[1]));
                }
            });
            // 长按 = 只复制这个坐标(不填)
            b.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager)
                                    getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                "坐标", CoordPicker.fmt(pt[0], pt[1])));
                        toast("已复制坐标:" + CoordPicker.fmt(pt[0], pt[1]));
                    }
                    return true;
                }
            });
            LinearLayout.LayoutParams lp =
                    (LinearLayout.LayoutParams) b.getLayoutParams();
            lp.bottomMargin = dp(4);
            box.addView(b);
        }

        Button clr = new Button(this);
        clr.setText("清空拾取记录");
        clr.setTextSize(11);
        clr.setAllCaps(false);
        clr.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)));
        clr.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                CoordPicker.clearHistory();
                rebuild();
            }
        });
        box.addView(clr);
    }

    /**
     * 参数被外部改过之后,**原地**刷新滑块位置。
     *
     * ⚠️ 不要用"finish + 重启 Activity"的偷懒办法 —— 重启会重新读 Intent,
     *    而 Intent 里没有新值,参数会被重置回默认(实测踩过)。
     */
    private void rebuild() {
        for (int i = 0; i < sliders.size(); i++) {
            CmdLib.Param p = pList.get(i);
            int idx = pIndex.get(i);
            int max = (p.kind == CmdLib.KIND_COORD || p.kind == CmdLib.KIND_GAP)
                    ? 1000 : (int) p.max;
            SeekBar sb = sliders.get(i);
            sb.setMax(Math.max(1, max));
            sb.setProgress((int) (vals[idx] / (p.max == 0 ? 1 : p.max) * max));
            Runnable r = painters.get(i);
            if (r != null) r.run();
        }
        updatePreview();
    }

    /** 一个参数 = 一行:名称 + 当前值 + 滑块 */
    private View makeParamRow(final int idx) {
        final CmdLib.Param p = cmd.params[idx];

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(12), 0, 0);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);

        TextView nm = new TextView(this);
        // 单位挂在**标签**后面;数值那边就不再重复了
        nm.setText(p.unit.isEmpty() ? p.name : (p.name + " " + p.unit));
        nm.setTextColor(C_DIM);
        nm.setTextSize(13);
        nm.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(nm);

        final TextView val = new TextView(this);
        val.setTextColor(C_FG);
        val.setTextSize(14);
        head.addView(val);
        row.addView(head);

        final SeekBar sb = new SeekBar(this);
        // 坐标类用 0~1000 的整数刻度,显示时换算成 0.000~1.000
        int max = (p.kind == CmdLib.KIND_COORD || p.kind == CmdLib.KIND_GAP)
                ? 1000 : (int) p.max;
        sb.setMax(Math.max(1, max));
        final float scale = (p.kind == CmdLib.KIND_COORD || p.kind == CmdLib.KIND_GAP)
                ? 1000f : 1f;
        sb.setProgress((int) (vals[idx] / (p.max == 0 ? 1 : p.max) * max));
        row.addView(sb);

        final Runnable paint = new Runnable() {
            @Override public void run() {
                if (p.kind == CmdLib.KIND_COORD || p.kind == CmdLib.KIND_GAP) {
                    val.setText(String.format(Locale.US, "%.3f", vals[idx]));
                } else {
                    // 只显示数字 —— 单位在上面的标签里
                    val.setText(String.valueOf((int) vals[idx]));
                }
            }
        };
        paint.run();

        sliders.add(sb);
        painters.add(paint);
        pList.add(p);
        pIndex.add(idx);

        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int prog, boolean fromUser) {
                vals[idx] = prog / scale * p.max;
                paint.run();
                updatePreview();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        return row;
    }

    private void updatePreview() {
        if (preview == null) return;
        String note = (noteEd == null) ? "" : noteEd.getText().toString().trim();
        if (cmd.params.length == 0) {
            String lb = (labelEd == null) ? "" : labelEd.getText().toString().trim();
            preview.setText(cmd.letter + " " + lb + (note.isEmpty() ? "" : " " + note));
        } else {
            preview.setText(CmdLib.build(cmd, vals, note));
        }
    }

    private void doInsert() {
        String line;
        if (cmd.params.length == 0) {
            String lb = (labelEd == null) ? "" : labelEd.getText().toString().trim();
            if (lb.isEmpty()) { toast("请填标号"); return; }
            line = cmd.letter + " " + lb;
        } else {
            String note = (noteEd == null) ? "" : noteEd.getText().toString().trim();
            line = CmdLib.build(cmd, vals, note);
        }
        setResult(RESULT_OK, new Intent().putExtra(EX_LINE, line));
        finish();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
