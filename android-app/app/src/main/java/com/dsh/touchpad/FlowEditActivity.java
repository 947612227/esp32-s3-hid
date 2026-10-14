package com.dsh.touchpad;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * 流程编辑(独立全屏页面)。
 *
 * 为什么不做成弹窗:
 *   实测在 AlertDialog 里,只要输入法弹出过,**弹窗上的所有点击都会失效**
 *   (连"取消"都点不动,按钮和步进器全没反应)。
 *   换成独立 Activity 后彻底没这个问题,而且编辑多行流程内容本来就该用整屏。
 */
public class FlowEditActivity extends Activity {

    public static final String EX_INDEX = "index";      // -1 = 新建
    public static final String EX_NAME  = "name";       // 已有流程名 / 新建时的默认名

    private static final int C_BG   = 0xff0d1117;
    private static final int C_CARD = 0xff161b22;
    private static final int C_LINE = 0xff30363d;
    private static final int C_FG   = 0xffc9d1d9;
    private static final int C_DIM  = 0xff8b949e;
    private static final int C_ACC  = 0xff58a6ff;
    private static final int C_WARN = 0xffffd479;

    private final Board board = new Board();
    private FlowStore flows;
    private FlowStore.Meta draft;
    private int index = -1;
    private String origName = "";
    private boolean isNew = true;

    private EditText nameEd, bodyEd;
    private LinearLayout stepBox;                 // 可视化步骤容器
    private final java.util.List<String> steps = new java.util.ArrayList<String>();
    private boolean visualMode = false;
    private static final int REQ_CMD_ADD = 3003;
    private static final int REQ_CMD_EDIT = 3004;
    private TextView schedInfo;

    // ---- 键盘弹出时要"让位"的元素 ----
    // 这些是**参考信息**(坐标写法提示、备注说明、定时设置),
    // 输入的时候用不到,但它们是固定高度、不会自动缩 ——
    // 键盘一弹编辑框就被挤成一条缝(用户实测反馈)。
    // 所以键盘出现时把它们收起来,编辑框拿回空间。
    private TextView coordTip, noteTipView, newFlowTip;
    private LinearLayout schedRow;
    private boolean kbShown = false;

    private int sp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                getResources().getDisplayMetrics());
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        SharedPreferences sp = getSharedPreferences("board", MODE_PRIVATE);
        board.setHost(sp.getString("host", ""));
        flows = new FlowStore(this);

        Intent it = getIntent();
        index = it.getIntExtra(EX_INDEX, -1);
        origName = it.getStringExtra(EX_NAME);
        if (origName == null) origName = "";
        isNew = index < 0;

        draft = isNew ? new FlowStore.Meta() : flows.get(origName);
        if (isNew) {
            draft.name = origName;
            draft.createdAt = System.currentTimeMillis();   // 新建的排最前用
            draft.appName = sp.getString("appName", "");
            draft.appPkg = sp.getString("appPkg", "");
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        // ---- 标题栏:返回 / 标题 / 保存 ----
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
        title.setText(isNew ? "新建流程" : "编辑流程");
        title.setTextColor(C_FG);
        title.setTextSize(16);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(title);

        Button save = new Button(this);
        save.setText("保存");
        save.setTextSize(14);
        save.setAllCaps(false);
        save.setMinimumWidth(0);
        save.setPadding(dp(16), 0, dp(16), 0);
        save.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doSave(); }
        });
        bar.addView(save);
        root.addView(bar);

        // ---- 内容区 ----
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));

        box.addView(label("流程名(默认取目标 App 名字,可改)"));
        nameEd = new EditText(this);
        nameEd.setText(draft.name);
        nameEd.setSingleLine();
        nameEd.setTextSize(15);
        box.addView(nameEd);

        // ================= 流程内容:可视化 / 文本 双模式 =================
        final LinearLayout modeBar = new LinearLayout(this);
        modeBar.setOrientation(LinearLayout.HORIZONTAL);
        modeBar.setGravity(Gravity.CENTER_VERTICAL);

        TextView bl = label("流程内容");
        bl.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        modeBar.addView(bl);

        final Button bVisual = new Button(this);
        bVisual.setText("☰ 可视化");
        final Button bText = new Button(this);
        bText.setText("✎ 文本");
        for (Button mb : new Button[]{bVisual, bText}) {
            mb.setTextSize(12);
            mb.setAllCaps(false);
            mb.setMinimumWidth(0);
            mb.setPadding(dp(10), 0, dp(10), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
            lp.leftMargin = dp(4);
            mb.setLayoutParams(lp);
            modeBar.addView(mb);
        }
        box.addView(modeBar);

        // ---- 可视化面板 ----
        final LinearLayout visualPanel = new LinearLayout(this);
        visualPanel.setOrientation(LinearLayout.VERTICAL);

        stepBox = new LinearLayout(this);
        stepBox.setOrientation(LinearLayout.VERTICAL);
        ScrollView stepScroll = new ScrollView(this);
        stepScroll.addView(stepBox);
        visualPanel.addView(stepScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button addStep = new Button(this);
        addStep.setText("＋ 添加步骤");
        addStep.setTextSize(13);
        addStep.setAllCaps(false);
        addStep.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        addStep.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivityForResult(new Intent(FlowEditActivity.this,
                        CmdPickerActivity.class), REQ_CMD_ADD);
            }
        });
        visualPanel.addView(addStep);

        // ---- 文本面板(保留原来的文本框)----
        final LinearLayout textPanel = new LinearLayout(this);
        textPanel.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bodyHead = new LinearLayout(this);
        bodyHead.setOrientation(LinearLayout.HORIZONTAL);
        bodyHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView bh = label("每行一个动作。不会写就点「指令库」");
        bh.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bodyHead.addView(bh);

        Button pick = new Button(this);
        pick.setText("⛭ 指令库");
        pick.setTextSize(12);
        pick.setAllCaps(false);
        pick.setMinimumWidth(0);
        pick.setPadding(dp(10), 0, dp(10), 0);
        pick.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
        pick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivityForResult(new Intent(FlowEditActivity.this,
                        CmdPickerActivity.class), REQ_CMD);
            }
        });
        bodyHead.addView(pick);
        textPanel.addView(bodyHead);

        // ---- 指令快捷条:自动折成**多行**,不用左右滑 ----
        // 用户反馈"横向滑动不好用,要两行或多行"。
        // 这里按屏幕宽度估算每行能放几个,放不下就换行。
        //
        // 点一下**只插入指令本身**(如 "T "),不预填参数值 ——
        // 坐标这类值用户会用拾取器或自己敲,预填了反而要删。
        LinearLayout chipBox = new LinearLayout(this);
        chipBox.setOrientation(LinearLayout.VERTICAL);
        chipBox.setPadding(0, dp(4), 0, 0);

        // 量文字宽度(字号和 Button 一致)
        android.graphics.Paint pm = new android.graphics.Paint();
        pm.setTextSize(sp(11));
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int maxRowW = screenW - dp(44);          // 留出左右 padding

        LinearLayout rowNow = null;
        int rowUsed = 0;
        for (final CmdLib.Cmd cc : CmdLib.all()) {
            String label = cc.letter + " " + cc.name;
            int w = (int) pm.measureText(label) + dp(24);   // 估算按钮宽度

            if (rowNow == null || (rowUsed > 0 && rowUsed + w > maxRowW)) {
                rowNow = new LinearLayout(this);
                rowNow.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = dp(4);
                rowNow.setLayoutParams(rlp);
                chipBox.addView(rowNow);
                rowUsed = 0;
            }

            Button chip = new Button(this);
            chip.setText(label);
            chip.setTextSize(11);
            chip.setAllCaps(false);
            chip.setMinimumWidth(0);
            chip.setMinimumHeight(0);
            chip.setPadding(dp(8), 0, dp(8), 0);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
            clp.rightMargin = dp(4);
            chip.setLayoutParams(clp);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    insertLine(CmdLib.template(cc));
                }
            });
            rowNow.addView(chip);
            rowUsed += w + dp(4);
        }
        textPanel.addView(chipBox);

        bodyEd = new EditText(this);
        // 占位提示:一行就够 —— 原来写三行示例,看着像已经填好的内容,
        // 用户会以为"默认文本删不掉"。而且**聚焦时直接隐藏**,点进去就干净了。
        final String BODY_HINT = "例:T 0.5 0.5 60 点我的票   ← 参数后面的中文就是备注";
        bodyEd.setHint(BODY_HINT);
        bodyEd.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                bodyEd.setHint(has ? "" : BODY_HINT);
            }
        });
        bodyEd.setGravity(Gravity.TOP | Gravity.START);
        bodyEd.setTextSize(13);
        // 键盘弹出时至少留这么高 —— 否则被 weight 挤成一条缝
        bodyEd.setMinHeight(dp(150));
        bodyEd.setTypeface(android.graphics.Typeface.MONOSPACE);
        textPanel.addView(bodyEd, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        coordTip = new TextView(this);
        TextView tip = coordTip;
        tip.setText("坐标:0.5 或 50% 或 16384 · 「#名字」定义一个标号 · 行首 # 是注释");
        tip.setTextColor(C_DIM);
        tip.setTextSize(10);
        tip.setPadding(0, dp(4), 0, 0);
        textPanel.addView(tip);

        // ---- 备注怎么加 ----
        // 用户反馈"新建流程的备注怎么加",说明这个功能**没有入口提示** ——
        // 功能一直是好的(参数后面写中文就行),但没人知道。这里给个显眼的说明。
        noteTipView = new TextView(this);
        TextView noteTip = noteTipView;
        noteTip.setText("💬 备注:在参数后面直接写中文,例 T 0.5 0.5 60 点我的票"
                + "  ← 点这里看详细说明");
        noteTip.setTextColor(0xffffd479);
        noteTip.setTextSize(11);
        noteTip.setPadding(0, dp(8), 0, dp(4));
        noteTip.setLineSpacing(0, 1.3f);
        noteTip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Dialogs.info(FlowEditActivity.this, "备注怎么加",
                        "【两种写法,效果一样】\n\n"
                        + "① 直接在下面框里写:\n"
                        + "     T 0.5 0.5 60 点我的票\n"
                        + "     wait 3000 等页面加载\n\n"
                        + "② 用「⛭ 指令库」:\n"
                        + "     点一条指令 → 滑到底下的「备注(可选)」输入框 → 插入\n\n"
                        + "【规则】\n"
                        + "参数后面第一个不是数字的词开始,后面全算备注。\n"
                        + "所以 T 0.5 0.5 点我的票(省略 ms)也认。\n\n"
                        + "【有什么用】\n"
                        + "日志从「点击 0.500 0.500」变成「点击:点我的票」,\n"
                        + "过几天回来看还知道这一步在干什么。\n"
                        + "编辑流程时备注也不会丢。\n\n"
                        + "【不写也行】\n"
                        + "不写就显示坐标,功能完全一样。");
            }
        });
        textPanel.addView(noteTip);

        // 两个面板放进容器,靠可见性切换
        box.addView(visualPanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        box.addView(textPanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        bVisual.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 切到可视化:先把文本框内容拆成步骤
                loadStepsFromText();
                visualPanel.setVisibility(View.VISIBLE);
                textPanel.setVisibility(View.GONE);
                renderSteps();
                paintMode(bVisual, bText, true);
            }
        });
        bText.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 切到文本:把步骤拼回文本
                applyStepsToText();
                visualPanel.setVisibility(View.GONE);
                textPanel.setVisibility(View.VISIBLE);
                paintMode(bVisual, bText, false);
            }
        });

        visualPanel.setVisibility(View.GONE);
        textPanel.setVisibility(View.VISIBLE);
        paintMode(bVisual, bText, false);

        // ---- 定时 ----
        schedInfo = new TextView(this);
        schedInfo.setTextSize(12);
        schedInfo.setPadding(0, dp(4), 0, dp(6));
        box.addView(schedInfo);
        paintSched();

        schedRow = new LinearLayout(this);
        LinearLayout row = schedRow;
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button setSched = new Button(this);
        setSched.setText("设置定时");
        setSched.setTextSize(14);
        setSched.setAllCaps(false);
        setSched.setLayoutParams(new LinearLayout.LayoutParams(0, dp(46), 1f));
        setSched.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openSchedule(); }
        });
        row.addView(setSched);
        box.addView(row);

        if (isNew) {
            newFlowTip = new TextView(this);
            TextView nt = newFlowTip;
            nt.setText("新建的流程默认是「暂停」状态,保存后到流程列表里手动开启。");
            nt.setTextColor(C_WARN);
            nt.setTextSize(11);
            nt.setPadding(0, dp(8), 0, 0);
            box.addView(nt);
        }
        root.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ================= 键盘弹出时给编辑框让位 =================
        // 为什么需要:参考信息(坐标写法 / 备注说明 / 定时设置)是固定高度的,
        // 键盘一弹就把编辑框挤成 2~3 行(用户实测反馈)。
        // 这里检测键盘高度,出现时把这些收起来,让编辑框拿回空间。
        watchKeyboard(root);

        setContentView(root);

        // 编辑页把超时放宽一点 —— 板子可能在跑流程/正忙,
        // 6 秒偶尔不够(它是单线程 WebServer)。
        board.setTimeout(12000);

        if (!isNew) loadContent();
    }

    /**
     * 监听键盘高度,弹出时把「参考信息」收起来。
     *
     * 判定方式:root 的可见高度明显小于屏幕高度 → 键盘占了下面那块。
     * 这只是个**布局优化**,判错了也没关系(不影响功能)。
     */
    private void watchKeyboard(final LinearLayout root) {
        root.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                try {
                    int h = root.getHeight();
                    if (h <= 0) return;
                    android.graphics.Rect r = new android.graphics.Rect();
                    root.getWindowVisibleDisplayFrame(r);
                    int screenH = root.getRootView().getHeight();
                    int hidden = screenH - r.bottom;
                    // 键盘一般占屏幕 20% 以上;状态栏/导航栏只有几十 px
                    boolean kb = hidden > screenH * 0.18;
                    if (kb != kbShown) {
                        kbShown = kb;
                        applyKbLayout(kb);
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    /** 键盘出现 → 收起参考信息;收起 → 恢复 */
    private void applyKbLayout(boolean kb) {
        int v = kb ? View.GONE : View.VISIBLE;
        if (coordTip   != null) coordTip.setVisibility(v);
        if (noteTipView != null) noteTipView.setVisibility(v);
        if (schedInfo  != null) schedInfo.setVisibility(v);
        if (schedRow   != null) schedRow.setVisibility(v);
        if (newFlowTip != null) newFlowTip.setVisibility(v);
    }

    private static final int REQ_CMD = 3002;

    // ================================================================ 可视化模式

    /** 两个模式按钮的高亮 */
    private void paintMode(Button bv, Button bt, boolean visual) {
        visualMode = visual;
        bv.setBackgroundColor(visual ? 0xff1f6feb : 0xff21262d);
        bt.setBackgroundColor(visual ? 0xff21262d : 0xff1f6feb);
        bv.setTextColor(0xffffffff);
        bt.setTextColor(0xffffffff);
    }

    /** 文本框 → 步骤列表 */
    private void loadStepsFromText() {
        steps.clear();
        String t = bodyEd.getText().toString();
        for (String line : t.split("\n")) {
            String x = line.trim();
            if (x.isEmpty()) continue;
            steps.add(x);
        }
    }

    /** 步骤列表 → 文本框 */
    private void applyStepsToText() {
        StringBuilder sb = new StringBuilder();
        for (String x : steps) sb.append(x).append('\n');
        bodyEd.setText(sb.toString().trim());
    }

    /** 把一行中文描述出来,给卡片副标题用 */
    private String describe(String line) {
        if (line.startsWith("#")) return line.length() > 1 ? "标号 / 注释" : "";
        CmdLib.Parsed p = CmdLib.parse(line);
        if (p == null) return "无法识别的写法(仍会原样保存)";
        return CmdLib.summary(p.cmd, p.vals, p.label, p.note);
    }

    /** 一行开头显示的中文名 */
    private String titleOf(String line) {
        if (line.startsWith("#")) return "标号";
        CmdLib.Parsed p = CmdLib.parse(line);
        if (p == null) return "未知指令";
        return p.cmd.name;
    }

    /** 徽标文字 */
    private String badgeOf(String line) {
        if (line.startsWith("#")) return "#";
        CmdLib.Parsed p = CmdLib.parse(line);
        return p == null ? "?" : p.cmd.letter;
    }

    /** 渲染可视化步骤列表 */
    private void renderSteps() {
        if (stepBox == null) return;
        stepBox.removeAllViews();

        if (steps.isEmpty()) {
            TextView e = new TextView(this);
            e.setText("还没有步骤。点下面的「＋ 添加步骤」开始编排。");
            e.setTextColor(C_DIM);
            e.setTextSize(13);
            e.setPadding(dp(4), dp(20), 0, 0);
            stepBox.addView(e);
            return;
        }

        for (int i = 0; i < steps.size(); i++) {
            stepBox.addView(makeStepCard(i));
        }
    }

    /** 一个步骤卡片:序号 + 徽标 + 名称 + 参数摘要 + 上下移/编辑/删除 */
    private View makeStepCard(final int idx) {
        final String line = steps.get(idx);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(0xff161b22);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(8);
        card.setLayoutParams(clp);

        // 第一行:序号 + 徽标 + 名称
        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        r1.setGravity(Gravity.CENTER_VERTICAL);

        TextView no = new TextView(this);
        no.setText((idx + 1) + ".");
        no.setTextColor(C_DIM);
        no.setTextSize(12);
        no.setPadding(0, 0, dp(6), 0);
        r1.addView(no);

        TextView badge = new TextView(this);
        badge.setText(badgeOf(line));
        badge.setTextColor(0xffffffff);
        badge.setTextSize(13);
        badge.setGravity(Gravity.CENTER);
        badge.setBackgroundColor(0xff1f6feb);
        badge.setPadding(dp(8), dp(1), dp(8), dp(1));
        r1.addView(badge);

        TextView nm = new TextView(this);
        nm.setText("  " + titleOf(line));
        nm.setTextColor(C_FG);
        nm.setTextSize(15);
        nm.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r1.addView(nm);
        card.addView(r1);

        // 第二行:参数摘要
        TextView sum = new TextView(this);
        sum.setText(describe(line));
        sum.setTextColor(C_WARN);
        sum.setTextSize(12);
        sum.setPadding(dp(24), dp(4), 0, dp(2));
        card.addView(sum);

        // 第三行:原始那一行(等宽字体,方便对照)
        TextView raw = new TextView(this);
        raw.setText(line);
        raw.setTextColor(C_DIM);
        raw.setTextSize(11);
        raw.setTypeface(android.graphics.Typeface.MONOSPACE);
        raw.setPadding(dp(24), 0, 0, dp(6));
        card.addView(raw);

        // 第四行:操作
        LinearLayout ops = new LinearLayout(this);
        ops.setOrientation(LinearLayout.HORIZONTAL);

        ops.addView(opButton("↑", idx > 0, new View.OnClickListener() {
            @Override public void onClick(View v) { moveStep(idx, -1); }
        }));
        ops.addView(opButton("↓", idx < steps.size() - 1, new View.OnClickListener() {
            @Override public void onClick(View v) { moveStep(idx, 1); }
        }));
        ops.addView(opButton("✎ 编辑", true, new View.OnClickListener() {
            @Override public void onClick(View v) { editStep(idx); }
        }));
        ops.addView(opButton("✕ 删除", true, new View.OnClickListener() {
            @Override public void onClick(View v) {
                steps.remove(idx);
                renderSteps();
            }
        }));
        card.addView(ops);
        return card;
    }

    private Button opButton(String text, boolean enabled, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setMinimumWidth(0);
        b.setEnabled(enabled);
        b.setAlpha(enabled ? 1f : 0.35f);
        b.setPadding(dp(4), 0, dp(4), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        lp.rightMargin = dp(4);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private void moveStep(int idx, int delta) {
        int t = idx + delta;
        if (t < 0 || t >= steps.size()) return;
        String s = steps.remove(idx);
        steps.add(t, s);
        renderSteps();
    }

    /** 编辑某个步骤:把那一行交给参数页,拖滑块改 */
    private int editingStepIdx = -1;

    private void editStep(int idx) {
        editingStepIdx = idx;
        String line = steps.get(idx);
        CmdLib.Parsed p = CmdLib.parse(line);
        if (p == null) {
            toast("这一行无法解析(可能是注释或未知指令),请用文本模式改");
            return;
        }
        startActivityForResult(new Intent(this, CmdParamActivity.class)
                .putExtra(CmdParamActivity.EX_LETTER, p.cmd.letter)
                .putExtra(CmdParamActivity.EX_INIT, line), REQ_CMD_EDIT);
    }

    /** 把一行指令插到光标处(没有光标就追加到末尾) */
    private void insertLine(String line) {
        if (line == null || line.isEmpty()) return;
        android.text.Editable e = bodyEd.getText();
        int start = Math.max(0, bodyEd.getSelectionStart());
        int end = Math.max(0, bodyEd.getSelectionEnd());
        if (start > end) { int t = start; start = end; end = t; }
        if (start > e.length()) start = e.length();
        if (end > e.length()) end = e.length();

        // 自动补换行,避免粘在一起
        String pre = "";
        if (start > 0 && e.charAt(start - 1) != '\n') pre = "\n";
        String post = (end < e.length() && e.charAt(end) != '\n') ? "\n" : "";

        e.replace(start, end, pre + line + post);
        int pos = start + pre.length() + line.length();
        bodyEd.setSelection(Math.min(pos, bodyEd.getText().length()));
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_CMD && res == RESULT_OK && data != null) {
            String line = data.getStringExtra(CmdPickerActivity.EX_LINE);
            if (line != null && !line.isEmpty()) {
                insertLine(line);
                toast("已插入:" + line);
            }
        } else if (req == REQ_CMD_ADD && res == RESULT_OK && data != null) {
            String line = data.getStringExtra(CmdPickerActivity.EX_LINE);
            if (line != null && !line.isEmpty()) {
                steps.add(line);
                renderSteps();
                toast("已添加:" + line);
            }
        } else if (req == REQ_CMD_EDIT && res == RESULT_OK && data != null) {
            String line = data.getStringExtra(CmdPickerActivity.EX_LINE);
            if (line != null && !line.isEmpty()) {
                int i = editingStepIdx;
                if (i >= 0 && i < steps.size()) {
                    steps.set(i, line);
                    renderSteps();
                    toast("已更新:" + line);
                }
            }
        } else if (req == REQ_SCHED && res == RESULT_OK && data != null) {
            // 定时结果(原有逻辑)
            draft.schedMode = data.getIntExtra(ScheduleActivity.EX_MODE, FlowStore.MODE_OFF);
            draft.repeatCount = data.getIntExtra(ScheduleActivity.EX_REPEAT, 1);
            if (draft.schedMode == FlowStore.MODE_AT) {
                draft.atMillis = data.getLongExtra(ScheduleActivity.EX_AT, 0);
                draft.startAtMillis = 0;
            } else if (draft.schedMode == FlowStore.MODE_DELAY) {
                draft.delayMs = data.getLongExtra(ScheduleActivity.EX_DELAY, 0);
                draft.startAtMillis = System.currentTimeMillis();
            } else {
                draft.atMillis = 0; draft.delayMs = 0; draft.startAtMillis = 0;
            }
            paintSched();
        }
    }

    private void loadContent() {
        loadContent(0);
    }

    /**
     * 读流程内容。
     *
     * 板子的 WebServer 是单线程的,偶尔会被 App 的轮询挤掉 ——
     * 所以出错**自动重试 3 次**(每次间隔递增),而不是直接失败。
     * 全失败了才提示,并给你一个「重试」按钮。
     */
    private void loadContent(final int tryNo) {
        bodyEd.setText(tryNo == 0 ? "读取中…" : ("读取中…(第 " + (tryNo + 1) + " 次)"));
        board.scriptText(index, new Board.Cb() {
            @Override public void done(boolean ok, String body) {
                if (!ok) {
                    if (tryNo < 2) {
                        // 网络抖一下就自己再试,不打扰用户
                        bodyEd.postDelayed(new Runnable() {
                            @Override public void run() { loadContent(tryNo + 1); }
                        }, 250L * (tryNo + 1));
                        return;
                    }
                    bodyEd.setText("");
                    showLoadFailed(body);
                    return;
                }
                try {
                    JSONObject o = new JSONObject(body);
                    String nm = Board.jstr(o, "name");
                    if (nm != null && !nm.isEmpty()) {
                        origName = nm;
                        nameEd.setText(nm);
                    }
                    bodyEd.setText(Board.jstr(o, "text"));
                } catch (Exception e) { bodyEd.setText(""); }
            }
        });
    }

    /** 读取彻底失败:给出说明 + 一个「重试」按钮,而不是只弹个 toast */
    private void showLoadFailed(String why) {
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
        b.setTitle("读取流程失败");
        b.setMessage("连不上板子:" + why + "\n\n"
                + "常见原因:\n"
                + "· 板子正忙(在跑流程 / 另一个页面在刷新)\n"
                + "· WiFi 抖动\n"
                + "· 板子 IP 变了\n\n"
                + "可以直接重试,不用退出去重进。");
        b.setPositiveButton("重试", new android.content.DialogInterface.OnClickListener() {
            @Override public void onClick(android.content.DialogInterface d, int w) {
                loadContent(0);
            }
        });
        b.setNegativeButton("返回", new android.content.DialogInterface.OnClickListener() {
            @Override public void onClick(android.content.DialogInterface d, int w) {
                finish();
            }
        });
        b.setCancelable(false);
        b.show();
    }

    private void doSave() {
        String n = nameEd.getText().toString().trim();
        if (n.isEmpty()) { toast("请填流程名"); return; }
        if (visualMode) applyStepsToText();
        String content = bodyEd.getText().toString().trim();
        if (content.isEmpty()) {
            toast("流程内容不能为空,至少写一个动作,例如 T 0.5 0.5 60");
            return;
        }
        draft.name = n;
        toast("正在保存…");
        board.save(n, content, new Board.Cb() {
            @Override public void done(boolean ok, String body) {
                toast(Board.messageOf(body));
                if (ok) {
                    // ⚠️ 改名了要把**板子上**的旧名字删掉,否则会留下幽灵流程
                    //   (用户实测:"改了名字回到列表,名字没变还多出一条")
                    //   ⚠️ 服务器上**不动** —— 按新规格,上传是手动的。
                    if (origName != null && !origName.isEmpty() && !origName.equals(n)) {
                        board.delete(origName, null);
                        flows.remove(origName);
                    }
                    flows.put(draft);
                    // ⚠️ 按用户新规格:**新建/改名不再自动上传服务器** ——
                    //    上传要用流程页的「⬆️ 上传流程」按钮。
                    // ⚠️ 让流程列表的缓存作废 —— 否则回到主界面还显示旧列表
                    //    (用户刚存的流程看不到,得手动点「刷新列表」)
                    MainActivity.scriptsDirty = true;
                    finish();
                }
            }
        });
    }

    private void paintSched() {
        if (draft.schedMode == FlowStore.MODE_OFF) {
            schedInfo.setText("定时:未设置");
            schedInfo.setTextColor(C_DIM);
        } else {
            long t = draft.targetMillis();
            schedInfo.setText("定时:" + (t > 0 ? DateTimePick.fmt(t) : "已设置"));
            schedInfo.setTextColor(C_ACC);
        }
    }

    /** 定时设置:也做成全屏页(避免弹窗问题) */
    private void openSchedule() {
        startActivityForResult(new Intent(this, ScheduleActivity.class)
                .putExtra(ScheduleActivity.EX_NAME, draft.name)
                .putExtra(ScheduleActivity.EX_MODE, draft.schedMode)
                .putExtra(ScheduleActivity.EX_AT, draft.atMillis)
                .putExtra(ScheduleActivity.EX_DELAY, draft.delayMs)
                .putExtra(ScheduleActivity.EX_REPEAT, draft.repeatCount), REQ_SCHED);
    }

    private static final int REQ_SCHED = 2001;


    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(C_DIM);
        t.setTextSize(12);
        t.setPadding(0, dp(10), 0, dp(4));
        return t;
    }

    private void toast(String s) {
        if (s == null || s.isEmpty()) return;
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
