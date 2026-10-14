package com.dsh.touchpad;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 悬浮窗:显示当前时间、目标 App、运行日志;可拖动,可贴边收起,点小球重新展开。
 *
 * ══════════════════════════════════════════════════════════════════
 *  ⚠️ 为什么拆成**两个窗口**:
 *
 *  Android 里"可触摸的窗口"会**吃掉落在它范围内的所有触摸**。
 *  而我们的注入是"按绝对坐标"打到屏幕上的 —— 悬浮窗如果整体可触摸,
 *  落在它上面的注入点击就会被它吞掉,流程静默失败(而且极难查)。
 *
 *  但窗口设成 NOT_TOUCHABLE 之后,**连拖动都做不了**(子 View 也收不到触摸)。
 *
 *  所以拆成:
 *    · panelWindow  —— 面板(时间/App/日志),NOT_TOUCHABLE,触摸 100% 穿透
 *    · handleWindow —— 拖柄 / 收起后的小球,可触摸,**很小**,执行流程时自动隐藏
 *
 *  这样"看得见"和"能操作"分开,既不会挡注入,又能拖能收。
 * ══════════════════════════════════════════════════════════════════
 */
public class FloatingWindow {

    private static final int C_BG   = 0xE6151b22;
    private static final int C_FG   = 0xffc9d1d9;
    private static final int C_DIM  = 0xff8b949e;
    private static final int C_ACC  = 0xff58a6ff;
    private static final int C_WARN = 0xffffd479;
    private static final int C_OK   = 0xff3fb950;

    private static final int PANEL_W_DP = 232;
    private static final int PANEL_H_DP = 150;
    private static final int BALL_DP    = 42;
    // 把柄改成**竖排 4 个按钮**:拖动 / 暂时隐藏 / 坐标拾取 / 收到边框
    private static final int HANDLE_W_DP  = 34;   // 按钮宽度
    private static final int HANDLE_BH_DP = 30;   // 单个按钮高度
    private static final int HANDLE_H_DP  = HANDLE_BH_DP * 5;   // 展开时整体高度(5 个按钮)

    private final Context ctx;
    private final WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ---- 面板窗口 ----
    private View panelRoot;
    private WindowManager.LayoutParams panelLp;
    private TextView timeView, appView, logView, pickView, schedView;
    /** 日志那块的可滚动容器 —— 默认收起(用户要求:启动后别占屏) */
    private ScrollView logScroll;
    /** 把柄上的「📜」按钮,用来展开/收起日志 */
    private TextView logBtnRef;
    /** 日志当前是否展开。默认 false = 收起 */
    private boolean logOpen = false;
    private TextView freqView;   // 频率(间隔 + 连续时长)运行状态
    private final StringBuilder logBuf = new StringBuilder();

    // ---- 拖柄/小球窗口 ----
    private View handleRoot;
    private TextView handleText;
    private WindowManager.LayoutParams handleLp;

    private boolean showing = false;
    private boolean collapsed = false;
    private boolean dockRight = true;      // 贴哪一边
    private int dockY = 400;               // 贴边后的纵向位置
    private boolean executing = false;
    private boolean handleAttached = false;
    private CoordPicker picker;
    private View dragTarget;
    private TextView collapseBtnRef;
    private TextView yieldBtnRef;     // 暂时隐藏
    private TextView pickBtnRef;      // 坐标拾取
    private TextView copyBtnRef;      // 复制页面句柄

    private int screenW, screenH;

    /**
     * 进程级单例。
     *
     * ⚠️ 为什么必须是单例:MainActivity 重建时会再 `new` 一个,
     *    而**旧实例的窗口还挂在 WindowManager 上没被移除**,结果屏幕上
     *    出现两套悬浮窗(实测:4 个 overlay 窗口 = 2 套 handle+panel)。
     *    而且旧实例一直持有已销毁的 Activity,还会泄漏。
     */
    private static FloatingWindow INSTANCE = null;

    public static FloatingWindow get(Context c) {
        if (INSTANCE == null) {
            INSTANCE = new FloatingWindow(c.getApplicationContext());
        }
        return INSTANCE;
    }

    public FloatingWindow(Context ctx) {
        this.ctx = ctx;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    public boolean isShowing() { return showing; }

    private int sp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                ctx.getResources().getDisplayMetrics());
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }

    /**
     * 把可能还挂在 WindowManager 上的视图全部摘掉。
     * 每次 show 之前先做一次,保证屏幕上**永远只有一套**悬浮窗。
     */
    private void forceDetach() {
        if (handleRoot != null) {
            try { wm.removeView(handleRoot); } catch (Throwable ignored) {}
            try { wm.removeViewImmediate(handleRoot); } catch (Throwable ignored) {}
        }
        if (panelRoot != null) {
            try { wm.removeView(panelRoot); } catch (Throwable ignored) {}
            try { wm.removeViewImmediate(panelRoot); } catch (Throwable ignored) {}
        }
        handleAttached = false;    // 面板和手柄是一起挂载的,一个标记就够
        // 重新建一次视图,避免复用已经 detach 的 View
        handleRoot = null;
        panelRoot = null;
    }

    private int windowType() {
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    // ================================================================ 创建

    public void show() {
        if (showing) return;
        forceDetach();          // 清掉可能残留的窗口(防重复)
        screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        screenH = ctx.getResources().getDisplayMetrics().heightPixels;

        buildPanel();
        buildHandle();

        panelLp.x = dp(12);
        panelLp.y = dp(150);
        handleLp.x = dp(12);
        handleLp.y = dp(150);

        try {
            wm.addView(panelRoot, panelLp);
            wm.addView(handleRoot, handleLp);
            handleAttached = true;
            showing = true;
        } catch (Throwable t) {
            showing = false;
        }
    }

    private void buildPanel() {
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(C_BG);
        panel.setPadding(dp(10), dp(8), dp(10), dp(8));

        // 第一行:占位(给拖柄让出位置) + 时间
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);

        View pad = new View(ctx);
        // 把柄改成**竖排**后位于面板左侧(宽 34dp),和面板**不再重叠**,
        // 所以这里只要和下面几行对齐即可(原来给横向把柄留了 108dp)。
        row.addView(pad, new LinearLayout.LayoutParams(dp(26), dp(1)));

        timeView = new TextView(ctx);
        timeView.setTextColor(C_ACC);
        timeView.setTextSize(13);
        timeView.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(timeView);
        panel.addView(row);

        appView = new TextView(ctx);
        appView.setTextColor(C_WARN);
        appView.setTextSize(11);
        appView.setPadding(dp(26), dp(2), 0, dp(4));
        // ⚠️ 面板是 FLAG_NOT_TOUCHABLE(否则注入的点击会被它吃掉,流程就点空),
        //    所以**不能**在面板上放点击事件 —— 复制句柄的按钮做在把柄上。
        panel.addView(appView);

        // 最近的定时 / 即将执行的流程
        schedView = new TextView(ctx);
        schedView.setTextColor(C_WARN);
        schedView.setTextSize(11);
        schedView.setPadding(dp(26), 0, 0, dp(4));
        panel.addView(schedView);

        // 频率运行状态(每 N 毫秒 + 还剩多久)
        freqView = new TextView(ctx);
        freqView.setTextColor(C_ACC);
        freqView.setTextSize(11);
        freqView.setPadding(dp(26), 0, 0, dp(4));
        panel.addView(freqView);

        // 最近拾取到的坐标
        pickView = new TextView(ctx);
        pickView.setTextColor(C_OK);
        pickView.setTextSize(11);
        pickView.setPadding(dp(26), 0, 0, dp(4));
        panel.addView(pickView);

        logView = new TextView(ctx);
        logView.setTextColor(C_DIM);
        logView.setTextSize(10);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logScroll = new ScrollView(ctx);
        logScroll.addView(logView);
        panel.addView(logScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(PANEL_H_DP - 50)));
        // ⚠️ **默认收起** —— 用户实测反馈:"app 启动后默认收起悬浮窗的日志"。
        //    日志区很高(PANEL_H_DP-50),一上来就把屏幕占掉一大块,
        //    而大多数时候用户只关心"时间 / 目标 App / 运行状态"。
        logScroll.setVisibility(View.GONE);

        panelRoot = panel;

        panelLp = new WindowManager.LayoutParams(
                dp(PANEL_W_DP), WindowManager.LayoutParams.WRAP_CONTENT,
                windowType(),
                // 面板永远不可触摸:注入的点击 100% 穿透
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP | Gravity.START;
    }

    private void buildHandle() {
        // 竖排 4 个按钮(用户要求):
        //   ① ⠿ 拖动   ② 🙈 暂时隐藏   ③ 📐 坐标拾取   ④ ◀ 收到边框
        // 收到边框后**只留第一个按钮**(图标换成 📋 日志),
        // 点它就重新弹出悬浮窗。
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        // ① 拖动(也是贴边后剩下的那一个)
        handleText = new TextView(ctx);
        handleText.setText("⠿");
        handleText.setTextColor(C_FG);
        handleText.setTextSize(15);
        handleText.setGravity(Gravity.CENTER);
        handleText.setBackgroundColor(0xCC1f6feb);
        handleText.setLayoutParams(new LinearLayout.LayoutParams(
                dp(HANDLE_W_DP), dp(HANDLE_BH_DP)));
        box.addView(handleText);

        // ② 暂时隐藏(让路):开屏广告的跳过按钮被挡住时用
        final TextView yieldBtn = mkHandleBtn("🙈", 0xCCbf8700);
        yieldBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { yieldWay(); }
        });
        yieldBtnRef = yieldBtn;
        box.addView(yieldBtn);

        // ③ 坐标拾取:盖一层透明取点层,点哪儿记哪儿
        final TextView pickBtn = mkHandleBtn("📐", 0xCC8957e5);
        pickBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startPick(); }
        });
        pickBtnRef = pickBtn;
        box.addView(pickBtn);

        // ④ 复制页面句柄(方便贴进 ifpage / waitfor)
        //    为什么放在把柄而不是面板:面板 FLAG_NOT_TOUCHABLE,收不到点击
        final TextView copyPage = mkHandleBtn("🔍", 0xCC1a7f37);
        copyPage.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copyPageHandle(); }
        });
        copyBtnRef = copyPage;
        box.addView(copyPage);

        // ⑤ 收到边框:面板收起,只留一个贴边小球
        final TextView collapse = mkHandleBtn("◀", 0xCC30363d);
        collapse.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { collapse(); }
        });
        collapseBtnRef = collapse;
        box.addView(collapse);

        // ⑥ 展开/收起**日志**(默认收起 —— 用户要求)
        final TextView logBtn = mkHandleBtn("📜", 0xCC57606a);
        logBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleLog(); }
        });
        logBtnRef = logBtn;
        box.addView(logBtn);

        handleRoot = box;
        // ⚠️ 拖动监听必须挂在**拖柄本身**上。
        //    挂在容器上没用:各按钮都是 clickable,会先吃掉触摸,
        //    父容器的 OnTouchListener 根本不会触发(实测踩过)。
        dragTarget = handleText;

        handleLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType(),
                // FLAG_KEEP_SCREEN_ON:悬浮窗只要可见就压住息屏。
                // 这是**零权限**的兜底方案(改系统息屏时间要 WRITE_SETTINGS)。
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        handleLp.gravity = Gravity.TOP | Gravity.START;

        setupDrag(handleText);
    }

    /** 当前这个"选词浮层" */
    private android.view.View wordPopup = null;

    /**
     * 点 🔍 → 弹出「当前页面有哪些词」,点一个词就把
     * `waitfor 那个词 8000` **整行复制好**,直接粘到流程里就能用。
     *
     * ══════════════════════════════════════════════════════════════
     *  为什么做成这样
     *
     *  用户反馈"智能等待还是没玩明白" —— 原来的 🔍 只复制一长串句柄
     *  (`cn.damai.MainActivity | 首页 / 推荐 / 演出 / ...`),
     *  用户拿到之后**不知道该把哪个词填进 waitfor**。
     *
     *  现在反过来:App 把当前页面上的词**列出来**,
     *  你点哪个,就把能直接用的那一行给你。**不用记语法。**
     * ══════════════════════════════════════════════════════════════
     */
    private void copyPageHandle() {
        java.util.List<String> words = null;
        String act = "";
        int prevN = 0;
        java.util.Set<String> prev = null;
        try {
            words = PageWatcher.textWords();
            act = PageWatcher.activityShort();
            // ⚠️ 顺序很重要:先取"上一页"的快照,再记录本页 ——
            //    反过来的话比的就成了本页自己,⭐ 一个都标不出来
            prev = PageWatcher.prevWordsSnapshot();
            prevN = prev.size();
            PageWatcher.rememberWordsAsPrev();
        } catch (Throwable ignored) {}

        if ((words == null || words.isEmpty()) && (act == null || act.isEmpty())) {
            android.widget.Toast.makeText(ctx,
                    "还没拿到页面信息(请先开启「页面识别」,并切到目标页面)",
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        showWordPopup(act, words, prevN, prev);
    }

    /**
     * 把列出来的词做成可点的浮层。
     *
     * 布局要点:
     *   · **⭐ 的词排最前面** —— 它们才是能用的关键字,别埋在几十个词中间
     *   · 整个面板**包在 ScrollView 里** —— 一页几十个词会超出屏幕,要能上下滑
     *   · 高度按内容算,最多占屏幕 72%
     */
    private void showWordPopup(String act, java.util.List<String> words, int prevN,
                               final java.util.Set<String> prev) {
        dismissWordPopup();
        try {
            // ---- ⭐ 排前面 ----
            if (words != null && !words.isEmpty() && prev != null && !prev.isEmpty()) {
                java.util.List<String> news = new java.util.ArrayList<String>();
                java.util.List<String> olds = new java.util.ArrayList<String>();
                for (String w : words) {
                    if (prev.contains(w.trim())) olds.add(w); else news.add(w);
                }
                news.addAll(olds);
                words = news;
            }

            // 外层:固定背景 + 圆角(内容区可滚动,底部按钮固定)
            LinearLayout outer = new LinearLayout(ctx);
            outer.setOrientation(LinearLayout.VERTICAL);
            outer.setBackgroundColor(0xF2161b22);
            outer.setPadding(dp(16), dp(14), dp(16), dp(14));

            // 可滚动的词区
            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.VERTICAL);

            TextView title = new TextView(ctx);
            title.setText("当前页面:" + (act == null || act.isEmpty() ? "(未知)" : act));
            title.setTextColor(0xff58a6ff);
            title.setTextSize(13);
            card.addView(title);

            TextView hint = new TextView(ctx);
            hint.setText(prevN == 0
                    ? "这是第一次打开。先在这一页点一次,切到另一个页面再点一次 ——\n"
                      + "第二次会用 ⭐ 标出「本页独有」的词,那才是能用的 waitfor 关键字。"
                    : "⭐ = 上一页没有、只在本页出现的词 —— 推荐用它当关键字(已排在最前面)。\n"
                      + "点任意一个词 → 自动复制好一行 waitfor,粘到流程里就能用。");
            hint.setTextColor(0xffffd479);
            hint.setTextSize(11);
            hint.setPadding(0, dp(4), 0, dp(10));
            card.addView(hint);

            if (words == null || words.isEmpty()) {
                TextView none = new TextView(ctx);
                none.setText("(这一页没读到可用的文字 —— 试试用 Activity 名)");
                none.setTextColor(0xff9aa4b0);
                none.setTextSize(11);
                card.addView(none);
            } else {
                // 词按行折(和流程编辑页一样的做法)
                android.graphics.Paint pm = new android.graphics.Paint();
                pm.setTextSize(sp(12));
                int maxW = screenW - dp(100);
                LinearLayout row = null;
                int used = 0;
                for (final String w : words) {
                    boolean isNew = (prev != null && !prev.isEmpty() && !prev.contains(w.trim()));
                    String label = isNew ? ("⭐ " + w) : w;
                    int bw = (int) pm.measureText(label) + dp(30);
                    if (row == null || (used > 0 && used + bw > maxW)) {
                        row = new LinearLayout(ctx);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT);
                        rlp.bottomMargin = dp(5);
                        row.setLayoutParams(rlp);
                        card.addView(row);
                        used = 0;
                    }
                    Button b = new Button(ctx);
                    b.setText(label);
                    b.setTextSize(12);
                    b.setAllCaps(false);
                    b.setMinimumWidth(0);
                    b.setMinimumHeight(0);
                    b.setPadding(dp(10), 0, dp(10), 0);
                    LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
                    lp2.rightMargin = dp(5);
                    b.setLayoutParams(lp2);

                    // 颜色自己画(系统默认 Button 是亮色底,浅色字会看不清)
                    android.graphics.drawable.GradientDrawable bg =
                            new android.graphics.drawable.GradientDrawable();
                    bg.setCornerRadius(dp(6));
                    if (isNew) {
                        bg.setColor(0xFF3a2d0a);
                        bg.setStroke(dp(1), 0xFFd29922);
                        b.setTextColor(0xFFFFE08A);
                    } else {
                        bg.setColor(0xFF21262d);
                        bg.setStroke(dp(1), 0xFF30363d);
                        b.setTextColor(0xFF9aa4b0);
                    }
                    b.setBackground(bg);

                    b.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            copyLine("waitfor " + w + " 8000 等「" + w + "」出现");
                        }
                    });
                    row.addView(b);
                    used += bw + dp(5);
                }
            }

            // 词区结束 → 放进 ScrollView
            ScrollView sv = new ScrollView(ctx);
            sv.setVerticalScrollBarEnabled(true);
            sv.addView(card);
            // 高度权重 1:把剩余空间全给词区,footer 固定在底部
            outer.addView(sv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            // 底部(固定,不跟着滚):用页面名 / 关闭
            LinearLayout foot = new LinearLayout(ctx);
            foot.setOrientation(LinearLayout.HORIZONTAL);
            foot.setPadding(0, dp(10), 0, 0);

            if (act != null && !act.isEmpty()) {
                Button bAct = new Button(ctx);
                bAct.setText("用页面名:" + act);
                bAct.setTextSize(11);
                bAct.setAllCaps(false);
                bAct.setTextColor(0xFF9aa4b0);
                android.graphics.drawable.GradientDrawable bgA =
                        new android.graphics.drawable.GradientDrawable();
                bgA.setColor(0xFF21262d);
                bgA.setStroke(dp(1), 0xFF30363d);
                bgA.setCornerRadius(dp(6));
                bAct.setBackground(bgA);
                bAct.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        copyLine("waitfor " + act + " 8000 等这个页面");
                    }
                });
                foot.addView(bAct);
            }
            Button close = new Button(ctx);
            close.setText("关闭");
            close.setTextSize(11);
            close.setAllCaps(false);
            close.setTextColor(0xFFc9d1d9);
            android.graphics.drawable.GradientDrawable bgC =
                    new android.graphics.drawable.GradientDrawable();
            bgC.setColor(0xFF21262d);
            bgC.setStroke(dp(1), 0xFF30363d);
            bgC.setCornerRadius(dp(6));
            close.setBackground(bgC);
            close.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { dismissWordPopup(); }
            });
            foot.addView(close);
            outer.addView(foot);

            // 高度:按内容算,最多占屏幕 76%
            int maxCardW = screenW - dp(60);
            outer.measure(View.MeasureSpec.makeMeasureSpec(maxCardW,
                            View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0,
                            View.MeasureSpec.UNSPECIFIED));
            int contentH = outer.getMeasuredHeight();
            int maxH = (int) (screenH * 0.76);
            int finalH = Math.min(contentH + dp(4), maxH);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    finalH,
                    windowType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.CENTER;
            wordPopup = outer;
            wm.addView(wordPopup, lp);
        } catch (Throwable e) {
            android.widget.Toast.makeText(ctx, "打不开选词面板:" + e.getMessage(),
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void dismissWordPopup() {
        if (wordPopup == null) return;
        try { wm.removeViewImmediate(wordPopup); } catch (Throwable ignored) {}
        wordPopup = null;
    }

    private void copyLine(String line) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager)
                            ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("waitfor", line));
            android.widget.Toast.makeText(ctx,
                    "已复制,粘到流程里即可:\n" + line,
                    android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable e) {
            android.widget.Toast.makeText(ctx, "复制失败:" + e.getMessage(),
                    android.widget.Toast.LENGTH_SHORT).show();
        }
        dismissWordPopup();
    }

    /** 把柄上的一个小按钮(竖排一行的样式) */
    private TextView mkHandleBtn(String icon, int bg) {
        TextView t = new TextView(ctx);
        t.setText(icon);
        t.setTextColor(C_FG);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundColor(bg);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                dp(HANDLE_W_DP), dp(HANDLE_BH_DP)));
        return t;
    }

    // ================================================================ 拖动 / 贴边

    private void setupDrag(View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            private int sx, sy, dy0;
            private float dx0, dy0f;
            private boolean moved;

            @Override public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = panelLp.x; sy = panelLp.y;
                        dy0 = handleLp.y;
                        dx0 = e.getRawX(); dy0f = e.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) (e.getRawX() - dx0);
                        int dy = (int) (e.getRawY() - dy0f);
                        if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) moved = true;
                        panelLp.x = sx + dx;
                        panelLp.y = clampY(sy + dy);
                        handleLp.x = panelLp.x;
                        handleLp.y = panelLp.y;
                        update();
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (moved) {
                            snapToEdge();
                        } else {
                            // 没移动 = 一次点击:收起状态下点小球就展开
                            if (collapsed) expand();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private int clampY(int y) {
        // 竖向把柄展开时高 HANDLE_H_DP,贴边小球 BALL_DP —— 留最大的那个
        int max = screenH - dp(Math.max(HANDLE_H_DP, BALL_DP)) - dp(20);
        if (max < 0) max = 0;
        if (y < 0) y = 0;
        if (y > max) y = max;
        return y;
    }

    /** 松手后贴到最近的一侧边缘,并收起成小球 */
    private void snapToEdge() {
        int cx = panelLp.x + dp(PANEL_W_DP) / 2;
        dockRight = cx > screenW / 2;
        dockY = clampY(panelLp.y);
        if (dockRight) {
            handleLp.x = screenW - dp(HANDLE_W_DP);
            panelLp.x = handleLp.x - dp(PANEL_W_DP) + dp(4);
        } else {
            handleLp.x = 0;
            panelLp.x = dp(HANDLE_W_DP) + dp(2);
        }
        panelLp.y = dockY;
        handleLp.y = dockY;
        update();
        if (!collapsed) collapse();
    }

    // ================================================================ 收起 / 展开

    /** 收到边框:面板收起,**只留「日志」按钮**,点它再弹出来 */
    public void collapse() {
        if (!showing) return;
        collapsed = true;

        // 面板隐藏
        panelRoot.setVisibility(View.GONE);

        // 只留第一个按钮,图标换成 📋(点它 = 弹出悬浮窗)
        if (collapseBtnRef != null) collapseBtnRef.setVisibility(View.GONE);
        if (yieldBtnRef != null) yieldBtnRef.setVisibility(View.GONE);
        if (pickBtnRef != null) pickBtnRef.setVisibility(View.GONE);
        if (copyBtnRef != null) copyBtnRef.setVisibility(View.GONE);
        if (logBtnRef != null) logBtnRef.setVisibility(View.GONE);
        handleText.setText("📋");
        handleText.setTextSize(16);
        handleText.setLayoutParams(new LinearLayout.LayoutParams(dp(BALL_DP), dp(BALL_DP)));

        // 贴边:露出大半个在屏幕外一点,像常见的小球
        handleLp.x = dockRight ? screenW - dp(BALL_DP - 6) : -dp(6);
        handleLp.y = clampY(dockY);
        update();
    }

    /** 展开:4 个按钮回来,面板贴着把柄出现 */
    /** 展开/收起日志那块(默认收起) */
    public void toggleLog() {
        logOpen = !logOpen;
        if (logScroll != null) {
            logScroll.setVisibility(logOpen ? View.VISIBLE : View.GONE);
        }
        update();
    }

    public void expand() {
        if (!showing) return;
        collapsed = false;

        if (collapseBtnRef != null) collapseBtnRef.setVisibility(View.VISIBLE);
        if (yieldBtnRef != null) yieldBtnRef.setVisibility(View.VISIBLE);
        if (pickBtnRef != null) pickBtnRef.setVisibility(View.VISIBLE);
        if (copyBtnRef != null) copyBtnRef.setVisibility(View.VISIBLE);
        if (logBtnRef != null) logBtnRef.setVisibility(View.VISIBLE);
        handleText.setText("⠿");
        handleText.setTextSize(15);
        handleText.setLayoutParams(new LinearLayout.LayoutParams(
                dp(HANDLE_W_DP), dp(HANDLE_BH_DP)));

        int hy = clampY(handleLp.y);
        handleLp.y = hy;
        handleLp.x = dockRight ? screenW - dp(HANDLE_W_DP) : 0;

        if (dockRight) {
            panelLp.x = Math.max(0, handleLp.x - dp(PANEL_W_DP) + dp(4));
        } else {
            panelLp.x = handleLp.x + dp(HANDLE_W_DP) + dp(2);
        }
        panelLp.y = hy;
        panelRoot.setVisibility(View.VISIBLE);
        update();
    }

    private void update() {
        try { wm.updateViewLayout(panelRoot, panelLp); } catch (Throwable ignored) {}
        try { wm.updateViewLayout(handleRoot, handleLp); } catch (Throwable ignored) {}
    }

    public void hide() {
        if (!showing) return;
        try { wm.removeView(panelRoot); } catch (Throwable ignored) {}
        try { wm.removeView(handleRoot); } catch (Throwable ignored) {}
        handleAttached = false;
        showing = false;
    }

    private Runnable restoreRunnable;

    /**
     * 临时隐藏,过一会儿自己回来。
     *
     * 用途:很多 App 启动时的开屏广告,"跳过/关闭"按钮在**右上角** ——
     *      悬浮窗贴到右边时正好压住它,广告就点不到了。
     *      所以启动目标 App 前先把悬浮窗收起来,给广告让路。
     *
     * @param ms 多久后自动恢复
     */
    public void hideTemporarily(long ms) {
        if (!showing) return;
        hide();
        if (restoreRunnable != null) ui.removeCallbacks(restoreRunnable);
        restoreRunnable = new Runnable() {
            @Override public void run() {
                restoreRunnable = null;
                show();
            }
        };
        ui.postDelayed(restoreRunnable, ms);
    }

    /**
     * 悬浮窗是否挡住了屏幕**右上角**(开屏广告的「跳过」按钮通常在那)。
     *
     * 用来决定"要不要提醒用户让路" —— 只在真的挡住时才提示,
     * 不挡就完全不打扰。
     *
     * @param w 屏幕宽度
     * @param h 屏幕高度
     */
    public boolean coversTopRight(int w, int h) {
        if (!showing || w <= 0 || h <= 0) return false;
        // 右上角热区:横向右侧 30%,纵向顶部 25%
        int rx = (int) (w * 0.70);
        int ry = (int) (h * 0.25);
        return overlaps(rx, 0, w, ry);
    }

    /** 悬浮窗(手柄或面板)是否和给定矩形相交 */
    private boolean overlaps(int x1, int y1, int x2, int y2) {
        int hx = handleLp != null ? handleLp.x : -1;
        int hy = handleLp != null ? handleLp.y : -1;
        int hw = dp(26 * 3);      // 手柄约 3 个按钮宽
        int hh = dp(34);
        if (hx >= 0 && hx < x2 && hx + hw > x1 && hy < y2 && hy + hh > y1) return true;

        int px = panelLp != null ? panelLp.x : -1;
        int py = panelLp != null ? panelLp.y : -1;
        if (px >= 0 && px < x2 && px + dp(PANEL_W_DP) > x1
                && py < y2 && py + dp(PANEL_H_DP) > y1) return true;
        return false;
    }

    /** 取消待恢复的定时(比如用户手动关了悬浮窗) */
    public void cancelRestore() {
        if (restoreRunnable != null) {
            ui.removeCallbacks(restoreRunnable);
            restoreRunnable = null;
        }
    }

    /**
     * 执行流程时:把**可触摸的拖柄整个隐藏**,面板保持可见但本来就不可触摸。
     * 这样注入的触摸绝不会被悬浮窗吃掉。
     */
    public void setExecuting(boolean ex) {
        if (!showing || executing == ex) return;
        executing = ex;
        // ⚠️ 只把窗口根视图设成 GONE **不会移除窗口**,窗口仍然存在、
        //    仍然在触摸判定里。要真正"让路",必须把窗口从 WindowManager 移除。
        //    实测踩过:设了 GONE 但拖柄位置的注入点击还是被吃掉。
        if (ex) {
            try { wm.removeView(handleRoot); } catch (Throwable ignored) {}
            handleAttached = false;
        } else {
            try {
                wm.addView(handleRoot, handleLp);
                handleAttached = true;
            } catch (Throwable ignored) {}
        }
    }

    /**
     * 手动让路:把悬浮窗收起来 15 秒。
     *
     * 为什么是**手动**而不是启动 App 时自动隐藏:
     *   自动隐藏是一刀切 —— 不管压没压住都藏,而且藏多久靠猜。
     *   改成用户自己点,行为完全可预期;而且会明确告诉多久后回来。
     */
    public void yieldWay() {
        if (!showing) return;
        hideTemporarily(15000);
        android.widget.Toast.makeText(ctx,
                "悬浮窗已收起,15 秒后自动回来", android.widget.Toast.LENGTH_SHORT).show();
    }

    /** 显示最近的定时:流程名 + 剩余时间(或"执行中") */
    public void setSchedule(final String flowName, final String countdown,
                            final boolean running) {
        if (!showing) return;
        ui.post(new Runnable() {
            @Override public void run() {
                if (schedView == null) return;
                if (flowName == null || flowName.isEmpty()) {
                    schedView.setText("⏳ 无定时任务");
                    schedView.setTextColor(C_DIM);
                } else if (running) {
                    schedView.setText("▶ " + flowName + " 执行中");
                    schedView.setTextColor(C_OK);
                } else {
                    schedView.setText("⏳ " + flowName + " · " + countdown);
                    schedView.setTextColor(C_WARN);
                }
            }
        });
    }

    /**
     * 显示频率运行状态。
     *
     * 频率是**板子侧**在跑,所以到时间由板子自己停(App 被杀也停) ——
     * 这里只是把板子报的状态显示出来。
     *
     * @param on       是否在跑
     * @param interval 间隔毫秒
     * @param remain   还剩毫秒(0 = 不限时长)
     * @param name     流程名
     */
    public void setFreq(final boolean on, final long interval, final long remain,
                        final String name) {
        if (!showing) return;
        ui.post(new Runnable() {
            @Override public void run() {
                if (freqView == null) return;
                if (!on) {
                    freqView.setText("🔁 频率:未运行");
                    freqView.setTextColor(C_DIM);
                    return;
                }
                String iv = (interval >= 1000)
                        ? String.format(java.util.Locale.US, "%.1f 秒", interval / 1000.0)
                        : interval + " 毫秒";
                String left;
                if (remain <= 0) {
                    left = "不限时长";
                } else {
                    long sec = remain / 1000;
                    left = String.format(java.util.Locale.US, "还剩 %d:%02d",
                            sec / 60, sec % 60);
                }
                freqView.setText("🔁 " + (name == null ? "" : name)
                        + " 每 " + iv + " · " + left);
                freqView.setTextColor(C_OK);
            }
        });
    }

    // ================================================================ 坐标拾取

    /**
     * 开始拾取坐标:临时盖一层全屏透明窗口,你点哪儿就记哪儿。
     *
     * 注意:这一下点击会被取点层拿走,**不会下发给下面的 App**。
     * 所以正确用法是:先在目标 App 里翻到那一页 → 点📐 → 点目标位置。
     */
    private void startPick() {
        if (picker != null && picker.isShowing()) { picker.cancel(); return; }
        // 执行流程时不要取点,免得互相干扰
        if (executing) return;

        picker = new CoordPicker(ctx, new CoordPicker.OnPick() {
            @Override public void onPick(float x, float y) {
                final String txt = CoordPicker.fmt(x, y);
                // 顺手试一次复制。⚠️ 但**不能依赖它**:
                //    Android 10+ / MIUI 会限制**后台应用**访问剪切板,
                //    而拾取时目标 App 在前台、我们是后台 → 写入可能被丢掉,
                //    表现就是"粘贴出来还是上一次的值"(用户实测)。
                //    所以真正的复制入口放在 App 里(前台,一定成功)。
                boolean copied = false;
                try {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager)
                                    ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("坐标", txt));
                    copied = true;
                } catch (Throwable ignored) {}
                final boolean ok = copied;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (pickView != null) {
                            pickView.setText("📍 已拾取:" + txt
                                    + (ok ? "(已试复制,可能被系统拦)" : "")
                                    + " → 到 App 里点「复制」最稳");
                        }
                    }
                });
            }
            @Override public void onCancel() {
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (pickView != null) pickView.setText("📍 已取消");
                    }
                });
            }
        });
        picker.start();
    }

    /** 供外部(App)读取最近拾取记录 */
    public static java.util.List<float[]> pickHistory() {
        return CoordPicker.history(null);
    }

    // ================================================================ 内容

    public void setHeader(String appName, String serverTime) {
        if (!showing) return;
        final String a = (appName == null || appName.isEmpty()) ? "(未选目标 App)" : appName;
        final String t = serverTime == null ? "--:--:--" : serverTime;
        // 页面识别(无障碍开了才有):让悬浮窗直接显示"现在在哪个页面",
        // 不用切回 App 就能看出流程是不是卡在某个页面上。
        String page = "";
        try { page = PageWatcher.summary(); } catch (Throwable ignored) {}
        final String p = page;
        ui.post(new Runnable() {
            @Override public void run() {
                if (appView != null) {
                    if (p.isEmpty()) {
                        appView.setText("📱 " + a);
                    } else {
                        // 同一行里若应用名重复就不重复显示
                        String show = p.startsWith(a) ? p.substring(a.length()).replaceFirst("^ · ", "")
                                                      : p;
                        appView.setText("📱 " + a + "\n🔍 " + show);
                    }
                }
                if (timeView != null) timeView.setText("🕒 " + t);
            }
        });
    }

    public void setLog(String text) {
        if (!showing) return;
        logBuf.setLength(0);
        if (text != null) logBuf.append(text);
        if (logBuf.length() > 2000) logBuf.delete(0, logBuf.length() - 2000);
        ui.post(new Runnable() {
            @Override public void run() {
                if (logView != null) logView.setText(logBuf.toString());
            }
        });
    }

    public void appendLog(String line) {
        if (!showing || line == null) return;
        logBuf.append(line).append('\n');
        if (logBuf.length() > 2000) logBuf.delete(0, logBuf.length() - 2000);
        final String t = logBuf.toString();
        ui.post(new Runnable() {
            @Override public void run() {
                if (logView != null) logView.setText(t);
            }
        });
    }
}
