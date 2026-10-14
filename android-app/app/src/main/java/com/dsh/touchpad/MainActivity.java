package com.dsh.touchpad;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 触摸注入板 —— 手机端控制台
 *
 * 底部固定 5 个页签(不用横向滚动,避免按钮被藏在屏幕外):
 *   状态 / 流程 / 控制 / 手势 / 日志
 */
public class MainActivity extends Activity {

    private static final int C_BG = 0xff0d1117;
    private static final int C_CARD = 0xff161b22;
    private static final int C_LINE = 0xff30363d;
    private static final int C_FG = 0xffc9d1d9;
    private static final int C_DIM = 0xff8b949e;
    private static final int C_ACC = 0xff58a6ff;
    private static final int C_OK = 0xff3fb950;
    private static final int C_WARN = 0xffd29922;
    private static final int C_ERR = 0xfff85149;

    private static final int TAB_STATUS = 0, TAB_SCRIPTS = 1, TAB_CONTROL = 2,
            TAB_GESTURE = 3, TAB_LOGS = 4;
    private static final String[] TAB_NAMES = {"状态", "流程", "控制", "手势", "日志"};

    /** 调试用:当前实例(仅给 DebugReceiver 切页签用) */
    static MainActivity CURRENT;

    private final Board board = new Board();

    /** 「控制走哪条路」的设置键 */
    private static final String PREF_TRANSPORT = "transport";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TimeKeeper time;                 // 网络时间(顶栏显示 + 定时用)
    private TextView clockView;
    private FlowStore flows;                 // 流程附加信息(开启/定时/目标App)
    private FloatingWindow floatWin;         // 悬浮窗(P7)
    private static final int REQ_FLOW_EDIT = 4101;
    private static final int REQ_SCHEDULE = 1001;
    private String pendingSchedName = "";
    private LinearLayout appCard;            // 流程页顶部的目标 App 卡片
    private final java.util.LinkedHashMap<String, TextView> countdownViews
            = new java.util.LinkedHashMap<String, TextView>();
    private FrameLayout rootFrame;           // 承载菜单浮层
    private LinearLayout headerBox;
    private String selectedAppName = "";     // 已选中的目标 App
    private String selectedAppPkg = "";
    private android.graphics.drawable.Drawable selectedAppIcon;

    private LinearLayout content;
    private LinearLayout tabBar;
    private TextView pillNet, pillHid, pillRun;

    /** 状态页最上面那行「连接方式」(蓝牙 / USB / WiFi) */
    private TextView linkRow;
    /** 蓝牙**控制**通道的状态行(和上面的 HID 不是一回事) */
    private TextView bleCtrlRow;

    /** 「☁️ 服务器」那一行 —— 让用户看到云端通没通 */
    private TextView serverRow;

    /** 「💡 灯」那一行 —— 显示板子现在是什么颜色/什么模式 */
    private TextView ledRow;
    /** 上次检测到的连接方式 + 上报过的值(变了才重报) */
    private Link.State lastLink = null;
    private String lastLinkSent = "";
    private boolean linkBusy = false;

    private int tab = TAB_STATUS;
    private JSONArray scripts = new JSONArray();
    private String lastScriptsSig = "";
    /**
     * 流程列表的缓存是否有效。
     *
     * ⚠️ 为什么要缓存(用户实测反馈):
     *      流程列表存在**板子**上,App 每次都要走蓝牙去 /api/scripts 读一遍。
     *      蓝牙一次往返 2~3 秒 ⇒ 每次切到「流程」页都要等 5 秒、一直显示"读取中…"。
     *      而流程列表**几乎不变** ⇒ 没必要时时去读。
     *
     *      现在:第一次进去读一次,以后切来切去都用缓存(秒开);
     *            只有**点「刷新列表」**、或**存/删流程之后**才重新拉。
     */
    private boolean scriptsLoaded = false;

    /**
     * 流程列表"脏了" —— 别处(编辑页保存 / 复制 / 删除)改过流程。
     * 用静态量是因为编辑页是**另一个 Activity**,拿不到这个实例。
     */
    public static volatile boolean scriptsDirty = false;
    private boolean autoRefresh = true;

    // 执行中:禁用所有按钮,避免"手点"和"注入"互相干扰
    private boolean running = false;
    private long lastDoneAt = 0;
    private LinearLayout scriptsContainer;   // 内容区根(加浮层用)
    private View busyMask;                   // 执行中的遮罩

    // 手势页的滑块参数
    private int swipeMs = 300;
    private int holdMs = 900;
    private int tapMs = 60;
    private int repeatTimes = 1;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        SharedPreferences sp = getSharedPreferences("board", MODE_PRIVATE);
        board.setHost(sp.getString("host", ""));

        time = new TimeKeeper(this);
        flows = new FlowStore(this);
        // ⚠️ 必须用单例:Activity 重建时不能新建第二个悬浮窗
        floatWin = FloatingWindow.get(this);
        if (getSharedPreferences("board", MODE_PRIVATE).getBoolean("floatWin", false)) {
            tryShowFloat();
        }
        selectedAppName = sp.getString("appName", "");
        selectedAppPkg  = sp.getString("appPkg", "");

        // 外层 FrameLayout:用来叠加右侧滑出的菜单
        FrameLayout shell = new FrameLayout(this);
        shell.setBackgroundColor(C_BG);

        rootFrame = shell;
        headerBox = new LinearLayout(this);
        headerBox.setOrientation(LinearLayout.VERTICAL);
        headerBox.setBackgroundColor(C_BG);
        headerBox.setPadding(0, statusBarHeight(), 0, 0);

        // ⚠️ 页签必须放在顶部:系统三键导航栏会盖住屏幕最底部,
        //    之前把页签放底部导致点不到(用户实测反馈)。
        headerBox.addView(buildHeader());
        headerBox.addView(buildTabBar());     // 固定在顶栏下方

        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(10), dp(10), dp(10), dp(28));
        sc.addView(content);

        // 用 FrameLayout 包一层,方便在执行时盖一个"执行中"遮罩,
        // 防止手指误触按钮(否则注入的触摸 + 手点会互相干扰)
        scriptsContainer = new LinearLayout(this);
        scriptsContainer.setOrientation(LinearLayout.VERTICAL);
        FrameLayout holder = new FrameLayout(this);
        holder.addView(sc, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        busyMask = buildBusyMask();
        busyMask.setVisibility(View.GONE);
        holder.addView(busyMask, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        headerBox.addView(holder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        shell.addView(headerBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(shell);

        setContentView(shell);

        // 给 Board 一个 Context(蓝牙连接要用),并按设置选传输方式
        board.attach(this);
        board.setTransport(getSharedPreferences("board", MODE_PRIVATE)
                .getInt(PREF_TRANSPORT, Board.T_AUTO));
        showTab(TAB_STATUS);
        sAppCtx = getApplicationContext();
        // 兜底:上次执行中被杀,息屏时间可能卡在"永不",恢复到用户原来的值
        KeepAwake.restoreIfStuck(this);

        ui.post(uiTick);
        ui.post(netTick);
        ui.post(pageTick);

        // 控制中心:后台上报手机信息 + 查 APK 更新(15 秒超时,失败静默)
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                Server.startupPing(MainActivity.this);
                checkAppUpdate();
            }
        }, 2500);

        // 启动提醒(权限 + 使用前准备)。延迟 1 秒,先让主界面画出来
        ui.postDelayed(new Runnable() {
            @Override public void run() { showStartupNotice(); }
        }, 1000);

        // 首次使用(没存过地址)→ 直接广播找一次,不用让用户手输 IP
        if (board != null && (board.host() == null || board.host().isEmpty())) {
            toast("首次使用,正在自动查找板子…");
            ui.postDelayed(new Runnable() {
                @Override public void run() { autoFindBoard(false); }
            }, 800);
        }
        CURRENT = this;

        // 顶栏时间:每秒走一次
        time.startTicking(new TimeKeeper.Tick() {
            @Override public void onTick(String text) {
                if (clockView != null) clockView.setText(text);
                if (floatWin != null && floatWin.isShowing()) {
                    floatWin.setHeader(selectedAppName, text);
                }
            }
        });
        // 首次启动默认同步一次网络时间
        time.sync(new TimeKeeper.SyncCb() {
            @Override public void done(boolean ok, String msg) {
                if (clockView != null) clockView.setText(time.nowText());
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (CURRENT == this) CURRENT = null;
        autoRefresh = false;
        ui.removeCallbacks(uiTick);
        ui.removeCallbacks(netTick);
        ui.removeCallbacks(pageTick);
        super.onDestroy();
    }

    /** 执行中的遮罩:吃掉所有触摸,并显示"执行中 + 停止" */
    private View buildBusyMask() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xE60d1117);
        box.setGravity(Gravity.CENTER);
        box.setClickable(true);          // 关键:吃掉触摸,不让它穿透到按钮
        box.setFocusable(true);

        TextView t = new TextView(this);
        t.setText("⏳ 正在执行流程");
        t.setTextColor(C_WARN);
        t.setTextSize(17);
        t.setGravity(Gravity.CENTER);
        box.addView(t);

        TextView tip = new TextView(this);
        tip.setText("执行期间已锁定按钮,避免注入的触摸误点界面\n(要停止请长按下面的按钮)");
        tip.setTextColor(C_DIM);
        tip.setTextSize(12);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, dp(8), 0, dp(18));
        box.addView(tip);

        Button stop = new Button(this);
        stop.setText("长按停止");
        stop.setTextSize(15);

        // ⚠️ 必须**长按**才停,不能用普通点击。
        //    原因:流程的注入点击可能正好落在屏幕中央(比如 T 0.5 0.5),
        //    普通点击会被误触发,把正在跑的流程停掉。实测踩过。
        //    1.5 秒远长于一般的 tap(30~60ms),H(长按)步骤才可能碰到,
        //    而那属于用户自己选的坐标,可以接受。
        stop.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                board.stop(new Board.Cb() {
                    @Override public void done(boolean ok, String body) {
                        toast(Board.messageOf(body));
                    }
                });
                return true;
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(180), dp(48));
        stop.setLayoutParams(lp);
        box.addView(stop);
        return box;
    }

    /** 进入/退出"执行中"状态 */
    private void setRunning(boolean run) {
        running = run;
        // 执行期间连拖柄一起隐藏,保证悬浮窗绝不挡住注入的触摸
        if (floatWin != null && floatWin.isShowing()) floatWin.setExecuting(run);
        if (!run) lastDoneAt = System.currentTimeMillis();
        if (busyMask != null) {
            busyMask.setVisibility(run ? View.VISIBLE : View.GONE);
        }
        paintTabs();
    }

    /** 按钮是否可点:执行中(以及结束后 1.2 秒内)一律禁用 */
    private boolean canTap() {
        // ⚠️ 必须带上 boardBusy(频率循环)。
        //    以前只看 running —— 频率循环**每次执行之间 running 是 false**,
        //    那个间隙里的注入点击就落到按钮上了。
        //    实测:误触开了「清空所有流程」的确认框,差一点全没了。
        if (running || boardBusy) return false;
        if (System.currentTimeMillis() - lastDoneAt < 1200) return false;
        return true;
    }

    // ================================================================ 顶栏
    private View buildHeader() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(C_CARD);
        bar.setPadding(dp(12), dp(10), dp(12), dp(8));

        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        r1.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("触摸注入板");
        title.setTextColor(C_FG);
        title.setTextSize(16);
        r1.addView(title);

        // 当前时间(精确到秒)。点一下立即重新同步网络时间
        clockView = new TextView(this);
        clockView.setText("--:--:--");
        clockView.setTextColor(C_ACC);
        clockView.setTextSize(15);
        clockView.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = dp(4);
        clockView.setLayoutParams(clp);
        clockView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doSyncNow(); }
        });
        clockView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { askTimeSettings(); return true; }
        });
        r1.addView(clockView);

        // 右侧留白,把三横推到最右。
        // ⚠️ 这里**必须**给明确高度:裸 View 在 AT_MOST 测量下会返回
        //    "可用空间的全部高度"(View.getDefaultSize 的行为),
        //    用 WRAP_CONTENT 会把整屏吃掉,导致页签和内容都被挤成 0 高。
        //    实测踩过,别改回 WRAP_CONTENT。
        View spacer = new View(this);
        r1.addView(spacer, new LinearLayout.LayoutParams(0, dp(1), 1f));

        TextView menuBtn = MenuPanel.hamburger(this);
        menuBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openMenu(); }
        });
        r1.addView(menuBtn);
        bar.addView(r1);

        return bar;
    }

    private TextView pill(String text, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(11);
        t.setPadding(dp(6), dp(2), dp(6), dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(5);
        t.setLayoutParams(lp);
        return t;
    }

    // ================================================================ 底部页签
    private View buildTabBar() {
        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(C_CARD);

        // 每个页签 = 文字 + 底部 2dp 指示条
        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int idx = i;
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView t = new TextView(this);
            t.setText(TAB_NAMES[i]);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(15);
            t.setPadding(0, dp(12), 0, dp(10));
            col.addView(t);

            View bar = new View(this);
            bar.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
            col.addView(bar);

            col.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showTab(idx); }
            });
            tabBar.addView(col);
        }
        paintTabs();
        return tabBar;
    }

    private void paintTabs() {
        for (int i = 0; i < TAB_NAMES.length; i++) {
            LinearLayout col = (LinearLayout) tabBar.getChildAt(i);
            TextView t = (TextView) col.getChildAt(0);
            View bar = col.getChildAt(1);
            boolean on = (i == tab);
            t.setTextColor(on ? C_ACC : C_DIM);
            bar.setBackgroundColor(on ? C_ACC : 0x00000000);
            col.setBackgroundColor(on ? 0xff1c2128 : C_CARD);
        }
    }

    /** 顶栏/菜单上的"当前目标 App"提示 */
    private void updateAppBar() {
        if (selectedAppName == null || selectedAppName.isEmpty()) return;
    }

    /** 按名字切页签(调试广播用) */
    void showTabByName(String name) {
        for (int i = 0; i < TAB_NAMES.length; i++) {
            if (TAB_NAMES[i].equals(name)) { showTab(i); return; }
        }
    }

    private void showTab(int i) {
        tab = i;
        paintTabs();
        content.removeAllViews();
        content.scrollTo(0, 0);
        switch (i) {
            case TAB_STATUS:   buildStatusTab();   break;
            case TAB_SCRIPTS:  buildScriptsTab();  break;
            case TAB_CONTROL:  buildControlTab();  break;
            case TAB_GESTURE:  buildGestureTab();  break;
            case TAB_LOGS:     buildLogsTab();     break;
        }
    }

    // ================================================================ 通用控件
    private LinearLayout card(String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(C_CARD);
        c.setPadding(dp(12), dp(10), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        c.setLayoutParams(lp);
        if (title != null) {
            TextView t = new TextView(this);
            t.setText(title);
            t.setTextColor(C_DIM);
            t.setTextSize(12);
            t.setPadding(0, 0, 0, dp(8));
            c.addView(t);
        }
        return c;
    }

    private View kv(String k, String v, int valueColor) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(3), 0, dp(3));
        TextView tk = new TextView(this);
        tk.setText(k);
        tk.setTextColor(C_DIM);
        tk.setTextSize(13);
        tk.setWidth(dp(84));
        r.addView(tk);
        TextView tv = new TextView(this);
        tv.setText(v);
        tv.setTextColor(valueColor == 0 ? C_FG : valueColor);
        tv.setTextSize(13);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(tv);
        return r;
    }

    private TextView hint(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(C_DIM);
        t.setTextSize(12);
        return t;
    }

    /** 一行按钮(自动均分宽度,不会跑到屏幕外) */
    private LinearLayout btnRow(String label, String[][] items,
                                View.OnClickListener h) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        if (label != null) {
            TextView t = new TextView(this);
            t.setText(label);
            t.setTextColor(C_DIM);
            t.setTextSize(12);
            t.setPadding(0, dp(8), 0, dp(4));
            box.addView(t);
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (String[] it : items) {
            Button b = new Button(this);
            b.setText(it[0]);
            b.setTag(it[1]);
            b.setTextSize(13);
            b.setPadding(dp(2), 0, dp(2), 0);
            b.setMinimumWidth(0);
            b.setOnClickListener(new View.OnClickListener() {
                private final View.OnClickListener real = h;
                @Override public void onClick(View v) {
                    if (!canTap()) { toast("执行中,请稍候"); return; }
                    real.onClick(v);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, dp(46), 1f);
            lp.rightMargin = dp(5);
            b.setLayoutParams(lp);
            row.addView(b);
        }
        box.addView(row);
        return box;
    }

    private Button flatBtn(String text, String tag, View.OnClickListener h) {
        Button b = new Button(this);
        b.setText(text);
        b.setTag(tag);
        b.setTextSize(13);
        b.setMinimumWidth(0);
        b.setOnClickListener(h);
        return b;
    }

    // ================================================================ 状态
    private LinearLayout statusCard;

    private void buildStatusTab() {
        // ⚠️ 「连接方式」单独一张卡 —— 不能放进 statusCard。
        //    fetchStatus 失败时会 statusCard.removeAllViews(),
        //    放里面会被一起清掉,用户反而看不到"到底哪条链路通"(实测踩过)。
        LinearLayout linkCard = card("连接方式");
        linkRow = new TextView(this);
        linkRow.setText("检测中…");
        linkRow.setTextSize(13);
        linkRow.setTextColor(C_FG);
        linkRow.setPadding(dp(2), dp(2), dp(2), dp(2));
        linkCard.addView(linkRow);
        TextView linkHint = hint("蓝牙 HID = 板子的触摸屏已接入;USB = 直插;"
                + "WiFi = App 能读到板子。每 5 秒自动刷新。");
        linkCard.addView(linkHint);

        // ---- 蓝牙**控制**通道的状态 + 连接按钮 ----
        //  ⚠️ 和上面的"蓝牙 HID"是两回事:
        //     HID 是板子往手机注入触摸(系统配对就行)
        //     控制是 App 读写板子(要我们自己做 GATT 客户端)
        bleCtrlRow = new TextView(this);
        bleCtrlRow.setTextSize(13);
        bleCtrlRow.setTextColor(C_FG);
        bleCtrlRow.setPadding(dp(2), dp(6), dp(2), dp(2));
        linkCard.addView(bleCtrlRow);

        // ☁️ 服务器连通状态 —— 原来所有云端调用都静默失败,用户看不到
        serverRow = new TextView(this);
        serverRow.setTextSize(13);
        serverRow.setTextColor(C_DIM);
        serverRow.setPadding(dp(2), dp(6), dp(2), dp(2));
        linkCard.addView(serverRow);

        // 💡 灯的状态 —— 黄只亮 2~3 秒,靠盯板子根本看不清,直接显示出来
        ledRow = new TextView(this);
        ledRow.setTextSize(13);
        ledRow.setTextColor(C_DIM);
        ledRow.setPadding(dp(2), dp(6), dp(2), dp(2));
        ledRow.setText("💡 灯:—");
        linkCard.addView(ledRow);

        linkCard.addView(btnRow(null, new String[][]{
                {"🔵 连接蓝牙控制", "conn"}, {"⚙ 控制方式", "mode"},
                {"💡 点亮板子", "led"}, {"☁️ 测服务器", "srv"}
        }, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if ("led".equals(v.getTag())) { showLedDialog(); return; }
                if ("srv".equals(v.getTag())) {
                    toast("正在测服务器连通…");
                    Server.ping(MainActivity.this, new Server.Cb() {
                        @Override public void done(final boolean ok, final String body) {
                            runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    updateServerRow();
                                    toast(ok ? "✅ 服务器通" : "❌ 连不上服务器");
                                }
                            });
                        }
                    });
                    return;
                }
                if ("conn".equals(v.getTag())) {
                    toast("正在连接蓝牙控制通道…");
                    Ble.get().connect(MainActivity.this, new Ble.Cb() {
                        @Override public void done(String body) {
                            runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    toast(Ble.get().stateText);
                                    updateBleCtrlRow();
                                    if (Ble.get().ready) fetchStatus();
                                }
                            });
                        }
                    });
                } else {
                    pickTransport();
                }
            }
        }));
        content.addView(linkCard);

        statusCard = card("板子状态");
        statusCard.addView(hint("正在读取…"));
        content.addView(statusCard);

        LinearLayout dev = card("手机侧自检(在本机执行,不经板子)");
        final TextView out = new TextView(this);
        out.setTextColor(C_DIM);
        out.setTextSize(12);
        out.setPadding(0, dp(4), 0, 0);
        dev.addView(btnRow(null, new String[][]{
                {"检查 HID 设备", "check"}, {"屏幕信息", "screen"}
        }, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if ("screen".equals(v.getTag())) {
                    out.setText("屏幕: " + screenW() + " x " + screenH()
                            + "\n底部三键导航坐标:\n"
                            + "  最近任务 0.22,0.963\n  回桌面   0.50,0.963\n  返回     0.78,0.963");
                } else {
                    out.setText(checkHid());
                }
            }
        }));
        dev.addView(out);
        content.addView(dev);

        LinearLayout sys = card("维护");
        sys.addView(btnRow(null, new String[][]{
                {"停止脚本", "stop"}, {"抬起触点", "releaseall"}
        }, new View.OnClickListener() {
            @Override public void onClick(View v) { doControl(String.valueOf(v.getTag())); }
        }));
        // 换网络后板子 IP 会变 —— 一键广播找回来,不用手输
        sys.addView(btnRow(null, new String[][]{
                {"🔍 自动查找板子", "findboard"}
        }, new View.OnClickListener() {
            @Override public void onClick(View v) { autoFindBoard(true); }
        }));

        // "蓝牙连不上 / 手机里没有输入设备"时的修复入口
        sys.addView(btnRow(null, new String[][]{
                {"修复蓝牙连接", "fixble"}
        }, new View.OnClickListener() {
            @Override public void onClick(View v) {
                Dialogs.confirm(MainActivity.this, "修复蓝牙连接",
                        "板子会断开当前连接并重新广播。\n\n"
                        + "什么时候用:手机蓝牙里显示「已连接」,但触摸注入没反应\n"
                        + "(说明手机侧的 HID 通道卡住了)。\n\n"
                        + "修复后可能要等 10 秒左右,或到蓝牙设置里点一下设备重新连接。",
                        "开始修复", new Dialogs.OnOk() {
                            @Override public void onOk() {
                                board.resetBle(new Board.Cb() {
                                    @Override public void done(boolean ok, String body) {
                                        toast(Board.messageOf(body));
                                    }
                                });
                            }
                        });
            }
        }));
        sys.addView(btnRow(null, new String[][]{
                {"停止定时", "schedstop"}, {"重启板子", "reboot"}, {"恢复出厂", "factory"}
        }, new View.OnClickListener() {
            @Override public void onClick(View v) {
                String a = String.valueOf(v.getTag());
                if ("reboot".equals(a) || "factory".equals(a)) sysOp(a);
                else doControl(a);
            }
        }));
        content.addView(sys);

        fetchStatus();
    }

    /** 读手机本机信息:内核里有没有我们的 HID 设备(用 /proc 读,不需要额外权限) */
    private String checkHid() {
        StringBuilder sb = new StringBuilder();
        try {
            // /proc/bus/input/devices 里能直接看到所有输入设备的名字
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/bus/input/devices"));
            String line;
            int n = 0;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("N: Name=") && line.contains("ESP32")) {
                    sb.append("✅ ").append(line.substring(8).replace("\"", "")).append('\n');
                    n++;
                }
            }
            br.close();
            if (n == 0) {
                sb.append("❌ 内核里没有我们的 HID 设备\n");
                sb.append("→ 手机蓝牙关一下再打开,让它重新枚举");
            } else {
                sb.append("共 ").append(n).append(" 个设备,触摸注入可用");
            }
        } catch (Exception e) {
            sb.append("读取失败: ").append(e.getMessage())
              .append("\n(Android 10+ 限制读 /proc,属正常)");
        }
        return sb.toString().trim();
    }

    private int screenW() { return getResources().getDisplayMetrics().widthPixels; }
    private int screenH() { return getResources().getDisplayMetrics().heightPixels; }

    private void fetchStatus() {
        board.status(new Board.Cb() {
            @Override public void done(boolean ok, String body) {
                pollBusy = false;
                if (!ok) {
                    setPill(pillNet, "未连接", C_ERR);
                    // 连不上就先别急着让用户手填 —— 静默广播找一次。
                    // 换网络 / 板子重启后 IP 会变,这是最常见的原因。
                    failCount++;
                    if (failCount >= 2 && !finding && !triedAutoFind) {
                        triedAutoFind = true;
                        setPill(pillNet, "查找中…", C_WARN);
                        autoFindBoard(false);
                    }
                    if (statusCard != null) {
                        statusCard.removeAllViews();
                        statusCard.addView(hint("连不上板子"));
                        statusCard.addView(hint(body));
                        statusCard.addView(hint("检查:① 手机和板子在同一 WiFi ② 地址填对"));
                        statusCard.addView(hint("(已自动广播查找,也可以在下面手动点「🔍 自动查找板子」)"));
                    }
                    return;
                }
                failCount = 0;
                try {
                    JSONObject o = new JSONObject(body);
                    String mode = Board.jstr(o, "mode");
                    String ip = Board.jstr(o, "ip");
                    boolean hid = Board.jbool(o, "hid");
                    lastStatus = o;
                    boardMac = Board.jstr(o, "mac");
                    JSONObject run = o.optJSONObject("run");
                    pendingFwUpdate = o.optJSONObject("fwUpdate");
                    JSONObject sched = o.optJSONObject("sched");

                    // 读到板子就顺手把 MAC 缓存下来(以后读不到也能上报连接方式)
                    try {
                        cacheBoardMac(new JSONObject(body).optString("mac", ""));
                    } catch (Throwable ignored) {}
                    setPill(pillNet, ip, "STA".equals(mode) ? C_OK : C_WARN);
                    setPill(pillHid, hid ? "HID✓" : "HID✗", hid ? C_OK : C_ERR);

                    boolean running = Board.jbool(run, "running");
                    // 💡 顺便把灯的状态显示出来
                    {
                        JSONObject _r = null;
                        try { _r = new JSONObject(body); } catch (Throwable ignored) {}
                        updateLedRow(_r == null ? null : _r.optString("led", ""));
                    }
                    runningName = running ? Board.jstr(run, "lastName") : "";
                    // 页面推送的开关:跑的时候推,跑完再推 3 秒(收尾)
                    if (running) { runningNow = true; pagePushUntil = System.currentTimeMillis() + 3000; }
                    else if (runningNow) { runningNow = false; pagePushUntil = System.currentTimeMillis() + 3000; }
                    // "板子在忙" = 正在跑流程 或 频率循环开着
                    // 用它来驱动"保持屏幕常亮",这样**所有触发路径都覆盖**:
                    // App 点的、网页点的、定时触发的、频率循环的
                    boolean schedOn = (sched != null && sched.optBoolean("active", false));
                    boardBusy = running || schedOn;

                    // 频率状态显示到悬浮窗(板子侧在跑,到时间自己停)
                    if (floatWin != null && floatWin.isShowing()) {
                        floatWin.setFreq(schedOn,
                                sched == null ? 0 : sched.optLong("intervalMs", 0),
                                sched == null ? 0 : sched.optLong("remainMs", 0),
                                sched == null ? "" : sched.optString("name", ""));
                    }
                    boolean lastOk = run == null || run.optBoolean("lastOk", true);
                    setPill(pillRun, running ? "运行中" : (lastOk ? "空闲" : "失败"),
                            running ? C_WARN : (lastOk ? C_OK : C_ERR));

                    statusCard.removeAllViews();
                    statusCard.addView(kv("网络", mode + " · " + ip, 0));
                    statusCard.addView(kv("访问地址", Board.jstr(o, "url"), C_ACC));
                    statusCard.addView(kv("HID", hid ? "✅ 就绪" : "❌ 未连接",
                            hid ? C_OK : C_ERR));
                    statusCard.addView(kv("空闲内存", (Board.jint(o, "heap") / 1024) + " KB", 0));
                    statusCard.addView(kv("运行时长", uptime(Board.jint(o, "uptime")), 0));
                    statusCard.addView(kv("流程数", Board.jint(o, "scriptCount") + " 个", 0));
                    statusCard.addView(kv("执行次数", Board.jint(run, "runCount") + " 次", 0));
                    String ln = Board.jstr(run, "lastName");
                    if (ln.length() > 0) statusCard.addView(kv("上次执行", ln, 0));
                    if (running) {
                        statusCard.addView(kv("进度",
                                Board.jint(run, "step") + "/" + Board.jint(run, "totalSteps")
                                        + " 步 · " + Board.jint(run, "elapsedMs") + "ms", C_WARN));
                    }
                    String le = Board.jstr(run, "lastError");
                    if (le.length() > 0) statusCard.addView(kv("上次错误", le, C_ERR));
                    if (sched != null && sched.optBoolean("active", false)) {
                        statusCard.addView(kv("定时循环",
                                Board.jstr(sched, "name") + " 每 "
                                        + Board.jint(sched, "intervalSec") + " 秒", C_WARN));
                    }
                    // 控制中心说有新固件 → 在这里给个入口(App 只提示,不自动升)
                    addFirmwareRow();
                } catch (Exception e) {
                    statusCard.removeAllViews();
                    statusCard.addView(hint("解析失败: " + e.getMessage()));
                }
            }
        });
    }

    /** 板子固件版本 + 「有新版本」提示(App 只提示,确认后才让板子去拉) */
    private void addFirmwareRow() {
        if (statusCard == null) return;
        try {
            JSONObject f = pendingFwUpdate;
            String localFw = Board.jstr(lastStatus, "fw");
            if (localFw.isEmpty()) localFw = "?";

            if (f == null || !f.optBoolean("hasUpdate", false)) {
                statusCard.addView(kv("板子固件", localFw, 0));
                return;
            }
            final String ver = f.optString("version");
            statusCard.addView(kv("板子固件", localFw + " → " + ver, C_WARN));

            LinearLayout box = Dialogs.column(this);
            box.addView(Dialogs.value(this, "板子固件有新版本:" + ver, C_ACC));
            String notes = f.optString("notes");
            box.addView(Dialogs.label(this,
                    "当前:" + localFw + " → 新:" + ver
                    + "\n大小:" + (f.optLong("size") / 1024) + " KB"
                    + (notes.isEmpty() ? "" : "\n\n" + notes)
                    + "\n\n升级过程约 20 秒,板子会重启。\n"
                    + "⚠️ 正在跑抢票流程时别升级,会打断。"));
            TextView btn = Dialogs.value(this, "点这里升级板子固件", C_WARN);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showFwDialog(ver, f); }
            });
            statusCard.addView(btn);
        } catch (Throwable ignored) {}
    }

    private void showFwDialog(String ver, JSONObject f) {
        LinearLayout box = Dialogs.column(this);
        box.addView(Dialogs.value(this, "升级板子固件到 " + ver, C_ACC));
        String notes = f.optString("notes");
        box.addView(Dialogs.label(this,
                "当前:" + Board.jstr(lastStatus, "fw") + "  →  新:" + ver
                + "\n大小:" + (f.optLong("size") / 1024) + " KB"
                + (notes.isEmpty() ? "" : "\n\n更新说明:\n" + notes)
                + "\n\n板子会自己从控制中心下载(约 20 秒),然后重启。\n"
                + "升级不会丢流程。"));
        box.addView(Dialogs.label(this, "⚠️ 升级期间别断电,也别让手机离开 WiFi。"));
        Dialogs.show(this, "升级板子固件", box, "开始升级", new Dialogs.OnOk() {
            @Override public void onOk() {
                toast("正在让板子下载固件…");
                board.otaFromServer(new Board.Cb() {
                    @Override public void done(boolean ok, String body) {
                        toast(Board.messageOf(body));
                        if (ok) {
                            // 板子会重启,等一会儿再刷新
                            ui.postDelayed(new Runnable() {
                                @Override public void run() { fetchStatus(); }
                            }, 20000);
                        }
                    }
                });
            }
        }, "取消");
    }

    private void setPill(TextView t, String s, int color) {
        if (t == null) return;
        t.setText(s);
        t.setTextColor(color);
    }

    private String uptime(int sec) {
        if (sec >= 3600) return (sec / 3600) + " 小时 " + (sec % 3600 / 60) + " 分";
        if (sec >= 60) return (sec / 60) + " 分 " + (sec % 60) + " 秒";
        return sec + " 秒";
    }

    // ================================================================ 流程
    private LinearLayout scriptListBox;

    private void buildScriptsTab() {
        // ---- 顶部:目标 App 卡片(需求:把 App 选择放到这里) ----
        appCard = card("目标 App");
        content.addView(appCard);
        renderAppCard();

        // ---- 流程列表 ----
        LinearLayout head = card(null);
        head.addView(btnRow(null, new String[][]{
                {"☁️ 同步服务器", "reload"}, {"新建流程", "new"}
        }, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if ("new".equals(v.getTag())) newFlow();
                else syncFromServer();
            }
        }));
        // 和服务器相关的两个操作单独一行(用户要求)
        head.addView(btnRow(null, new String[][]{
                {"⬆️ 上传流程", "up"}, {"🗑 清空本地流程", "clr"}
        }, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if ("up".equals(v.getTag())) uploadAllFlows();
                else clearLocalFlows();
            }
        }));
        content.addView(head);

        scriptListBox = card(null);
        content.addView(scriptListBox);
        if (scriptsLoaded && scripts.length() > 0) {
            // 有缓存 ⇒ 直接画,不闪"读取中…",也不用等蓝牙
            renderScripts();
        } else {
            scriptListBox.addView(hint("读取中…"));
            lastScriptsSig = "";
            loadScripts();
        }
    }

    /** 目标 App 卡片:图标 + 名字;没选就提示去选 */
    private void renderAppCard() {
        if (appCard == null) return;
        appCard.removeAllViews();

        if (selectedAppPkg == null || selectedAppPkg.isEmpty()) {
            TextView t = hint("还没有选目标 App。流程运行前会先启动它。");
            appCard.addView(t);
            appCard.addView(btnRow(null, new String[][]{{"选择 App", "pick"}},
                    new View.OnClickListener() {
                        @Override public void onClick(View v) { showAppList(); }
                    }));
            return;
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        android.graphics.drawable.Drawable ic = appIconOf(selectedAppPkg);
        if (ic != null) iv.setImageDrawable(ic);
        row.addView(iv);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, 0, 0);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView n = new TextView(this);
        n.setText(selectedAppName);
        n.setTextColor(C_FG);
        n.setTextSize(16);
        col.addView(n);
        TextView pk = new TextView(this);
        pk.setText(selectedAppPkg);
        pk.setTextColor(C_DIM);
        pk.setTextSize(11);
        col.addView(pk);
        row.addView(col);
        appCard.addView(row);

        appCard.addView(btnRow(null, new String[][]{
                {"更换 App", "pick"}, {"去启动看看", "launch"}
        }, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if ("pick".equals(v.getTag())) showAppList();
                else launchSelectedApp();
            }
        }));
    }

    /** 取某个包的图标(拿不到返回 null,此时不展示图标) */
    private android.graphics.drawable.Drawable appIconOf(String pkg) {
        try {
            return getPackageManager().getApplicationIcon(pkg);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 显示流程列表。
     *
     * **有缓存就直接画,不发请求** —— 切页签秒开 ✅
     * 想强制拿最新的,用 {@link #refreshScripts()} ✅
     */
    private void loadScripts() {
        // UI 没建好就别画了
        if (scriptListBox == null) return;
        if (scriptsDirty) { scriptsLoaded = false; scriptsDirty = false; }
        if (scriptsLoaded) { renderScripts(); return; }
        fetchScripts();
    }

    /**
     * 「刷新列表」按钮 —— **从服务器拉最新的,再同步到板子,然后显示**。
     *
     * ⚠️⚠️ 这里曾经做错(用户指出):
     *      我第一版把「刷新列表」做成"重新读一遍**板子**"。
     *      但流程是**存在服务器**上的(第 ⑥ 项就是为这个)——
     *      服务器才是远程存储,板子只是**执行端**。
     *      只读板子的话,服务器上新建的流程**永远下不来**。
     *
     *      正确的流向:
     *        服务器(权威) ──拉下来──> App ──写进板子──> 板子(执行)
     *                                     ↘ 同时更新本地状态
     */
    /**
     * 「☁️ 同步服务器」—— 把板子同步成服务器的样子:
     *   ① 服务器启用的 → 写进板子
     *   ② 板子上有、服务器没有的 → **列出来让用户确认**,确认后删掉
     *
     * ⚠️ ② **不自动删** —— 之前"默默按服务器状态删板子"的逻辑两次清空了板子。
     *    现在把决定权交回给用户 ✅
     */
    private void syncFromServer() {
        toast("正在从服务器同步…");
        FlowSync.pull(this, board, true, new FlowSync.Cb() {
            @Override public void done(final boolean ok, final String info) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        boardLog("同步服务器 → " + info);
                        scriptsLoaded = false;
                        lastScriptsSig = "";
                        if (tab == TAB_SCRIPTS) fetchScripts();
                        if (!ok) { toast("❌ " + info); return; }
                        final java.util.List<String> extra = FlowSync.pendingExtra;
                        if (extra == null || extra.isEmpty()) {
                            toast("✅ 已同步(" + info + ")");
                            return;
                        }
                        // 列出多余的,让用户决定删不删
                        StringBuilder sb = new StringBuilder();
                        sb.append("服务器上没有这些流程,板子上却还有:\n\n");
                        for (String n : extra) sb.append("· ").append(n).append("\n");
                        sb.append("\n要删掉它们吗?(删了就只剩服务器上启用的那些)");
                        new android.app.AlertDialog.Builder(MainActivity.this)
                                .setTitle("同步:发现多余的流程")
                                .setMessage(sb.toString())
                                .setPositiveButton("删掉这 " + extra.size() + " 条",
                                        new android.content.DialogInterface.OnClickListener() {
                                    @Override public void onClick(android.content.DialogInterface d, int w) {
                                        deleteFlowsSequential(extra, 0, 0);
                                    }
                                })
                                .setNegativeButton("保留", null)
                                .show();
                    }
                });
            }
        });
    }

    /** 串行删掉一批流程 */
    private void deleteFlowsSequential(final java.util.List<String> names, final int i, final int done) {
        if (i >= names.size()) {
            toast("✅ 已删掉 " + done + " 条");
            FlowStore st = new FlowStore(this);
            for (String n : names) st.remove(n);
            scriptsLoaded = false;
            lastScriptsSig = "";
            fetchScripts();
            return;
        }
        board.delete(names.get(i), new Board.Cb() {
            @Override public void done(boolean ok, String body) {
                deleteFlowsSequential(names, i + 1, done + (ok ? 1 : 0));
            }
        });
    }

    /** 「⬆️ 上传流程」—— 把板子上**所有**流程传到服务器(同名覆盖、不同名新建) */
    private void uploadAllFlows() {
        toast("正在上传板子上的流程到服务器…");
        FlowSync.uploadAll(this, board, new FlowSync.Cb() {
            @Override public void done(final boolean ok, final String info) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        boardLog("上传流程 → " + info);
                        toast(ok ? ("✅ " + info) : ("❌ " + info));
                    }
                });
            }
        });
    }

    /** 「🗑 清空本地流程」—— 删掉板子上所有流程(二次确认) */
    private void clearLocalFlows() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("清空本地流程")
                .setMessage("会删掉板子上所有流程,不可撤销。\n\n确认吗?")
                .setPositiveButton("确认清空", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        toast("正在清空…");
                        FlowSync.clearBoard(MainActivity.this, board, new FlowSync.Cb() {
                            @Override public void done(final boolean ok, final String info) {
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        boardLog("清空本地 → " + info);
                                        toast(ok ? ("✅ " + info) : ("❌ " + info));
                                        refreshScripts();
                                    }
                                });
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshScripts() {
        if (scriptListBox == null) return;
        scriptsLoaded = false;
        scriptsDirty = false;
        lastScriptsSig = "";
        // ⚠️⚠️ **不要阻塞界面等服务器**(用户实测:"大概率卡在
        //    正在从服务器拉取流程 → 读取中 → 还没有流程")。
        //
        //    原因:原来把界面清空、写"正在从服务器拉取…",然后**等**服务器拉完
        //    再读板子。蓝牙 + 服务器两段路都很慢(可能十几秒),
        //    期间界面一片空白;一旦中间哪步失败就停在"还没有流程"上。
        //
        //    改成:**先把现有的列表照常显示出来,服务器同步放到后台做**,
        //    同步完了再静默刷新一次。用户点刷新立刻有反馈,不用干等。
        lastScriptsSig = "";
        fetchScripts();                     // ① 立刻显示板子上现有的

        // ② 后台从服务器拉全量,拉完再静默刷一次
        FlowSync.pull(this, board, true, new FlowSync.Cb() {
            @Override public void done(final boolean ok, final String info) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        boardLog("刷新列表 → " + (ok ? info : ("服务器拉取失败:" + info)));
                        scriptsLoaded = false;
                        lastScriptsSig = "";
                        if (tab == TAB_SCRIPTS) fetchScripts();
                    }
                });
            }
        });
    }

    /** 读列表失败/读回空时的重试次数 */
    private int scriptsRetry = 0;

    /** 真正发请求读流程列表 */
    private void fetchScripts() {
        if (scriptListBox == null) return;
        board.scripts(new Board.Cb() {
            @Override public void done(boolean ok, String body) {
                if (!ok) {
                    // ⚠️⚠️ **不要因为一次失败就把列表清空** ——
                    //    实测蓝牙读列表偶尔会丢响应(读回 0 条 / 超时),
                    //    原来一失败就 removeAllViews + "读取失败",
                    //    用户看到的就是"列表空了"。
                    //    现在:保留已经显示的内容,然后**重试**。
                    if (scriptsRetry < 5) {
                        scriptsRetry++;
                        boardLog("读流程列表失败,重试 " + scriptsRetry + "/5");
                        ui.postDelayed(new Runnable() {
                            @Override public void run() { fetchScripts(); }
                        }, 8000);      // ⚠️ 要等**8 秒** —— 同步在写流程时
                                               //    整条 BLE 队列都被 SAVE 占着(每个
                                               //    SAVE 写 Flash 要好几秒),等 1.2 秒
                                               //    根本轮不到 LIST。
                        return;
                    }
                    scriptsRetry = 0;
                    if (scriptListBox.getChildCount() == 0) {
                        scriptListBox.addView(hint("读取失败:" + body));
                    }
                    return;
                }
                try {
                    JSONObject o = new JSONObject(body);
                    scripts = o.optJSONArray("scripts");
                    if (scripts == null) scripts = new JSONArray();
                } catch (Exception e) {
                    scriptListBox.removeAllViews();
                    scriptListBox.addView(hint("解析失败: " + e.getMessage()));
                    return;
                }
                // 只在数据真的变了才重绘,避免按钮位置跳动
                // (重绘瞬间按下会把点击套到另一个按钮上,实测踩过)
                // 读回 0 条也可能是**响应丢了**,重试几次再说
                if (scripts.length() == 0 && scriptsRetry < 5) {
                    scriptsRetry++;
                    boardLog("读回 0 条,重试 " + scriptsRetry + "/5");
                    ui.postDelayed(new Runnable() {
                        @Override public void run() { fetchScripts(); }
                    }, 1200);
                    return;
                }
                scriptsRetry = 0;
                scriptsLoaded = true;        // 缓存有效了
                String sig = body.length() + ":" + body.hashCode();
                if (!sig.equals(lastScriptsSig)) {
                    lastScriptsSig = sig;
                    nameToIndex.clear();
                    for (int k = 0; k < scripts.length(); k++) {
                        JSONObject so = scripts.optJSONObject(k);
                        if (so != null) {
                            nameToIndex.put(Board.jstr(so, "name"), Board.jint(so, "index"));
                        }
                    }
                    renderScripts();
                }
            }
        });
    }

    /**
     * 流程列表:精简展示(不显示每步指令),带开启/暂停与定时状态。
     * 倒计时由 {@link #refreshCountdowns()} 只改文字,不重建列表。
     */
    /**
     * 流程列表排序(按需求):
     *   ① 正在执行的   最上面
     *   ② 已开启的     其次
     *   ③ 其余         按创建时间倒序(新建的在前)
     *   ④ 已过期的     **沉到最下方**(不清理,只是排后面)
     */
    private void sortScripts() {
        if (scripts == null || scripts.length() <= 1) return;
        java.util.List<JSONObject> list = new java.util.ArrayList<JSONObject>();
        for (int i = 0; i < scripts.length(); i++) {
            JSONObject o = scripts.optJSONObject(i);
            if (o != null) list.add(o);
        }
        final String runName = runningName == null ? "" : runningName;
        java.util.Collections.sort(list, new java.util.Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) {
                int pa = priorityOf(a, runName), pb = priorityOf(b, runName);
                if (pa != pb) return pa - pb;
                if (pa == 2) {
                    long ca = flows.get(Board.jstr(a, "name")).createdAt;
                    long cb = flows.get(Board.jstr(b, "name")).createdAt;
                    if (ca != cb) return cb > ca ? 1 : -1;   // 新的在前
                }
                return Board.jint(a, "index") - Board.jint(b, "index");
            }
        });
        JSONArray arr = new JSONArray();
        for (JSONObject o : list) arr.put(o);
        scripts = arr;
    }

    /**
     * 排序优先级(数字越小越靠上):
     *   0 执行中   1 已开启   2 正常   3 已过期
     *
     * 注意:已过期只是**沉底**,不会自动清理 ——
     * "这个流程本来定过某个时间但没跑成"本身就是有用的信息。
     */
    private int priorityOf(JSONObject o, String runName) {
        String nm = Board.jstr(o, "name");
        if (!runName.isEmpty() && runName.equals(nm)) return 0;   // 执行中
        FlowStore.Meta m = flows.get(nm);
        if (m.enabled) return 1;                                   // 已开启
        long t = m.targetMillis();
        if (t > 0 && m.expired(time.nowMs())) return 3;            // 已过期 → 沉底
        return 2;
    }

    private void renderScripts() {
        // ⚠️⚠️ 必须判空 —— 踩过的崩溃:
        //      FlowSync 在启动时拉流程,回来后调 loadScripts(),
        //      而那一刻 scriptListBox 还没被 onCreate 赋值(UI 还没建完),
        //      直接 NPE 把 App 崩掉(实测 crash log 就在 renderScripts:1066)。
        //      任何"UI 就绪前触发 loadScripts()"的路径都会踩这个坑,
        //      所以保护要加在**渲染这一层**,而不是每个调用点。
        if (scriptListBox == null) return;
        sortScripts();
        scriptListBox.removeAllViews();
        countdownViews.clear();

        if (scripts.length() == 0) {
            scriptListBox.addView(hint("还没有流程,点「新建流程」建一个"));
            return;
        }

        for (int i = 0; i < scripts.length(); i++) {
            final JSONObject s = scripts.optJSONObject(i);
            if (s == null) continue;
            final int idx = Board.jint(s, "index");
            final String name = Board.jstr(s, "name");
            final int steps = Board.jint(s, "steps");
            final FlowStore.Meta meta = flows.get(name);

            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(0, dp(10), 0, dp(10));

            // 第一行:状态圆点 + 名字 + 步数
            LinearLayout l1 = new LinearLayout(this);
            l1.setOrientation(LinearLayout.HORIZONTAL);
            l1.setGravity(Gravity.CENTER_VERTICAL);

            TextView dot = new TextView(this);
            dot.setText(meta.enabled ? "●" : "○");
            dot.setTextColor(meta.enabled ? C_OK : C_DIM);
            dot.setTextSize(14);
            dot.setPadding(0, 0, dp(6), 0);
            l1.addView(dot);

            TextView t = new TextView(this);
            t.setText(name);
            t.setTextColor(meta.enabled ? C_FG : C_DIM);
            t.setTextSize(15);
            t.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            l1.addView(t);

            TextView st = new TextView(this);
            st.setText(steps + " 步");
            st.setTextColor(C_DIM);
            st.setTextSize(11);
            l1.addView(st);
            box.addView(l1);

            // 第二行:定时状态(倒计时会实时更新这个 TextView)
            TextView cd = new TextView(this);
            cd.setTextColor(C_WARN);
            cd.setTextSize(11);
            cd.setPadding(dp(20), dp(4), 0, dp(2));
            box.addView(cd);
            countdownViews.put(name, cd);

            // 第三行:操作
            String toggle = meta.enabled ? "暂停" : "开启";
            box.addView(btnRow(null, new String[][]{
                    {"运行", "run:" + idx},
                    {toggle, "tog:" + idx},
                    {"定时…", "sched:" + idx},
                    {"编辑", "edit:" + idx},
                    {"复制", "copy:" + idx},
                    {"删除", "del:" + idx}
            }, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    String a = String.valueOf(v.getTag());
                    if (a.startsWith("run:")) askRun(Integer.parseInt(a.substring(4)), name);
                    else if (a.startsWith("tog:")) toggleFlow(name, meta.enabled);
                    else if (a.startsWith("sched:")) askSchedule(name);
                    else if (a.startsWith("edit:")) editScript(Integer.parseInt(a.substring(5)));
                    else if (a.startsWith("copy:")) copyFlow(Integer.parseInt(a.substring(5)), name);
                    else confirmDelete(name);
                }
            }));

            View line = new View(this);
            line.setBackgroundColor(C_LINE);
            box.addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            scriptListBox.addView(box);
        }
        refreshCountdowns();
    }

    /** 只更新倒计时文字,不重建列表(不然按钮会跳) */
    private void refreshCountdowns() {
        if (countdownViews.isEmpty()) return;
        long now = time.nowMs();
        for (java.util.Map.Entry<String, TextView> e : countdownViews.entrySet()) {
            FlowStore.Meta m = flows.get(e.getKey());
            TextView tv = e.getValue();
            if (tv == null) continue;

            // 频率是流程自己的属性 —— 不管有没有定时,都显示出来
            String freqLine = "";
            if (m.freqOn) {
                String iv = (m.freqIntervalMs >= 1000)
                        ? String.format(java.util.Locale.US, "%.1f 秒", m.freqIntervalMs / 1000.0)
                        : m.freqIntervalMs + " 毫秒";
                freqLine = "\n🔁 频率:每 " + iv + " · "
                        + (m.freqDurationMs > 0 ? ("连续 " + fmtLeft(m.freqDurationMs)) : "不限时长");
            }

            if (m.schedMode == FlowStore.MODE_OFF) {
                tv.setText("定时:未设置" + freqLine);
                tv.setTextColor(C_DIM);
                continue;
            }
            long target = m.targetMillis();
            if (target <= 0) {
                tv.setText("定时:未设置");
                tv.setTextColor(C_DIM);
                continue;
            }
            long left = target - now;
            String when = fmtTime(target);
            if (left <= 0) {
                if (m.expired(now)) {
                    tv.setText("定时:" + when + " · 已过期" + freqLine);
                    tv.setTextColor(C_ERR);
                } else {
                    tv.setText("定时:" + when + " · 正在执行" + freqLine);
                    tv.setTextColor(C_OK);
                }
            } else {
                tv.setText("定时:" + when + " · 还有 " + fmtLeft(left) + freqLine);
                tv.setTextColor(C_WARN);
            }
        }
        maybeResortOnExpiry(now);
        autoPauseExpired(now);
    }

    /**
     * 过期的流程自动设为「暂停」。
     *
     * 为什么:过期了还挂着「已开启」会让人以为它还会跑,
     * 而且它是"全局唯一开启"的占用者 —— 不释放的话没法开启别的流程。
     *
     * 幂等:暂停过的 enabled 已经是 false,不会重复处理;
     * 只有真的改了状态才重排列表(避免每秒重建视图导致点击落空)。
     */
    private void autoPauseExpired(long now) {
        boolean changed = false;
        for (String nm : flows.allNames()) {
            FlowStore.Meta m = flows.get(nm);
            if (!m.enabled) continue;
            long t = m.targetMillis();
            if (t <= 0) continue;
            if (!m.expired(now)) continue;
            m.enabled = false;
            flows.put(m);
            changed = true;
            toast("「" + nm + "」定时已过期,已自动暂停");
        }
        if (changed && tab == TAB_SCRIPTS) renderScripts();
    }

    /**
     * 有人"刚刚过期"时重排一次列表。
     *
     * ⚠️ 不能每秒都重排 —— 重建视图会让正在点的按钮移位(踩过这个坑)。
     *    所以只比较"过期集合"的签名,变了才重排。
     */
    private void maybeResortOnExpiry(long now) {
        StringBuilder sb = new StringBuilder();
        for (String nm : flows.allNames()) {
            if (flows.get(nm).expired(now)) sb.append(nm).append('|');
        }
        String sig = sb.toString();
        if (lastExpiredSig == null) { lastExpiredSig = sig; return; }
        if (!sig.equals(lastExpiredSig)) {
            lastExpiredSig = sig;
            renderScripts();     // 过期集合变了 → 重排
        }
    }

    private String fmtTime(long ms) {
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA);
        f.setTimeZone(time.zone());
        return f.format(new java.util.Date(ms));
    }

    private String fmtLeft(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, ss = s % 60;
        if (h > 0) return h + " 时 " + m + " 分 " + ss + " 秒";
        if (m > 0) return m + " 分 " + ss + " 秒";
        return ss + " 秒";
    }

    /** 开启/暂停。开启时会把其他流程关掉(全局互斥) */
    private void toggleFlow(String name, boolean wasEnabled) {
        if (wasEnabled) {
            flows.disable(name);
            toast("已暂停:" + name);
        } else {
            // ⚠️ 已过期的流程禁止开启。
            //    原因:过期流程开启后**不会执行**(调度器有 expired 判断),
            //    却会占用"全局唯一开启"的名额,把别的流程挤掉 —— 纯负收益。
            //    想跑的话走「运行」手动执行,或先把定时改到未来。
            FlowStore.Meta chk = flows.get(name);
            long t = chk.targetMillis();
            if (t > 0 && chk.expired(time.nowMs())) {
                Dialogs.info(this, "无法开启",
                        "「" + name + "」的定时已经过期,不能开启。\n\n"
                        + "过期流程开启后不会执行,还会占着「同一时间只能开一个」的名额。\n\n"
                        + "想跑的话:点「运行」手动执行,或者先用「定时…」把时间改到未来。");
                return;
            }
            String displaced = flows.enableOnly(name);
            // 重新开启时清掉上次的触发标记,否则不会再响
            FlowStore.Meta m = flows.get(name);
            m.appLaunched = false;
            m.fired = false;
            if (m.schedMode == FlowStore.MODE_DELAY) {
                m.startAtMillis = System.currentTimeMillis();   // 倒计时从现在重新算
            }
            flows.put(m);
            if (displaced != null) toast("已开启:" + name + "(已自动暂停「" + displaced + "」)");
            else toast("已开启:" + name);
        }
        renderScripts();
    }

    /**
     * 复制流程。
     *
     * 语义 = **新建一个内容相同的流程**:
     *   · 内容从板子读一份**再写一份新文件** → 两者完全独立,
     *     删掉原流程不影响副本(不是共享引用)
     *   · 默认**暂停**(和新建流程一致)
     *   · 不带定时(新建流程也没有定时)
     *   · createdAt 用当前时间 → 按排序规则会排在前面
     */
    private void copyFlow(final int idx, final String name) {
        toast("正在复制「" + name + "」…");
        board.scriptText(idx, new Board.Cb() {
            @Override public void done(boolean ok, String body) {
                if (!ok) { toast("读取失败:" + Board.messageOf(body)); return; }
                String text;
                try {
                    text = Board.jstr(new JSONObject(body), "text");
                } catch (Exception e) {
                    toast("解析失败:" + e.getMessage());
                    return;
                }
                if (text == null || text.trim().isEmpty()) { toast("流程内容为空"); return; }
                final String newName = uniqueCopyName(name);
                board.save(newName, text, new Board.Cb() {
                    @Override public void done(boolean ok2, String body2) {
                        if (!ok2) { toast("复制失败:" + Board.messageOf(body2)); return; }
                        // 新建一份元数据:暂停、无定时、拷贝 App 绑定
                        FlowStore.Meta src = flows.get(name);
                        FlowStore.Meta cp = new FlowStore.Meta();
                        cp.name = newName;
                        cp.enabled = false;                       // 默认暂停
                        cp.schedMode = FlowStore.MODE_OFF;        // 不带定时
                        cp.createdAt = System.currentTimeMillis(); // 排前面
                        cp.appPkg = src.appPkg;
                        cp.appName = src.appName;
                        cp.repeatCount = src.repeatCount;
                        cp.intervalMs = src.intervalMs;
                        cp.intervalTimes = src.intervalTimes;
                        cp.resetRunState();
                        flows.put(cp);
                        scriptsDirty = true;
                        toast("已复制为「" + newName + "」(暂停状态)");
                        loadScripts();
                    }
                });
            }
        });
    }

    /** 生成不重名的副本名:"原名 副本" / "原名 副本2" / ... */
    private String uniqueCopyName(String base) {
        java.util.Set<String> used = new java.util.HashSet<String>(flows.allNames());
        if (scripts != null) {
            for (int i = 0; i < scripts.length(); i++) {
                used.add(Board.jstr(scripts.optJSONObject(i), "name"));
            }
        }
        String cand = base + " 副本";
        if (!used.contains(cand)) return cand;
        for (int i = 2; i < 100; i++) {
            cand = base + " 副本" + i;
            if (!used.contains(cand)) return cand;
        }
        return base + " 副本" + System.currentTimeMillis();
    }

    /** 点运行:弹三选项(需求 P6) */
    private void askRun(final int idx, final String name) {
        // 先检查"流程保存的 App"和"当前选中的 App"是否一致
        FlowStore.Meta mm = flows.get(name);
        String fp = mm.appPkg == null ? "" : mm.appPkg;
        String sp2 = selectedAppPkg == null ? "" : selectedAppPkg;
        if (!fp.isEmpty() && !sp2.isEmpty() && !fp.equals(sp2)) {
            askRunAppMismatch(idx, name, mm);
            return;
        }
        askRun2(idx, name);
    }

    /**
     * 流程里的目标 App 和当前选择的不一致 → 问一下要不要改成当前选的。
     *
     * 为什么需要:流程的坐标是针对某个 App 的界面录的。
     * 如果实际启动的是另一个 App,那套坐标会点到完全不相干的地方
     * (甚至可能是别的地方的删除/确认按钮),很危险。
     */
    private void askRunAppMismatch(final int idx, final String name, final FlowStore.Meta mm) {
        String flowApp = appLabelOf(mm.appPkg, mm.appName);
        String nowApp  = appLabelOf(selectedAppPkg, selectedAppName);

        LinearLayout box = Dialogs.column(this);
        box.addView(Dialogs.value(this, "目标 App 不一致", C_WARN));
        box.addView(Dialogs.label(this, "流程「" + name + "」保存时用的是:"));
        box.addView(Dialogs.value(this, flowApp, C_FG));
        box.addView(Dialogs.label(this, "现在选择的是:"));
        box.addView(Dialogs.value(this, nowApp, C_ACC));
        box.addView(Dialogs.label(this,
                "流程里的坐标是针对原来那个 App 录的。\n"
                + "用别的 App 跑,点击会落到完全不相干的位置。"));

        final android.app.AlertDialog[] h = new android.app.AlertDialog[1];
        h[0] = Dialogs.showWithClose(this, "运行流程", box,
                "改成「" + nowApp + "」", new Dialogs.OnOk() {
                    @Override public void onOk() {
                        h[0].dismiss();
                        // 把流程的目标 App 更新成当前选择的
                        mm.appPkg = selectedAppPkg;
                        mm.appName = selectedAppName;
                        flows.put(mm);
                        toast("已改成「" + appLabelOf(selectedAppPkg, selectedAppName) + "」");
                        askRun2(idx, name);
                    }
                },
                "保持「" + flowApp + "」", new Dialogs.OnOk() {
                    @Override public void onOk() {
                        h[0].dismiss();
                        askRun2(idx, name);   // 不改,直接继续
                    }
                });
    }

    private void askRun2(final int idx, final String name) {
        FlowStore.Meta m = flows.get(name);
        String appName = (m.appName == null || m.appName.isEmpty())
                ? selectedAppName : m.appName;

        LinearLayout box = Dialogs.column(this);
        box.addView(Dialogs.label(this, "请确保 App 已经登录"));
        box.addView(Dialogs.value(this,
                (appName == null || appName.isEmpty()) ? "(未选择目标 App)" : appName, C_FG));
        box.addView(Dialogs.label(this, "确定后:启动该 App → 执行流程"));

        // 这个流程启用了频率 → 明确告诉用户会循环跑,而不是跑一次
        if (m.freqOn) {
            String iv = (m.freqIntervalMs >= 1000)
                    ? String.format(java.util.Locale.US, "%.1f 秒", m.freqIntervalMs / 1000.0)
                    : m.freqIntervalMs + " 毫秒";
            box.addView(Dialogs.value(this, "🔁 已启用频率", C_WARN));
            box.addView(Dialogs.label(this,
                    "每 " + iv + " 重跑一遍,"
                    + (m.freqDurationMs > 0
                        ? ("连续 " + fmtLeft(m.freqDurationMs) + " 后自动停")
                        : "不限时长(跑到你点「停止」)")
                    + "。\n由板子计时,App 被杀也会按时停。"));
        }

        final android.app.AlertDialog[] h = new android.app.AlertDialog[1];
        h[0] = Dialogs.showWithClose(this, "运行流程", box, "确定执行",
                new Dialogs.OnOk() {
                    @Override public void onOk() {
                        h[0].dismiss();
                        launchSelectedApp();
                        // ⚠️ 不能只等 1.2 秒 —— 实测大麦启动到首页要 5.8 秒,
                        //    1.2 秒时还在开屏广告,第一步点击必然打偏。
                        //    改成"轮询等目标 App 真正到前台,再多给 2.5 秒缓冲"。
                        waitAppForegroundThenExec(name, idx, 0);
                    }
                },
                "点我去看看",
                new Dialogs.OnOk() {
                    @Override public void onOk() {
                        h[0].dismiss();
                        selfStopped = false;     // 复位:马上要 launch,等它变 true
                        launchSelectedApp();     // 只启动,不执行
                    }
                });
    }

    /**
     * 等目标 App 真正到前台,再执行流程。
     *
     * 为什么不能固定延时:实测大麦从启动到首页要 **5.8 秒**,
     * 固定等 1.2 秒时还在开屏广告,流程第一步就点偏了。
     * 轮询 + 缓冲比拍一个固定值可靠。
     */
    /**
     * 启动目标 App 后,等它真正到前台,再执行流程。
     *
     * ⚠️⚠️ 这里踩过一个**大坑**(用户实测:"点运行要等 30 秒才动"):
     *
     *   老实现用 `ActivityManager.getRunningTasks(1)` 判断目标 App 是否在前台。
     *   但 **从 Android 5 (API 21) 起这个接口就被限制了** ——
     *   它**只返回调用者自己的 task**。所以在 Android 16 上:
     *       ts.get(0).topActivity == 我们自己的 MainActivity
     *   永远不等于目标 App 的包名 ⇒ `fg` **永远是 false**
     *   ⇒ 每 500ms 轮询一次,一直轮询到 60 次
     *   ⇒ **死等满 30 秒**,才走"等超时,仍然开始执行"分支
     *   ⇒ 用户看到的就是"点了运行,等 30 秒才动"
     *
     *   修法:不去猜别的 App 在不在前台(权限/限制太麻烦),
     *   改成判断**我们自己有没有退到后台** ——
     *   我们主动 launch 了别的 App,系统一定会把我们的 Activity 停掉,
     *   `onStop()` 可靠、无需任何权限、所有 Android 版本行为一致。
     *   自己退到后台 ⇒ 目标 App 已经起来了 ⇒ 再给一段渲染时间就执行。
     */
    private void waitAppForegroundThenExec(final String flowName, final int idx, int tries) {
        // ① 先等**自己**退到后台(最多 8 秒)
        if (!selfStopped && tries < 16) {
            if (tries == 0) toast("等目标 App 起来…");
            ui.postDelayed(new Runnable() {
                @Override public void run() { waitAppForegroundThenExec(flowName, idx, tries + 1); }
            }, 500);
            return;
        }
        // ② 自己已经退到后台了,再给目标 App 一段渲染/开屏广告的时间
        //    ⚠️ 这个值可以调:大麦那种重开屏的需要长一点。
        long wait = getSharedPreferences("board", MODE_PRIVATE)
                .getLong("appLaunchWaitMs", 2500);
        ui.postDelayed(new Runnable() {
            @Override public void run() { exec(idx, 1, 0); }
        }, wait);
    }

    /** 我们自己有没有退到后台(用来判断"目标 App 起来了") */
    private volatile boolean selfStopped = false;

    @Override protected void onStop() {
        super.onStop();
        selfStopped = true;
    }

    private void exec(int idx, int repeat, int interval) {
        if (!canTap()) { toast("执行中,请稍候"); return; }
        setRunning(true);
        // ⚠️ 立刻打开页面推送 —— 不能等 2 秒的状态轮询,
        //    否则脚本开头的 waitfor 会因为"板子还不知道当前页面"而白等。
        runningNow = true;
        pagePushUntil = System.currentTimeMillis() + 3600_000L;

        // 看这个流程有没有启用频率 —— 有就按频率循环跑,没有就跑一次
        FlowStore.Meta m = flowMetaByIdx(idx);
        final boolean useFreq = (m != null && m.freqOn);

        if (useFreq) {
            // 频率循环是**板子侧**跑的,到时间自己停(App 被杀也停)
            boardBusy = true;
            board.schedule(idx, m.freqIntervalMs, m.freqDurationMs, new Board.Cb() {
                @Override public void done(boolean ok, String body) {
                    toast(Board.messageOf(body));
                    if (!ok) { boardBusy = false; setRunning(false); return; }
                    waitDone(0);
                }
            });
        } else {
            board.run(idx, repeat, interval, new Board.Cb() {
                @Override public void done(boolean ok, String body) {
                    toast(Board.messageOf(body));
                    waitDone(0);
                }
            });
        }
    }

    /** 按板子返回的 index 找到对应的流程元信息(拿频率配置用) */
    private FlowStore.Meta flowMetaByIdx(int idx) {
        try {
            for (int i = 0; i < scripts.length(); i++) {
                JSONObject o = scripts.optJSONObject(i);
                if (o != null && Board.jint(o, "index") == idx) {
                    return flows.get(Board.jstr(o, "name"));
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 轮询板子的 running 状态,结束后解锁按钮 */
    private void waitDone(final int tries) {
        if (tries > 120) {
            setRunning(false);
            runningNow = false;
            pagePushUntil = System.currentTimeMillis() + 3000;
            return;
        }
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                board.status(new Board.Cb() {
                    @Override public void done(boolean ok, String body) {
                        boolean still = false;
                        if (ok) {
                            try {
                                JSONObject o = new JSONObject(body);
                                JSONObject r = o.optJSONObject("run");
                                still = r != null && r.optBoolean("running", false);
                            } catch (Exception ignored) {}
                        }
                        if (still) waitDone(tries + 1);
                        else {
                                            setRunning(false);
                            toast("执行结束");
                            if (tab == TAB_SCRIPTS) loadScripts();
                        }
                    }
                });
            }
        }, 500);
    }

    private void confirmDelete(final String name) {
        Dialogs.confirm(this, "删除流程", "确定删除「" + name + "」?", "删除",
                new Dialogs.OnOk() {
                    @Override public void onOk() {
                        board.delete(name, new Board.Cb() {
                            @Override public void done(boolean ok, String body) {
                                toast(Board.messageOf(body));
                                flows.remove(name);
                                lastScriptsSig = "";
                                loadScripts();
                            }
                        });
                    }
                });
    }

    /** 定时设置(从流程列表进入):直接读写已保存的 Meta */
    /** 定时设置:改为独立全屏页面(弹窗与输入法冲突,详见 ScheduleActivity) */
    private void askSchedule(final String name) {
        FlowStore.Meta m = flows.get(name);
        pendingSchedName = name;
        startActivityForResult(new android.content.Intent(this, ScheduleActivity.class)
                .putExtra(ScheduleActivity.EX_NAME, name)
                .putExtra(ScheduleActivity.EX_MODE, m.schedMode)
                .putExtra(ScheduleActivity.EX_AT, m.atMillis)
                .putExtra(ScheduleActivity.EX_DELAY, m.delayMs)
                .putExtra(ScheduleActivity.EX_REPEAT, m.repeatCount)
                // 频率也一起带过去(现在统一在"定时"页里配)
                .putExtra(ScheduleActivity.EX_FREQ_ON, m.freqOn)
                .putExtra(ScheduleActivity.EX_FREQ_MS, m.freqIntervalMs)
                .putExtra(ScheduleActivity.EX_FREQ_DUR, m.freqDurationMs), REQ_SCHEDULE);
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FLOW_EDIT) {
            // 编辑页回来了(不管保存还是取消)→ 重新拉一次列表
            loadScripts();
            return;
        }
        if (req == REQ_SCHEDULE && res == RESULT_OK && data != null) {
            String name = pendingSchedName;
            if (name == null || name.isEmpty()) return;
            FlowStore.Meta m = flows.get(name);
            m.schedMode = data.getIntExtra(ScheduleActivity.EX_MODE, FlowStore.MODE_OFF);
            m.repeatCount = data.getIntExtra(ScheduleActivity.EX_REPEAT, 1);
            if (m.schedMode == FlowStore.MODE_AT) {
                m.atMillis = data.getLongExtra(ScheduleActivity.EX_AT, 0);
                m.startAtMillis = 0;
            } else if (m.schedMode == FlowStore.MODE_DELAY) {
                m.delayMs = data.getLongExtra(ScheduleActivity.EX_DELAY, 0);
                m.startAtMillis = System.currentTimeMillis();
            } else {
                m.atMillis = 0; m.delayMs = 0; m.startAtMillis = 0;
            }
            m.name = name;
            // 频率(和定时模式无关,一起保存)
            m.freqOn = data.getBooleanExtra(ScheduleActivity.EX_FREQ_ON, m.freqOn);
            m.freqIntervalMs = data.getIntExtra(ScheduleActivity.EX_FREQ_MS,
                    m.freqIntervalMs);
            m.freqDurationMs = data.getLongExtra(ScheduleActivity.EX_FREQ_DUR,
                    m.freqDurationMs);
            // ⚠️ 必须重置一次性的执行状态:
            //    否则改完定时后 fired 还是 true,新定时**永远不会执行**
            //    (调度器判断条件是 !m.fired),而且列表文案也不会刷新。
            m.resetRunState();
            flows.put(m);
            toast("定时已保存" + (m.schedMode == FlowStore.MODE_OFF ? "" : "(已重置执行状态)"));
            if (tab == TAB_SCRIPTS) renderScripts();
        }
    }

    /** 新建流程:标题默认取目标 App 名字(需求 P5) */
    private void newFlow() {
        String def = (selectedAppName == null || selectedAppName.isEmpty())
                ? "" : selectedAppName;
        startActivityForResult(new android.content.Intent(this, FlowEditActivity.class)
                .putExtra(FlowEditActivity.EX_INDEX, -1)
                .putExtra(FlowEditActivity.EX_NAME, def), REQ_FLOW_EDIT);
    }

    private void editScript(int idx) {
        // 用 forResult:编辑/新建保存后要**自动刷新列表**,
        // 否则新流程要手动点「刷新列表」才出现(实测踩过)。
        startActivityForResult(new android.content.Intent(this, FlowEditActivity.class)
                .putExtra(FlowEditActivity.EX_INDEX, idx), REQ_FLOW_EDIT);
    }


    private void buildControlTab() {
        LinearLayout nav = card("导航 / 系统键");
        nav.addView(btnRow("底部三键", new String[][]{
                {"回桌面", "home"}, {"返回", "back"}, {"最近任务", "recents"}
        }, ctlClick));
        nav.addView(btnRow("屏幕", new String[][]{
                {"唤醒", "wake"}, {"电源键", "power"}
        }, ctlClick));
        content.addView(nav);

        LinearLayout vol = card("音量 / 媒体");
        vol.addView(btnRow(null, new String[][]{
                {"音量+", "volup"}, {"音量−", "voldown"}, {"静音", "mute"}
        }, ctlClick));
        vol.addView(btnRow(null, new String[][]{
                {"播放/暂停", "playpause"}, {"下一曲", "next"}, {"上一曲", "prev"}
        }, ctlClick));
        content.addView(vol);

        LinearLayout sw = card("快速滑动");
        sw.addView(btnRow(null, new String[][]{
                {"上滑", "u"}, {"下滑", "d"}, {"左滑", "l"}, {"右滑", "r"}
        }, new View.OnClickListener() {
            @Override public void onClick(View v) {
                String a = String.valueOf(v.getTag());
                if ("u".equals(a))      board.swipe(0.5f, 0.75f, 0.5f, 0.25f, swipeMs, cb);
                else if ("d".equals(a)) board.swipe(0.5f, 0.25f, 0.5f, 0.75f, swipeMs, cb);
                else if ("l".equals(a)) board.swipe(0.85f, 0.5f, 0.15f, 0.5f, swipeMs, cb);
                else                    board.swipe(0.15f, 0.5f, 0.85f, 0.5f, swipeMs, cb);
            }
        }));
        sw.addView(btnRow("双指滚动", new String[][]{
                {"上滚", "pfu"}, {"下滚", "pfd"}
        }, new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean up = "pfu".equals(v.getTag());
                String p = "a=0.4&b=" + (up ? "0.75" : "0.25") + "&c=0.4&d=" + (up ? "0.30" : "0.70")
                        + "&e=0.6&f=" + (up ? "0.75" : "0.25") + "&g=0.6&h=" + (up ? "0.30" : "0.70");
                // 板子的 P 指令参数与 api/gesture 的 op=swipe 不同,这里用两次单指近似
                board.gesture("pinch", "a=0.5&b=0.5&c=" + (up ? "0.6" : "0.2")
                        + "&d=" + (up ? "0.2" : "0.6") + "&ms=" + swipeMs, cb);
            }
        }));
        content.addView(sw);

        LinearLayout pin = card("缩放 / 多指");
        pin.addView(btnRow(null, new String[][]{
                {"放大", "zin"}, {"缩小", "zout"}
        }, new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean zoomIn = "zin".equals(v.getTag());
                board.gesture("pinch", "a=0.5&b=0.5&c=" + (zoomIn ? "0.2" : "0.6")
                        + "&d=" + (zoomIn ? "0.6" : "0.2") + "&ms=" + swipeMs, cb);
            }
        }));
        content.addView(pin);

        LinearLayout panel = card("参数调整(影响上面的手势)");
        panel.addView(stepper("滑动时长", new String[]{"swipeMs"}, 0));
        panel.addView(stepper("点击时长", new String[]{"tapMs"}, 1));
        panel.addView(stepper("长按时长", new String[]{"holdMs"}, 2));
        content.addView(panel);
    }

    private final View.OnClickListener ctlClick = new View.OnClickListener() {
        @Override public void onClick(View v) { doControl(String.valueOf(v.getTag())); }
    };

    /** 参数加减控件 */
    private View stepper(String label, String[] key, final int which) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(5), 0, dp(5));

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(C_DIM);
        t.setTextSize(13);
        t.setWidth(dp(84));
        row.addView(t);

        final TextView val = new TextView(this);
        val.setTextColor(C_FG);
        val.setTextSize(14);
        val.setGravity(Gravity.CENTER);
        val.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(val);

        final Runnable paint = new Runnable() {
            @Override public void run() {
                if (which == 3) { val.setText(repeatTimes + " 次"); return; }
                int v = which == 0 ? swipeMs : (which == 1 ? tapMs : holdMs);
                val.setText(v + " ms");
            }
        };
        paint.run();

        Button minus = new Button(this);
        minus.setText("−");
        minus.setTextSize(16);
        minus.setMinimumWidth(0);
        minus.setLayoutParams(new LinearLayout.LayoutParams(dp(52), dp(44)));
        minus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (which == 0) swipeMs = Math.max(80, swipeMs - 50);
                else if (which == 1) tapMs = Math.max(20, tapMs - 20);
                else if (which == 2) holdMs = Math.max(200, holdMs - 100);
                else repeatTimes = Math.max(1, repeatTimes - 1);
                paint.run();
            }
        });
        row.addView(minus);

        Button plus = new Button(this);
        plus.setText("+");
        plus.setTextSize(16);
        plus.setMinimumWidth(0);
        plus.setLayoutParams(new LinearLayout.LayoutParams(dp(52), dp(44)));
        plus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (which == 0) swipeMs = Math.min(2000, swipeMs + 50);
                else if (which == 1) tapMs = Math.min(1000, tapMs + 20);
                else if (which == 2) holdMs = Math.min(5000, holdMs + 100);
                else repeatTimes = Math.min(50, repeatTimes + 1);
                paint.run();
            }
        });
        row.addView(plus);
        return row;
    }

    private void doControl(String what) {
        if ("stop".equals(what)) {
            // ⚠️ 停止要**同时**停掉频率循环 ——
            //    否则点了「停止脚本」,当前这次停了,但板子的频率循环
            //    下一轮又把它跑起来了(实测踩过)。
            board.scheduleStop(null);
            boardBusy = false;
            board.stop(cb);
            return;
        }
        else if ("schedstop".equals(what)) board.scheduleStop(cb);
        else if ("releaseall".equals(what)) board.gesture("releaseall", null, cb);
        else if ("playpause".equals(what)) board.key("consumer", 0xCD, cb);
        else if ("next".equals(what)) board.key("consumer", 0xB5, cb);
        else if ("prev".equals(what)) board.key("consumer", 0xB6, cb);
        else board.control(what, cb);
    }

    private void sysOp(final String op) {
        if (!"factory".equals(op)) {
            Dialogs.confirm(this, "确认", "确定重启板子?", "确定", new Dialogs.OnOk() {
                @Override public void onOk() { board.system(op, cb); }
            });
            return;
        }
        // 「清空所有流程」是**不可撤销**的破坏性操作,而且按钮位置正好在
        // 流程很容易点到的坐标上(实测误触过一次,差一点全没了)。
        // 所以做**两步确认**,第二步要求手输「清空」——
        // 注入的短按点击不可能完成"输入文字"。
        Dialogs.confirm(this, "⚠️ 清空所有流程",
                "这会删掉板子上全部流程,板子自带的功能无法撤销。\n\n"
                + "(服务器上有完整备份,可以恢复 —— 但还是别误触。)\n\n"
                + "确定要继续吗?", "继续", new Dialogs.OnOk() {
            @Override public void onOk() { confirmClearByTyping(op); }
        });
    }

    /** 第二步:必须手输「清空」才真的执行 */
    private void confirmClearByTyping(final String op) {
        LinearLayout box = Dialogs.column(this);
        box.addView(Dialogs.label(this, "真的要清空的话,请在下面输入「清空」两个字:"));
        final EditText ed = new EditText(this);
        ed.setHint("在这里输入:清空");
        ed.setSingleLine();
        ed.setTextSize(15);
        box.addView(ed);
        box.addView(Dialogs.label(this, "输入不对就什么都不会发生。"));
        Dialogs.show(this, "最后确认", box, "确认清空", new Dialogs.OnOk() {
            @Override public void onOk() {
                if (!"清空".equals(ed.getText().toString().trim())) {
                    toast("输入不对,已取消(什么都没做)");
                    return;
                }
                board.system(op, cb);
            }
        }, "取消");
    }

    // ================================================================ 手势(坐标板)
    private TextView padInfo;

    private void buildGestureTab() {
        LinearLayout c = card("坐标板:在这里操作 = 在手机屏幕对应位置操作");
        c.addView(hint("按下=点击 · 长按=长按 · 拖动=滑动"));

        final View pad = new View(this);
        pad.setBackgroundColor(0xff010409);
        c.addView(pad, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));

        padInfo = hint("等待操作…");
        c.addView(padInfo);

        pad.setOnTouchListener(makePadListener(pad));
        content.addView(c);

        LinearLayout manual = card("手动坐标");
        final EditText ex = new EditText(this);
        ex.setHint("x,如 0.5 或 50%");
        ex.setTextSize(13);
        final EditText ey = new EditText(this);
        ey.setHint("y,如 0.5 或 50%");
        ey.setTextSize(13);
        manual.addView(ex);
        manual.addView(ey);
        manual.addView(btnRow(null, new String[][]{
                {"点击", "tap"}, {"长按", "long"}, {"双击", "double"}
        }, new View.OnClickListener() {
            @Override public void onClick(View v) {
                float x = parseCoord(ex.getText().toString(), 0.5f);
                float y = parseCoord(ey.getText().toString(), 0.5f);
                String a = String.valueOf(v.getTag());
                if ("long".equals(a)) board.longPress(x, y, cb);
                else if ("double".equals(a)) board.gesture("double", "a=" + x + "&b=" + y, cb);
                else board.tap(x, y, cb);
                padInfo.setText(String.format("%s %.3f,%.3f", a, x, y));
            }
        }));
        content.addView(manual);

        LinearLayout two = card("两点滑动(填起点终点)");
        final EditText[] f = new EditText[4];
        String[] ph = {"起点 x", "起点 y", "终点 x", "终点 y"};
        for (int i = 0; i < 4; i++) {
            f[i] = new EditText(this);
            f[i].setHint(ph[i]);
            f[i].setTextSize(13);
            two.addView(f[i]);
        }
        two.addView(btnRow(null, new String[][]{{"滑动", "go"}},
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        float[] v4 = new float[4];
                        for (int i = 0; i < 4; i++) {
                            v4[i] = parseCoord(f[i].getText().toString(), i % 2 == 0 ? 0.5f : 0.5f);
                        }
                        board.swipe(v4[0], v4[1], v4[2], v4[3], swipeMs, cb);
                        padInfo.setText(String.format("滑动 %.2f,%.2f → %.2f,%.2f",
                                v4[0], v4[1], v4[2], v4[3]));
                    }
                }));
        content.addView(two);

        LinearLayout rep = card("连续执行(连点/连滑)");
        rep.addView(stepper("次数", new String[]{"repeat"}, 3));
        rep.addView(hint("配合上面的「点击」使用:先设次数,再连点"));
        rep.addView(btnRow(null, new String[][]{
                {"连点 x N", "multi"}
        }, new View.OnClickListener() {
            @Override public void onClick(View v) {
                float x = parseCoord(ex.getText().toString(), 0.5f);
                float y = parseCoord(ey.getText().toString(), 0.5f);
                final int n = repeatTimes;
                padInfo.setText("连点 " + n + " 次…");
                for (int i = 0; i < n; i++) {
                    final int k = i;
                    ui.postDelayed(new Runnable() {
                        @Override public void run() {
                            board.tap(x, y, silent);
                            if (k == n - 1) padInfo.setText("连点完成 " + n + " 次");
                        }
                    }, i * 400L);
                }
            }
        }));
        content.addView(rep);
    }

    private View.OnTouchListener makePadListener(final View pad) {
        return new View.OnTouchListener() {
            private float dX, dY;
            private long dT;
            private boolean moved;

            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                float w = v.getWidth(), h = v.getHeight();
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dX = e.getX() / w;
                        dY = e.getY() / h;
                        dT = System.currentTimeMillis();
                        moved = false;
                        if (padInfo != null) {
                            padInfo.setText(String.format("按下 %.3f,%.3f", dX, dY));
                        }
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(e.getX() / w - dX) > 0.02f
                                || Math.abs(e.getY() / h - dY) > 0.02f) {
                            moved = true;
                            if (padInfo != null) {
                                padInfo.setText(String.format("拖到 %.3f,%.3f",
                                        e.getX() / w, e.getY() / h));
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        float ux = e.getX() / w, uy = e.getY() / h;
                        long dt = System.currentTimeMillis() - dT;
                        if (moved) {
                            board.swipe(dX, dY, ux, uy, (int) Math.min(Math.max(dt, 80), 1500), cb);
                            if (padInfo != null) {
                                padInfo.setText(String.format("滑动 %.2f,%.2f → %.2f,%.2f",
                                        dX, dY, ux, uy));
                            }
                        } else if (dt >= 600) {
                            board.longPress(ux, uy, cb);
                            if (padInfo != null) {
                                padInfo.setText(String.format("长按 %.3f,%.3f", ux, uy));
                            }
                        } else {
                            board.tap(ux, uy, cb);
                            if (padInfo != null) {
                                padInfo.setText(String.format("点击 %.3f,%.3f", ux, uy));
                            }
                        }
                        return true;
                }
                return false;
            }
        };
    }

    private float parseCoord(String s, float def) {
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

    // ================================================================ 日志
    private TextView logView;
    private int seenLogs = 0;

    private void buildLogsTab() {
        LinearLayout c = card("运行日志(板子最近 80 行)");
        c.addView(btnRow(null, new String[][]{
                {"刷新", "r"}, {"清空", "c"}
        }, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if ("c".equals(v.getTag())) {
                    board.clearLogs(new Board.Cb() {
                        @Override public void done(boolean ok, String body) {
                            seenLogs = 0;
                            if (logView != null) logView.setText("(暂无日志)");
                        }
                    });
                } else {
                    seenLogs = 0;
                    if (logView != null) logView.setText("");
                    pullLogs();
                }
            }
        }));

        logView = new TextView(this);
        logView.setText("(暂无日志)");
        logView.setTextColor(0xffc9d1d9);      // 提亮一点,11sp 太小太暗看着累
        logView.setTextSize(12);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setBackgroundColor(0xff010409);
        logView.setPadding(dp(8), dp(8), dp(8), dp(8));
        // ⚠️ 高度必须是 WRAP_CONTENT。
        //    以前写死 dp(480) —— 内容超过就被**裁掉**,
        //    长一点的流程日志(一次 30+ 行)后面根本看不到。
        //    改成跟内容走,由外层页面的 ScrollView 负责滚动。
        logView.setTextIsSelectable(true);      // 能选中复制某几行,方便贴给别人看
        logView.setLineSpacing(dp(2), 1.0f);
        c.addView(logView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(c);

        seenLogs = 0;
        pullLogs();
    }

    /**
     * 拉板子日志。
     *
     * ⚠️⚠️ 这里**不能依赖 logView**(那是"日志页签"里的控件)。
     *      以前开头就是 `if (logView == null) return;` ——
     *      用户没进过日志页时它是 null,**连拉都不拉**,
     *      于是悬浮窗的日志一直空着(用户实测反馈:
     *      "必须要先在 app 里点击日志页签后,悬浮窗才会出现日志")。
     *
     *      现在:悬浮窗和日志页**各自独立**地拿数据,谁在就喂谁。
     */
    private void pullLogs() {
        final boolean needFloat = (floatWin != null && floatWin.isShowing());
        // 两个消费方都不在,就不用发请求了
        if (logView == null && !needFloat) return;

        board.logs(new Board.Cb() {
            @Override public void done(boolean ok, String body) {
                pollBusy = false;
                if (!ok) {
                    if (logView != null) logView.setText("读取失败:" + body);
                    return;
                }
                try {
                    JSONObject o = new JSONObject(body);
                    JSONArray lines = o.optJSONArray("lines");
                    if (lines == null) return;

                    // ---- ① 喂悬浮窗(最近 30 行,不管在哪个页签)----
                    if (floatWin != null && floatWin.isShowing()) {
                        StringBuilder fb = new StringBuilder();
                        int from = Math.max(0, lines.length() - 30);
                        for (int k = from; k < lines.length(); k++) {
                            fb.append(lines.optString(k)).append('\n');
                        }
                        String t = fb.toString();
                        if (!t.equals(lastLogText)) {      // 变了才刷,省点开销
                            lastLogText = t;
                            floatWin.setLog(t);
                        }
                    }

                    // ---- ② 喂日志页签(可能根本没建过)----
                    if (logView == null) return;
                    if (lines.length() < seenLogs) { logView.setText(""); seenLogs = 0; }
                    if (lines.length() == seenLogs) return;
                    if (seenLogs == 0) logView.setText("");
                    StringBuilder sb = new StringBuilder();
                    for (int i = seenLogs; i < lines.length(); i++) {
                        sb.append(lines.optString(i)).append('\n');
                    }
                    logView.append(sb.toString());
                    seenLogs = lines.length();
                } catch (Exception ignored) {}
            }
        });
    }

    // ================================================================ 回调 / 刷新
    private final Board.Cb cb = new Board.Cb() {
        @Override public void done(boolean ok, String body) {
            toast(Board.messageOf(body));
        }
    };

    private final Board.Cb silent = new Board.Cb() {
        @Override public void done(boolean ok, String body) { }
    };

    /**
     * 把 App 里的定时任务报给服务端。
     *
     * ⚠️ 定时是存在**手机 App 的本地设置**里的,板子根本不知道 ——
     *    不报的话看板就看不到"下次什么时候执行"。
     *
     * 策略:内容变了立刻报;没变也每 5 分钟兜底报一次。
     */
    private void maybeReportSchedules() {
        if (boardMac == null || boardMac.isEmpty()) return;
        long now = System.currentTimeMillis();
        String fp = scheduleFingerprint();
        boolean changed = !fp.equals(schedFingerprint);
        if (!changed && now - schedReportAt < 5 * 60_000L) return;
        schedFingerprint = fp;
        schedReportAt = now;
        Server.reportSchedules(this, boardMac, fp);
    }

    /** 把定时任务拼成 JSON(这个串同时当"指纹"用来判断变没变) */
    private String scheduleFingerprint() {
        org.json.JSONArray arr = new org.json.JSONArray();
        try {
            for (String n : flows.allNames()) {
                FlowStore.Meta m = flows.get(n);
                if (m == null || m.schedMode == FlowStore.MODE_OFF) continue;
                org.json.JSONObject t = new org.json.JSONObject();
                t.put("name", m.name);
                t.put("mode", m.schedMode == FlowStore.MODE_AT ? "at" : "delay");
                if (m.schedMode == FlowStore.MODE_AT) t.put("fireAt", m.atMillis);
                else t.put("delayMs", m.delayMs);
                t.put("enabled", m.enabled);
                t.put("repeatCount", m.repeatCount);
                arr.put(t);
            }
        } catch (Throwable ignored) {}
        return arr.toString();
    }

    /**
     * UI 节拍:**固定 1 秒**,负责所有"看起来要连续"的东西。
     *
     * ⚠️ 以前这些都挂在 2 秒的网络循环上 —— 只要不在流程页,
     *    悬浮窗倒计时就是 **2 秒一跳**,看着一卡一卡的(用户实测反馈)。
     *    拆开之后:UI 走 1 秒,网络请求仍然 2 秒,互不影响。
     */
    /** 1 秒一次;每 5 次做一次连接检测 */
    private int linkTickN = 0;

    private final Runnable uiTick = new Runnable() {
        @Override public void run() {
            if (!autoRefresh) return;
            schedulerTick();          // 定时调度
            if (++linkTickN % 5 == 0) linkTick();   // 每 5 秒探一次连接方式
            maybeRelayBoard();        // 每 30 分钟把板子状态代报给服务器
            updateFloatSchedule();    // 悬浮窗倒计时
            syncFloatExecuting();     // 悬浮窗"执行中"状态
            if (tab == TAB_SCRIPTS) {
                // 只改那一个 TextView 的文字,**绝不重建列表**
                // (重建会让按钮跳位,按下时点到别的按钮,实测踩过)
                refreshCountdowns();
            }
            // 板子在忙 → 保持屏幕常亮;不忙 → 恢复原来的息屏时间
            // (放在这里而不是 exec() 里,是为了覆盖网页/定时/频率所有触发路径)
            if (boardBusy) KeepAwake.hold(MainActivity.this);
            else KeepAwake.release(MainActivity.this);

            // 遮罩也要跟着 boardBusy —— 频率循环的**间隙**里 running 是 false,
            // 以前遮罩会一闪一闪,间隙的注入点击就打到按钮上了(实测差点了清空所有流程)
            if (busyMask != null) {
                boolean cover = running || boardBusy;
                int want = cover ? View.VISIBLE : View.GONE;
                if (busyMask.getVisibility() != want) busyMask.setVisibility(want);
            }

            // 后台常驻:跑流程 或 页面识别开着的时候起前台服务。
            // ⚠️ 没有它的话,切到大麦之后 MIUI 会杀掉 App,
            //    页面识别停止 → waitfor 永远超时(实测踩过)
            // ⚠️ 这里必须用 **enabledInSettings**(设置里开关开着)而不是 enabled()(已连上)。
            //    用 enabled() 会死循环:App 被杀 → sConnected=false → 不起前台服务
            //    → 进程保不住 → 永远连不上(实测踩过)。
            boolean needAlive = boardBusy || PageWatcher.enabledInSettings(MainActivity.this);
            if (needAlive != lastNeedAlive) {
                lastNeedAlive = needAlive;
                android.util.Log.i("TP_KEEPALIVE", "needAlive=" + needAlive
                        + " boardBusy=" + boardBusy
                        + " switchOn=" + PageWatcher.enabledInSettings(MainActivity.this)
                        + " running=" + KeepAliveService.isRunning());
            }
            if (needAlive && !KeepAliveService.isRunning()) {
                KeepAliveService.start(MainActivity.this,
                        boardBusy ? "流程执行中" : "页面识别");
            } else if (!needAlive && KeepAliveService.isRunning()) {
                KeepAliveService.stop(MainActivity.this);
            }

            // 把 App 的定时报给服务端(内容变了立刻报,没变每 5 分钟兜底一次)
            maybeReportSchedules();
            ui.postDelayed(this, 1000);
        }
    };

    /** 网络节拍:2 秒一次,拉状态/日志(没必要每秒都发请求) */
    /**
     * 上一轮轮询还没回来 —— 别叠加。
     *
     * ⚠️ 蓝牙带宽远小于 WiFi:2 秒一轮的 STATUS/LOG 如果**不等上一条回来**
     *    就接着发,请求会越堆越多,最后队列几十条、界面一直"读取中…"
     *    (实测:33 条请求只有 2 条拿到响应)。必须"一问一答"。
     */
    private volatile boolean pollBusy = false;

    private final Runnable netTick = new Runnable() {
        @Override public void run() {
            // ⚠️⚠️ **用户刚点了动作 ⇒ 这一轮轮询全部跳过**。
            //      踩过的坑:用户点「运行」,RUN 入队后要和 20 秒轮询抢通道,
            //      经常抢不到 ⇒ 超时 ⇒ App 重试 ⇒ 用户看到"等 30 秒才执行",
            //      而且因为重试板子可能跑两遍。
            //      让路 12 秒,用户操作就能独占通道立刻生效。
            if (Ble.userActionPending()) {
                ui.postDelayed(this, 1500);      // 让路期间也快一点回来看看
                return;
            }
            if (autoRefresh && !pollBusy) {
                boolean did = false;
                if (tab == TAB_STATUS) {
                    pollBusy = true; did = true; fetchStatus();
                } else if (tab == TAB_LOGS) {
                    pollBusy = true; did = true; pullLogs();
                }
                // ⚠️ 悬浮窗在显示 → 不管当前在哪个页签,都要刷新它的日志。
                //    否则"必须切到日志页签,而且切走就不刷新"(用户实测反馈)。
                if (!did && tab != TAB_LOGS && floatWin != null && floatWin.isShowing()) {
                    pollBusy = true; pullLogs();
                }
                if (did || pollBusy) {
                    // 兜底:3.5 秒还没回来就放开,别把轮询永久停掉
                    ui.postDelayed(new Runnable() {
                        @Override public void run() { pollBusy = false; }
                    }, 3500);
                }
            }
            // ⚠️⚠️ **按链路选轮询间隔**:
            //     WiFi 是"一问一答"的 TCP,2 秒一轮毫无压力。
            //     蓝牙一次往返慢得多(实测约 3 秒/条),还按 2 秒轮询
            //     就是"请求产生速度 > 完成速度" ⇒ 队列越堆越多 ⇒
            //     界面一直读不出来(实测 25 条只成功 6 条)。
            //     蓝牙下放到 6 秒一轮,让每条都来得及完成。
            // ⚠️⚠️ **蓝牙下轮询必须放得很慢**。
            //     实测:蓝牙一次往返 2~3 秒,而一轮要发 STATUS + LOG 两个请求。
            //     6 秒一轮时通道 100% 占满,成功率只有 30%
            //     (29 发出 / 20 失败),连"代板子上报"都排不进去 ⇒
            //     服务器上板子永远显示离线。
            //     放到 20 秒,通道就有大量空闲给中继和用户操作。
            //     代价:蓝牙模式下状态刷新慢一些 —— 可以接受,
            //     因为蓝牙本来就是"没网时的兜底通路"。
            ui.postDelayed(this, bleActive() ? 20000 : 2000);
        }
    };

    /** 当前是不是在走蓝牙控制通道 */
    private boolean bleActive() {
        return board.getTransport() != Board.T_WIFI && Ble.get().ready;
    }

    // ================================================================ 定时调度
    /** 流程名 → 板子上的序号(用于执行) */
    private final java.util.HashMap<String, Integer> nameToIndex
            = new java.util.HashMap<String, Integer>();

    /**
     * 定时调度:每秒检查一次。
     *
     * 行为(按需求):
     *   ① 目标时刻前 5 分钟 → 启动所选 App(让用户知道时间快到了)
     *   ② 到点 → 立即执行流程(执行 M 次,间隔 X 毫秒)
     *   ③ 全局只认"当前开启"的那一个流程
     */
    /** 上次代报时间 + 是否正在报(避免叠加) */
    private long lastRelayAt = 0;
    private boolean relaying = false;
    /** 本次中继是什么时候开始的 —— 用来做看门狗,防止 relaying 卡死 */
    private long relayStartedAt = 0;
    /** 上次做完整流程内容备份的时刻(内容贵,半小时兜一次就够) */
    private long lastFullBackupAt = 0;

    /**
     * **代板子上报** —— 板子自己上不了网时的兜底。
     *
     * 典型场景:公司 WiFi 要飞连认证,板子连不上,只能开热点。
     * 这时板子发不出心跳,云端的在线状态 / 流程备份都会停。
     * 但手机有网(连板子热点时 Android 默认保留蜂窝),所以让 App 中转。
     *
     * 每 30 分钟一次 —— 不用太频繁:心跳的作用是"别人能看到状态",
     * 而流程内容变化也不频繁。板子自己心跳是 2 分钟,两者不冲突。
     */
    /**
     * 「连接方式」检测 —— 蓝牙 HID / USB 直插 / WiFi。
     *
     * 每 5 秒一次。**必须在后台线程跑** —— WiFi 探测要等超时。
     * WiFi 探测只探"上次成功的地址"(1.2 秒超时),不每次都广播 ——
     * 广播要等 6 秒,用来做后台轮询太重了。找不到就点「🔍 自动查找板子」。
     */
    /** 刷新「蓝牙控制通道」那行 */
    private void updateBleCtrlRow() {
        if (bleCtrlRow == null) return;
        Ble b = Ble.get();
        String mode;
        switch (board.getTransport()) {
            case Board.T_BLE:  mode = "只走蓝牙"; break;
            case Board.T_WIFI: mode = "只走 WiFi"; break;
            default:           mode = "自动(蓝牙优先)"; break;
        }
        bleCtrlRow.setText("蓝牙控制通道:" + b.stateText
                + (b.ready ? "  ✅" : "")
                + "\n控制方式:" + mode
                + (b.ready && board.getTransport() != Board.T_WIFI
                   ? "  → 正在走蓝牙" : ""));
        bleCtrlRow.setTextColor(b.ready ? C_OK : C_DIM);
    }

    /** 选「控制走哪条路」 */
    private void pickTransport() {
        final String[] names = {"自动(蓝牙可用就走蓝牙)", "只用蓝牙", "只用 WiFi"};
        final int[] vals = {Board.T_AUTO, Board.T_BLE, Board.T_WIFI};
        new android.app.AlertDialog.Builder(this)
                .setTitle("控制走哪条路")
                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        board.setTransport(vals[w]);
                        getSharedPreferences("board", MODE_PRIVATE).edit()
                                .putInt(PREF_TRANSPORT, vals[w]).apply();
                        updateBleCtrlRow();
                        toast("已切换:" + names[w]);
                        fetchStatus();
                        if (tab == TAB_SCRIPTS) loadScripts();
                    }
                })
                .show();
    }

    /**
     * 执行服务器捎回来的灯命令。
     *
     * ⚠️ 走 App 和板子的直连(WiFi / 蓝牙控制通道),所以是**立刻**生效的 ——
     *    而走板子自己心跳那条路要等 2 分钟,而且这块板子根本没外网、永远等不到。
     */
    private void applyServerLed(String ledJson) {
        try {
            JSONObject o = new JSONObject(ledJson);
            if (o.optBoolean("blink", false)) {
                boardLog("服务器命令:找板子(灯闪)");
                board.ledBase("blink", Board.NOOP);
            } else if (o.optBoolean("off", false)) {
                boardLog("服务器命令:关灯");
                board.ledBase("off", Board.NOOP);
            } else if (o.optBoolean("auto", false)) {
                boardLog("服务器命令:回到自动");
                board.ledBase("auto", Board.NOOP);
            } else {
                int r = o.optInt("r", 0), g = o.optInt("g", 0), b = o.optInt("b", 0);
                boardLog("服务器命令:颜色 " + r + "," + g + "," + b);
                board.ledBase(r + "," + g + "," + b, Board.NOOP);
            }
        } catch (Throwable t) {
            boardLog("服务器命令解析失败:" + t.getMessage());
        }
    }

    /** 发一条 LED 命令并 toast 结果 */
    private void ledSend(String what, Board.Cb cb) {
        board.ledBase(what, new Board.Cb() {
            @Override public void done(final boolean ok, final String body) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        toast(ok ? Board.messageOf(body) : "失败:" + body);
                    }
                });
            }
        });
    }

    /**
     * 「灯」对话框 —— 关灯 / 自动 / 指定颜色 / 找板子。
     *
     * ⚠️ 为什么需要这个:
     *      最早只有一个「点亮板子」按钮(只发"找板子")。
     *      用户看到灯是蓝的想问"怎么关",答案只有串口命令 —— 太不友好。
     */
    private void showLedDialog() {
        LinearLayout box = Dialogs.column(this);
        box.addView(Dialogs.label(this,
                "板子的 RGB 灯有三种状态:\n"
                + "  · 自动(默认):未连接=暗蓝 / 已连接=暗绿 / 执行中=暗黄\n"
                + "  · 手动色:选了就一直亮,不会被动变\n"
                + "  · 关灯:保持熄灭\n\n"
                + "现在灯是蓝的,说明「自动」模式下手机没连上板子。"));

        box.addView(Dialogs.value(this, "① 认板子", C_ACC));
        box.addView(btnRow(null, new String[][]{{"🔵 让灯闪(找板子)", "blink"}},
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        ledSend("blink", null);
                        ledDismiss();
                    }
                }));

        box.addView(Dialogs.value(this, "② 关闭 / 恢复", C_ACC));
        box.addView(btnRow(null, new String[][]{
                {"⚫ 关灯", "off"}, {"🔄 回到自动", "auto"}
        }, new View.OnClickListener() {
            @Override public void onClick(View v) {
                ledSend("off".equals(v.getTag()) ? "off" : "auto", null);
                ledDismiss();
            }
        }));

        box.addView(Dialogs.value(this, "③ 现场确认灯好不好用", C_ACC));
        box.addView(btnRow(null, new String[][]{{"🌈 四色自检(红→黄→绿→灭)", "test"}},
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        ledSend("test", null);
                        ledDismiss();
                    }
                }));
        box.addView(hint("四个颜色各亮 1.5 秒。要是四个都能看到,说明灯和引脚都是对的;"
                       + "要是只亮一个色/不亮,就是引脚或灯珠的问题。"));

        box.addView(Dialogs.value(this, "④ 指定颜色", C_ACC));
        box.addView(btnRow(null, new String[][]{
                {"🔴 红", "255,0,0"}, {"🟢 绿", "0,255,0"}, {"🔵 蓝", "0,0,255"}
        }, new View.OnClickListener() {
            @Override public void onClick(View v) {
                ledSend(String.valueOf(v.getTag()), null);
                ledDismiss();
            }
        }));
        box.addView(btnRow(null, new String[][]{
                {"🟡 黄", "255,180,0"}, {"🟣 紫", "180,0,255"}, {"⚪ 白", "255,255,255"}
        }, new View.OnClickListener() {
            @Override public void onClick(View v) {
                ledSend(String.valueOf(v.getTag()), null);
                ledDismiss();
            }
        }));
        box.addView(hint("选了颜色就一直亮,不会被状态色改掉。想还给状态色点「回到自动」。"));
        ledDlg = Dialogs.show(this, "💡 板子的灯", box, null, null, "关闭");
    }

    private android.app.AlertDialog ledDlg;
    private void ledDismiss() { if (ledDlg != null) { ledDlg.dismiss(); ledDlg = null; } }

    /** 刷新「💡 灯」那行(status 里带的 led 字段) */
    private void updateLedRow(String ledMode) {
        if (ledRow == null || ledMode == null || ledMode.isEmpty()) return;
        ledRow.setText("💡 灯:" + ledMode);
        ledRow.setTextColor(ledMode.contains("运行") ? C_WARN
                          : ledMode.contains("待机") ? C_ERR
                          : ledMode.contains("手动") ? C_ACC : C_DIM);
    }

    /**
     * 控制通道断了就**自动重连**。
     *
     * ⚠️⚠️ 为什么必须有(用户实测):
     *      HID 连着(手机认到了触摸屏)✅ 但**控制通道断了** ✅
     *      界面上显示"蓝牙控制通道:未连接" ✅ 而用户不会去点那个手动按钮 ✅
     *      结果:App 发的命令(包括「运行」)**全都发不出去** ⇒ 超时 ⇒
     *      用户看到的就是"点了运行半天不动、灯一闪而过" ✅
     *
     *      之前只有手动按钮能连,断了就一直是断的 ✅
     */
    private long lastBleRetryAt = 0;
    private void maybeReconnectBleCtrl() {
        if (Ble.get().ready) return;                 // 好好的不用管
        if (lastLink == null || !lastLink.hidReady()) return;  // HID 都没连,重连也没用
        long now = System.currentTimeMillis();
        if (now - lastBleRetryAt < 15000) return;    // 15 秒一次,别猛敲
        lastBleRetryAt = now;
        boardLog("控制通道断了,自动重连…");
        Ble.get().connect(this, new Ble.Cb() {
            @Override public void done(String body) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        boardLog("自动重连结果:" + Ble.get().stateText);
                        updateBleCtrlRow();
                        if (Ble.get().ready) fetchStatus();
                    }
                });
            }
        });
    }

    /** 刷新「☁️ 服务器」那行 */
    private void updateServerRow() {
        if (serverRow == null) return;
        serverRow.setText("☁️ 服务器:" + Server.stateText());
        serverRow.setTextColor(Server.lastState == 1 ? C_OK
                             : (Server.lastState == -1 ? C_ERR : C_DIM));
    }

    /** 上次探服务器的时间(每 30 秒最多一次) */
    private long lastPingAt = 0;

    /**
     * 启动时从服务器拉一次流程(第 ⑥ 项:服务器是权威,App 是缓存)。
     *
     * ⚠️ 不做"板子在不在线"的前置判断 —— Board 没有这个接口,
     *    而且板子没连上时拉下来的内容也需要写进本地元数据。
     *    失败就**不置完成标志**,过一会儿重试(60 秒一次,不刷屏)。
     */
    private boolean flowSyncDone = false;
    private long flowSyncTryAt = 0;
    private void maybeFlowSync() {
        if (flowSyncDone) return;
        long now = System.currentTimeMillis();
        if (now - flowSyncTryAt < 60000) return;
        flowSyncTryAt = now;
        FlowSync.pull(this, board, false, false /* 不占 busy */, new FlowSync.Cb() {
            @Override public void done(final boolean ok, final String info) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        boardLog(ok ? ("流程同步完成:" + info) : ("流程同步失败:" + info));
                        flowSyncDone = true;    // ⚠️ 不管成功失败都只跑一次 ——
                                                //    否则每 60 秒重试一次,而板子忙时
                                                //    回调不回来会越堆越多
                        if (ok) {
                            scriptsLoaded = false;     // 服务器可能带来了新流程
                            lastScriptsSig = "";
                            if (tab == TAB_SCRIPTS) loadScripts();
                        }
                    }
                });
            }
        });
    }

    private void linkTick() {
        if (linkBusy) return;
        linkBusy = true;
        final String lastHost = getSharedPreferences("board", MODE_PRIVATE)
                .getString("host", "");
        new Thread(new Runnable() {
            @Override public void run() {
                final Link.State st = Link.detect(MainActivity.this, lastHost, 1200);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        linkBusy = false;
                        lastLink = st;
                        updateBleCtrlRow();
                        if (linkRow != null) {
                            linkRow.setText(st.summary()
                                    + "   (" + st.costMs + "ms)");
                            linkRow.setTextColor(st.hidReady() || st.wifi ? C_OK : C_WARN);
                        }
                        // ☁️ 服务器状态行 + 启动时同步一次流程
                        //   ⚠️ 上次我把这两句插到了「连接蓝牙控制」按钮的回调里,
                        //      结果那行只在点按钮时才刷新,平时一直是空的。
                        updateServerRow();
                        maybeReconnectBleCtrl();
                        maybeFlowSync();
                        if (System.currentTimeMillis() - lastPingAt > 30000) {
                            lastPingAt = System.currentTimeMillis();
                            Server.ping(MainActivity.this, null);   // 兜底探活
                        }
                        // 变了才重报 —— 别每 5 秒都发一次
                        String key = st.mode() + "|" + st.hidName;
                        if (!key.equals(lastLinkSent)) {
                            lastLinkSent = key;
                            reportLinkIfKnown(st);
                        }
                    }
                });
            }
        }).start();
    }

    /** 把连接方式报到服务器(需要板子 MAC,拿不到就先跳过) */
    private void reportLinkIfKnown(final Link.State st) {
        String mac = boardMac();
        if (!mac.isEmpty()) {
            Server.reportLink(this, mac, st.mode(), st.hidName);
            return;
        }
        // ① 本地没缓存 → 先从**蓝牙 MAC 推**(ESP32 约定 WiFi = BT − 1)。
        //    这样"只有蓝牙通、WiFi 完全不可达"时也能把连接方式报上去。
        String guess = Link.guessBoardMacFromBle(this);
        if (!guess.isEmpty()) {
            cacheBoardMac(guess);
            Server.reportLink(this, guess, st.mode(), st.hidName);
            return;
        }
        // ② 蓝牙也拿不到 → 最后试试读板子
        if (board == null) return;
        board.status(new Board.Cb() {
            @Override public void done(boolean ok, String body) {
                if (!ok) return;
                try {
                    cacheBoardMac(new JSONObject(body).optString("mac", ""));
                } catch (Throwable ignored) {}
                String m = boardMac();
                if (!m.isEmpty()) {
                    Server.reportLink(MainActivity.this, m, st.mode(), st.hidName);
                }
            }
        });
    }

    /**
     * 板子 MAC(给上报用)。
     *
     * ⚠️ **必须缓存到本地**,不能每次都去问板子 ——
     *    典型场景:板子蓝牙连着(HID 通),但 App 读不到板子(WiFi 不通)。
     *    这时如果 MAC 只靠现场读取,就永远拿不到 → 连接方式也报不上去
     *    (实测踩过:三星上显示"蓝牙 HID",服务器却什么都没收到)。
     */
    private String boardMacCached = "";

    private void cacheBoardMac(String mac) {
        if (mac == null || mac.isEmpty()) return;
        boardMacCached = mac;
        getSharedPreferences("board", MODE_PRIVATE).edit()
                .putString("boardMac", mac).apply();
    }

    private String boardMac() {
        if (boardMacCached == null || boardMacCached.isEmpty()) {
            boardMacCached = getSharedPreferences("board", MODE_PRIVATE)
                    .getString("boardMac", "");
        }
        return boardMacCached == null ? "" : boardMacCached;
    }

    private void maybeRelayBoard() {
        long now = System.currentTimeMillis();
        android.util.Log.i("TouchRelay", "检查: relaying=" + relaying
                + " 距上次=" + ((now - lastRelayAt) / 1000) + "s board=" + (board != null));
        // ⚠️⚠️ **中继看门狗** —— 必须放在最前面。
        //     原来 relaying 只在"流程全读完了"才复位,
        //     任何一次回调不来(蓝牙超时/队列卡住)它就**永远是 true**,
        //     于是再也不上报 ⇒ 服务器上板子一直显示离线、
        //     最后上报时间停住不动(实测就是这个现象)。
        //     超过 3 分钟还没完成,就认为这次中继废了,允许下次重来。
        if (relaying) {
            // 3 分钟太长 —— 蓝牙单次失败率高,卡住一次要等 3 分钟才重试,
            // 而重试又可能失败,结果几十次里难得成功一次。
            // 45 秒足够让正常的 8 个请求跑完(每个 2~3 秒),卡住也能快速重来。
            if (now - relayStartedAt < 45 * 1000L) return;
            boardLog("上次中继卡住超时,放弃并允许重试");
            relaying = false;
        }
        if (board == null) return;
        // ⚠️⚠️ **间隔必须小于服务端的「在线」窗口**(ONLINE_MS = 5 分钟),
        //     否则两次中继之间看板会显示离线。
        //     原来 30 分钟 ⇒ 只有刚报完那 5 分钟是在线的(实测:56→76→96 秒
        //     心跳一直在变老,再过几分钟就离线了)。
        //     改成 2 分钟,和板子自己的心跳节奏一致。
        //     代价几乎为零:STATUS 请求会被「在途合并 + 短缓存」吸收,
        //     实际只多一个"发到服务器"的 HTTP(走手机流量,和蓝牙无关)。
        if (now - lastRelayAt < 2 * 60 * 1000L) return;
        lastRelayAt = now;
        relaying = true;
        relayStartedAt = now;
        // ⚠️ **中继期间暂停周期轮询**。
        //    中继要连发 8+ 个请求(状态 + 列表 + 每个流程),
        //    而蓝牙一次往返 2~3 秒。如果不让路,轮询会一直插队,
        //    中继排到最后永远做不完(实测:中继从来没成功上报过)。
        pollBusy = true;

        board.status(new Board.Cb() {
            @Override public void done(final boolean ok, final String statusBody) {
                if (!ok) {
                    // ⚠️⚠️ **失败要尽快重试,不能等 30 分钟**。
                    //     蓝牙单次请求失败率很高(实测约 60%),
                    //     如果失败就把 lastRelayAt 记成"现在",
                    //     下次要等 30 分钟 ⇒ 基本永远报不上去
                    //     ⇒ 服务器上板子一直显示离线。
                    //     把时间戳往前挪 29.5 分钟,让 30 秒后就能再来一次。
                    lastRelayAt = System.currentTimeMillis() - 29 * 60 * 1000L - 30000L;
                    relaying = false; pollBusy = false;
                    boardLog("代报失败,30 秒后重试");
                    return;
                }
                String mac = "";
                String bname = "";
                try {
                    JSONObject o = new JSONObject(statusBody);
                    mac = o.optString("mac", "");
                    bname = o.optString("name", "");
                    cacheBoardMac(mac);
                } catch (Throwable ignored) {}
                if (mac.isEmpty()) { relaying = false; return; }

                final String fMac = mac, fName = bname;
                // ① 先报"只有状态"的一份 —— **这一步就能让板子显示在线**
                //    (哪怕后面读流程全失败,板子的 last_seen 也更新了)
                //
                //    ⚠️ 同时处理服务器**捎回来的命令**(比如后台点的"灯")。
                //       板子自己连不上服务器,收不到心跳响应,
                //       所以后台的命令只能靠这里中转给板子。
                Server.relayBoard(MainActivity.this, fMac, fName, statusBody, null, null,
                        new Server.RelayCb() {
                            @Override public void done(final String ledJson) {
                                if (ledJson == null || ledJson.isEmpty()) return;
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        applyServerLed(ledJson);
                                    }
                                });
                            }
                        });
                boardLog("已代报状态(板子应该显示在线了)");

                // 再读流程列表 + 每个流程内容,读全了再报一次(带流程备份)
                board.scripts(new Board.Cb() {
                    @Override public void done(boolean ok2, String listBody) {
                        if (!ok2) { relaying = false; return; }
                        JSONArray names = new JSONArray();
                        JSONArray idxs = new JSONArray();
                        try {
                            JSONArray arr = new JSONObject(listBody).optJSONArray("scripts");
                            for (int i = 0; arr != null && i < arr.length(); i++) {
                                JSONObject sc = arr.optJSONObject(i);
                                if (sc == null) continue;
                                names.put(sc.optString("name", ""));
                                idxs.put(sc.optInt("index", i));
                            }
                        } catch (Throwable ignored) {}
                        // ② 拿到流程名就**再报一次**(带名字,不带内容)。
                        //    内容(每个流程一次 GET)是最贵的一步:蓝牙一次往返
                        //    2~3 秒,6 个流程就是 15 秒以上,中途任何一次失败
                        //    就前功尽弃 —— 所以拆成两步,先保"在线"和"名字"。
                        Server.relayBoard(MainActivity.this, fMac, fName, statusBody,
                                          names.toString(), null);
                        boardLog("已代报流程名 " + names.length() + " 个");

                        // ③ 内容备份**不必每 2 分钟做**(它最贵:6 个流程 = 6 个 GET)
                        //    每 30 分钟兜一次就够了 —— 它的作用是"误删了能找回来"
                        if (now - lastFullBackupAt > 30 * 60 * 1000L) {
                            lastFullBackupAt = now;
                            readFlowsRecursive(fMac, fName, statusBody, names, idxs, 0,
                                               new JSONObject(), 3);
                        } else {
                            relaying = false;
                            pollBusy = false;
                        }
                    }
                });
            }
        });
    }

    /** 挨个把流程内容读出来(一次一个请求,板子是单线程的,别并发压它) */
    private void readFlowsRecursive(final String mac, final String bname,
                                    final String statusBody, final JSONArray names,
                                    final JSONArray idxs, final int k,
                                    final JSONObject texts, final int budget) {
        // ⚠️ **预算用完就收工** —— 流程内容只是"备份的锦上添花",
        //    不值得为了它把整个中继拖到超时(那样连"在线"都报不上去)。
        if (budget <= 0 || k >= idxs.length()) {
            Server.relayBoard(this, mac, bname, statusBody, names.toString(),
                              texts.length() > 0 ? texts.toString() : null);
            boardLog("已代板子上报服务器(WiFi 不可达时的兜底)");
            relaying = false;
            pollBusy = false;
            return;
        }
        board.scriptText(idxs.optInt(k, k), new Board.Cb() {
            @Override public void done(boolean ok, String body) {
                if (ok) {
                    try {
                        JSONObject o = new JSONObject(body);
                        texts.put(o.optString("name", ""), o.optString("text", ""));
                    } catch (Throwable ignored) {}
                }
                // 失败就少一个预算 —— 连续失败会很快用完,不再硬撑
                readFlowsRecursive(mac, bname, statusBody, names, idxs, k + 1, texts,
                                   ok ? budget : budget - 1);
            }
        });
    }

    private void schedulerTick() {
        if (flows == null || time == null) return;

        // ⚠️ 必须遍历**所有**启用的流程,不能只取一个。
        //    以前用 flows.enabledName() —— 它按 SharedPreferences 的遍历顺序
        //    返回**第一个** enabled 的,而那个顺序是不确定的。
        //    同时开了两个定时时,可能永远只调度其中一个,
        //    新设的那个到点根本不跑(用户实测:"设了一分钟后执行,到点没点按钮")。
        final long now = time.nowMs();
        java.util.List<String> names = flows.enabledNames();
        for (String name : names) {
            FlowStore.Meta m = flows.get(name);
            if (m == null) continue;
            long target = m.targetMillis();
            if (target <= 0) continue;

            boolean dirty = false;

            // ① 提前 5 分钟启动 App
            long launchAt = target - 5 * 60 * 1000L;
            if (!m.appLaunched && now >= launchAt && now < target) {
                String pkg = (m.appPkg == null || m.appPkg.isEmpty()) ? selectedAppPkg : m.appPkg;
                if (pkg != null && !pkg.isEmpty()) {
                    try {
                        android.content.Intent i =
                                getPackageManager().getLaunchIntentForPackage(pkg);
                        if (i != null) {
                            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i);
                        }
                    } catch (Throwable ignored) {}
                }
                m.appLaunched = true;
                dirty = true;
                toast("定时快到了,已启动「" + appLabelOf(pkg, m.appName) + "」");
            }

            // ② 到点执行
            //    ⚠️ **已过期的不补跑** —— 否则一开启一个过期定时会立刻触发一次
            //       (实测:开了个 12:42 的定时,当时 15:30,一开启就跑了)。
            if (!m.fired && now >= target && !m.expired(now)) {
                m.fired = true;
                m.firedAt = now;
                dirty = true;
                String pkg = (m.appPkg == null || m.appPkg.isEmpty()) ? selectedAppPkg : m.appPkg;
                if (pkg != null && !pkg.isEmpty() && !m.appLaunched) {
                    try {
                        android.content.Intent i =
                                getPackageManager().getLaunchIntentForPackage(pkg);
                        if (i != null) {
                            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i);
                        }
                    } catch (Throwable ignored) {}
                }
                Integer idx = nameToIndex.get(name);
                if (idx != null) {
                    int times = Math.max(1, m.repeatCount);
                    int gap = m.intervalMs > 0 ? m.intervalMs : 0;
                    toast("定时到,开始执行「" + name + "」");
                    boardLog("定时触发: " + name + " (第 " + times + " 次)");
                    // ⚠️ 必须走 exec() 而不是直接 board.run():
                    //    exec() 会 setRunning(true) 盖上"执行中遮罩",把本 App 的按钮锁住。
                    //    踩过的坑:定时执行没锁界面,流程的注入点击落在**我们自己的
                    //    流程列表**上,把「删除」按钮点了一串 —— 5 个示例流程就是这样没的。
                    exec(idx, times, gap);
                } else {
                    toast("找不到流程「" + name + "」,请到流程页刷新列表");
                    boardLog("定时触发失败: 流程「" + name + "」不在板子列表里");
                }
            }

            if (dirty) flows.put(m);
        }
    }

    /**
     * 往板子日志里塞一行 —— 定时这类"人在别的 App"时发生的事,
     * 事后只能靠日志排查(实测:定时没触发时无从下手)。
     */
    /**
     * 往"板子日志"里写一行。
     *
     * ⚠️⚠️ 同时也写 logcat —— 踩过的坑:
     *      原来**只**调 board.appendLog(),数据要经 WiFi 发到板子。
     *      可板子在 AP 模式时手机根本够不到它 ⇒ 这些日志**全丢了**,
     *      排查时在 logcat 里一个字都看不到,白折腾很久。
     *      现在两条路都走:本地 logcat 一定有,板子能收到就顺便存一份。
     */
    private void boardLog(final String line) {
        android.util.Log.i("TouchPad", line);
        try {
            if (board == null) return;
            board.appendLog(line, new Board.Cb() {
                @Override public void done(boolean ok, String body) {}
            });
        } catch (Throwable ignored) {}
    }

    /**
     * 取 App 的显示名,供提示文字用。
     *
     * ⚠️ 不能直接用 m.appName —— 流程里可能没存(老流程 / 没选过目标 App),
     *    实测就会出现"已启动「」"这种空名字。
     *    顺序:包名查系统 → 流程里存的 → 全局选中的。
     */
    private String appLabelOf(String pkg, String fallbackName) {
        if (pkg != null && !pkg.isEmpty()) {
            try {
                android.content.pm.ApplicationInfo ai =
                        getPackageManager().getApplicationInfo(pkg, 0);
                CharSequence l = getPackageManager().getApplicationLabel(ai);
                if (l != null && l.length() > 0) return l.toString();
            } catch (Throwable ignored) {}
        }
        if (fallbackName != null && !fallbackName.isEmpty()) return fallbackName;
        if (selectedAppName != null && !selectedAppName.isEmpty()) return selectedAppName;
        return "(未知)";
    }

    /**
     * 自动查找板子(UDP 广播)。
     *
     * 场景:换了 WiFi / 板子重启后 DHCP 换了 IP,
     *      原来存的地址就失效了 —— 这个能直接找回来。
     *
     * @param manual 用户手动点的(要弹提示);false = 后台自动尝试(安静)
     */
    private void autoFindBoard(final boolean manual) {
        if (finding) return;
        finding = true;
        if (manual) toast("正在广播查找板子…");
        Discovery.find(6000, new Discovery.Cb() {
            @Override public void done(final String host) {
                finding = false;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (host == null) {
                            // UDP 没找到 → 再试板子的**热点地址**。
                            // 首次配置时板子还没连上任何 WiFi,只会开热点
                            // (ESP32-Touch-XXXX),此时它就是 192.168.4.1。
                            // 直接试这个地址就不用申请定位权限去读 SSID 了。
                            showApFallback(manual);
                            return;
                        }
                        board.setHost(host);
                        getSharedPreferences("board", MODE_PRIVATE).edit()
                                .putString("host", host).apply();
                        toast("找到板子:" + host);
                        fetchStatus();
                        if (tab == TAB_SCRIPTS) loadScripts();
                    }
                });
            }
        });
    }

    /**
     * 热点兜底:板子首次上电没配过 WiFi,只会开热点。
     * 手机连上那个热点后,板子地址固定是 192.168.4.1。
     */
    private void showApFallback(final boolean manual) {
        final String apHost = "192.168.4.1";
        if (manual) toast("局域网没找到,试板子热点地址 " + apHost + " …");
        board.setHost(apHost);
        board.status(new Board.Cb() {
            @Override public void done(boolean ok, String body) {
                if (ok) {
                    getSharedPreferences("board", MODE_PRIVATE).edit()
                            .putString("host", apHost).apply();
                    toast("已通过板子热点连上(" + apHost + ")");
                    fetchStatus();
                    showFirstSetupGuide();
                } else {
                    // 也不通 → 恢复原地址并给出完整引导
                    board.setHost(getSharedPreferences("board", MODE_PRIVATE)
                            .getString("host", ""));
                    if (manual) showFirstSetupGuide();
                }
            }
        });
    }

    /**
     * 查 APK 更新。
     *
     * 你要求:连接超时就算了(15 秒,在 Server 里设的)。
     * 所以这里**失败完全静默** —— 没网/服务器挂了都不影响用。
     */
    /**
     * 查 App 更新。
     *
     * **每天只弹一次**(你要的):按本地日历日判断,同一天内再启动不重复弹。
     * 失败/超时完全静默(15 秒超时在 Server 里设的)。
     */
    private void checkAppUpdate() {
        Server.checkUpdate(this, new Server.UpdateCb() {
            @Override public void done(final boolean hasUpdate, final String versionName,
                                        final int versionCode, final String url,
                                        final String notes, final long size) {
                if (!hasUpdate || url == null || url.isEmpty()) return;

                // ---- 每天只弹一次 ----
                final SharedPreferences sp = getSharedPreferences("board", MODE_PRIVATE);
                final String today = new java.text.SimpleDateFormat("yyyy-MM-dd",
                        java.util.Locale.US).format(new java.util.Date());
                if (today.equals(sp.getString("updateShownDate", ""))) {
                    // 今天已经弹过了,记一下就行(方便排查"为什么没弹")
                    sp.edit().putString("updateSkippedDate", today).apply();
                    return;
                }
                sp.edit().putString("updateShownDate", today).apply();

                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        showUpdateDialog(versionName, versionCode, url, notes, size, true);
                    }
                });
            }
        });
    }

    /**
     * 手动检查更新(设置里点版本号触发)。
     *
     * 和启动时那个的区别:
     *   · **不受"每天一次"限制** —— 是你主动点的
     *   · **没有更新也会告诉你**,而不是静默
     *   · 会先弹个"正在检查…"
     */
    private void manualCheckUpdate() {
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage("正在检查更新…");
        pd.setCancelable(true);
        pd.show();

        Server.checkUpdate(this, new Server.UpdateCb() {
            @Override public void done(final boolean hasUpdate, final String versionName,
                                        final int versionCode, final String url,
                                        final String notes, final long size) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try { pd.dismiss(); } catch (Throwable ignored) {}
                        if (hasUpdate && url != null && !url.isEmpty()) {
                            showUpdateDialog(versionName, versionCode, url, notes, size, false);
                        } else {
                            String cur = Server.localVersionName(MainActivity.this);
                            Dialogs.info(MainActivity.this, "已是最新版本",
                                    "当前版本:" + cur + "\n\n"
                                    + "(检查失败也会显示这个 —— 服务器超时 15 秒就算了,\n"
                                    + "没网/服务器挂了不影响 App 正常使用)");
                        }
                    }
                });
            }
        });
    }

    /** 更新弹窗(启动时和手动检查共用) */
    private void showUpdateDialog(String versionName, int versionCode,
                                  final String url, String notes, long size,
                                  boolean autoRemind) {
        LinearLayout box = Dialogs.column(this);
        box.addView(Dialogs.value(this,
                "新版本 " + versionName + "(" + versionCode + ")", C_ACC));
        box.addView(Dialogs.label(this,
                "当前版本:" + Server.localVersionName(this)
                + "\n大小:" + (size / 1024) + " KB"
                + (notes != null && !notes.isEmpty() ? "\n\n" + notes : "")));
        box.addView(Dialogs.label(this,
                "点「去下载」会用浏览器打开下载地址,\n下完手动安装即可(会自动覆盖,数据不丢)。"
                + (autoRemind ? "\n\n(这个提醒每天只弹一次)" : "")));
        Dialogs.show(this, "App 有更新", box, "去下载", new Dialogs.OnOk() {
            @Override public void onOk() {
                try {
                    startActivity(new android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(url)));
                } catch (Throwable e) {
                    toast("打不开浏览器,地址:" + url);
                }
            }
        }, "以后再说");
    }

    /** 启动提醒:权限 + 使用前必须准备的几件事。
     *
     * 为什么要弹:这些东西**不准备好,流程必然失败**,而且失败原因很难查 ——
     * 流程只是按坐标点击,它不知道"你没登录""没加抢票预约""没选购票人"。
     */
    private void showStartupNotice() {
        showStartupNotice(false);
    }

    /**
     * Android 13+ 要**运行时**申请通知权限。
     * 没有它前台服务照跑,但常驻通知不显示 ——
     * 用户就看不到"App 还活着"的提示,被杀也不知道。
     */
    private void askNotificationPermission() {
        java.util.ArrayList<String> need = new java.util.ArrayList<String>();
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                       != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                need.add("android.permission.POST_NOTIFICATIONS");
            }
            // ⚠️ Android 12+ 读已配对蓝牙设备要这个 ——
            //    用来认出板子、并推导它的 WiFi MAC(板子没网时的兜底)
            if (android.os.Build.VERSION.SDK_INT >= 31
                    && checkSelfPermission("android.permission.BLUETOOTH_CONNECT")
                       != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                need.add("android.permission.BLUETOOTH_CONNECT");
            }
            // 扫描板子也要(Android 12+)—— 配额不够时连 startScan 都进不去
            if (android.os.Build.VERSION.SDK_INT >= 31
                    && checkSelfPermission("android.permission.BLUETOOTH_SCAN")
                       != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                need.add("android.permission.BLUETOOTH_SCAN");
            }
        } catch (Throwable ignored) {}
        if (need.isEmpty()) return;
        try {
            requestPermissions(need.toArray(new String[0]), 9001);
        } catch (Throwable ignored) {}
    }

    private void showStartupNotice(final boolean force) {
        askNotificationPermission();
        SharedPreferences sp = getSharedPreferences("board", MODE_PRIVATE);
        if (!force && sp.getBoolean("noNotice", false)) return;

        LinearLayout box = Dialogs.column(this);

        // ---- 权限 ----
        box.addView(Dialogs.value(this, "① 权限", C_ACC));
        boolean overlay = android.provider.Settings.canDrawOverlays(this);
        box.addView(Dialogs.label(this,
                (overlay ? "✅ 悬浮窗权限:已开启" : "❌ 悬浮窗权限:未开启 ← 必须开")
                + "\n　　(悬浮窗、坐标拾取 📐 都需要它)"));
        if (!overlay) {
            TextView go = Dialogs.value(this, "　→ 点这里去开启", C_WARN);
            go.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        startActivity(new android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:" + getPackageName())));
                    } catch (Throwable e) {
                        toast("请手动到 设置 → 应用 → 显示在其他应用上层");
                    }
                }
            });
            box.addView(go);
        }
        box.addView(Dialogs.label(this,
                "网络权限安装时已自动授予。\n"
                + "蓝牙是系统层面配对板子,不需要 App 权限。"));

        // ---- 页面识别(强烈建议开)----
        boolean pwon = false;
        try { pwon = PageWatcher.enabled(); } catch (Throwable ignored) {}
        box.addView(Dialogs.value(this, "② 页面识别" + (pwon ? "" : "(建议开启)"), C_ACC));
        if (pwon) {
            box.addView(Dialogs.label(this, "✅ 已开启 —— 悬浮窗会显示当前页面,"
                    + "脚本可以用 waitfor / ifpage"));
        } else {
            box.addView(Dialogs.label(this,
                    "❌ 未开启 —— 不开的话脚本里只能用 wait 猜时间,"
                    + "页面慢一点就点空,而且失败了不知道为什么。"));
            TextView go = Dialogs.value(this, "　→ 点这里去开启(只看不点,不代替你操作)", C_WARN);
            go.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        startActivity(new android.content.Intent(
                                android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                        toast("在列表里找「触摸注入板 · 页面识别」并打开");
                    } catch (Throwable e) {
                        toast("请手动到 设置 → 无障碍 里开启");
                    }
                }
            });
            box.addView(go);
            TextView why = Dialogs.value(this, "　→ 它是干什么的、打不开怎么办", C_DIM);
            why.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Help.page(MainActivity.this, false);
                }
            });
            box.addView(why);
        }

        // ---- 使用前准备 ----
        box.addView(Dialogs.value(this, "③ 用之前必须准备好(否则流程必然失败)", C_WARN));
        box.addView(Dialogs.label(this,
                "· 先登录目标 App —— 流程不会帮你登录\n"
                + "· 想抢的票先加进「我的 → 抢票预约」,并选好票档\n"
                + "· 在「我的」里设置好实名购票人 / 观演人\n"
                + "· 确认 App 里没有挡在前面的弹窗(更新提示、活动广告等)"));

        // ---- 注意事项 ----
        box.addView(Dialogs.value(this, "④ 注意事项", C_ACC));
        box.addView(Dialogs.label(this,
                "· 流程运行期间别手动碰手机 —— 注入的是绝对坐标,会和你打架\n"
                + "· 别让手机息屏(建议把息屏时间调长,或临时设为永不息屏)\n"
                + "· 同一时间只能开启一个流程(开启新的会自动暂停别的)\n"
                + "· 开跑前确认:蓝牙已连板子,且 HID 显示「就绪」\n"
                + "· 换了 WiFi 后点「自动查找板子」,不用手填 IP\n"
                + "· 界面变化(改版/换机型)后,坐标要重新拾取"));

        // ---- 边界 ----
        box.addView(Dialogs.value(this, "⑤ 用途边界", C_DIM));
        box.addView(Dialogs.label(this,
                "这是个通用的触摸注入工具,只按你录的坐标点击。\n"
                + "请在你有权操作的设备和你自己的账号上使用。"));

        final android.widget.CheckBox noMore = new android.widget.CheckBox(this);
        // 常驻入口打开时默认**不勾选** —— 直接点确定就等于"恢复启动提醒"
        noMore.setChecked(!force && sp.getBoolean("noNotice", false));
        noMore.setText("不再提示(设置里可以再看，取消勾选可恢复)");
        noMore.setTextColor(C_DIM);
        noMore.setTextSize(12);
        box.addView(noMore);

        // ⚠️ 悬浮窗是**系统级覆盖层**,会盖在弹窗上面 —— 实测把「① 权限」那一段挡住了。
        //    所以弹窗期间先把它收起来,关掉弹窗再放回来。
        final boolean hadFloat = (floatWin != null && floatWin.isShowing());
        if (hadFloat) floatWin.hideTemporarily(120000);

        android.app.AlertDialog dlg = Dialogs.show(this, "使用前须知", box,
                "我知道了", new Dialogs.OnOk() {
            @Override public void onOk() {
                // 勾上 = 以后不弹;取消勾 = 恢复启动提醒(设置里再进来自动取消勾选即可)
                getSharedPreferences("board", MODE_PRIVATE).edit()
                        .putBoolean("noNotice", noMore.isChecked()).apply();
                if (!noMore.isChecked()) toast("已恢复「启动提醒」");
            }
        }, "关闭");

        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) {
                if (hadFloat && floatWin != null && !floatWin.isShowing()) floatWin.show();
            }
        });
    }

    /** 首次配网引导:把"隐性知识"讲清楚 */
    private void showFirstSetupGuide() {
        LinearLayout box = Dialogs.column(this);
        box.addView(Dialogs.value(this, "首次使用 / 换了网络?", C_WARN));
        box.addView(Dialogs.label(this,
                "板子上电后如果连不上 WiFi,会自己开一个热点。按下面做:"));

        box.addView(Dialogs.value(this, "① 手机连板子的热点", C_ACC));
        box.addView(Dialogs.label(this,
                "到 设置 → WLAN,找名字像\n"
                + "　　ESP32-Touch-XXXX\n"
                + "的热点(XXXX 是板子 MAC 后两位),密码:12345678"));

        box.addView(Dialogs.value(this, "② 回到本 App", C_ACC));
        box.addView(Dialogs.label(this,
                "点「🔍 自动查找板子」—— 这时能连上(板子在 192.168.4.1)。"));

        box.addView(Dialogs.value(this, "③ 在 App 里填 WiFi", C_ACC));
        box.addView(Dialogs.label(this,
                "状态页 → 设置 → 给板子配网,填你家 WiFi 的名字和密码。\n"
                + "连上后板子会自动切回你的 WiFi(热点关掉)。"));

        box.addView(Dialogs.value(this, "④ 手机切回你家 WiFi", C_ACC));
        box.addView(Dialogs.label(this,
                "App 会自动发现板子在你家网络里的新 IP,不用手填。"));

        final android.app.AlertDialog[] h = new android.app.AlertDialog[1];
        h[0] = Dialogs.show(this, "怎么给板子配网", box, "去 WLAN 设置",
                new Dialogs.OnOk() {
                    @Override public void onOk() {
                        h[0].dismiss();
                        try {
                            startActivity(new android.content.Intent(
                                    android.provider.Settings.ACTION_WIFI_SETTINGS));
                        } catch (Throwable e) { toast("请手动打开 WLAN 设置"); }
                    }
                }, "知道了");
    }

    /**
     * 页面推送节拍(700ms)。
     *
     * 只在**流程执行期间**推 —— 空闲时不推,省电也省板子的 CPU。
     * 之所以要 700ms 这么快:脚本里的 waitfor 靠它判断页面到没到,
     * 推得慢会让 waitfor 反应迟钝。
     */
    private final Runnable pageTick = new Runnable() {
        @Override public void run() {
            try {
                if (PageWatcher.enabled() && board != null) {
                    // ⚠️ 先刷新「页面特征」—— 大麦这种 Fragment 切换的 App,
                    //    Activity 不变,只有页面文字会变。不刷新的话
                    //    切来切去板子永远收到旧页面(用户实测反馈)。
                    PageWatcher.refreshIfDirty();
                    boolean executing = runningNow || System.currentTimeMillis() < pagePushUntil;
                    boolean changed = PageWatcher.revision() != lastPageRev;

                    // 两种时候推:
                    //   ① 页面**变了** → 立刻推(脚本开头的 waitfor 就不用干等)
                    //   ② 流程执行中 → 每 700ms 推一次(文字会变,waitfor 靠它)
                    if (changed || executing) {
                        lastPageRev = PageWatcher.revision();
                        pagePushN++;
                        // 文字只在需要时读(遍历控件树有开销)
                        String text = (changed || pagePushN % 2 == 0)
                                      ? PageWatcher.pageTextNow() : null;
                        board.pushPage(PageWatcher.activityFull(), text);
                    }
                }
            } catch (Throwable ignored) {}
            ui.postDelayed(this, 700);
        }
    };

    /** 找出"最近要执行"的流程,推给悬浮窗展示 */
    private void updateFloatSchedule() {
        if (floatWin == null || !floatWin.isShowing()) return;
        long now = time.nowMs();

        String bestName = null;
        long bestLeft = Long.MAX_VALUE;

        // 优先看已开启的那个(它才是真会跑的)
        String en = flows.enabledName();
        if (en != null) {
            FlowStore.Meta em = flows.get(en);
            long t = em.targetMillis();
            if (t > 0) { bestName = en; bestLeft = t - now; }
        }
        // 没有已开启的定时,就看所有流程里最近的一个(仅展示)
        if (bestName == null) {
            for (String nm : flows.allNames()) {
                FlowStore.Meta fm = flows.get(nm);
                long t = fm.targetMillis();
                if (t <= 0) continue;
                long left = t - now;
                if (left > 0 && left < bestLeft) { bestLeft = left; bestName = nm; }
            }
        }

        boolean running = runningName != null && !runningName.isEmpty();
        if (running) {
            floatWin.setSchedule(runningName, "", true);
        } else if (bestName != null && bestLeft > 0) {
            floatWin.setSchedule(bestName, "还有 " + fmtLeft(bestLeft), false);
        } else if (bestName != null) {
            floatWin.setSchedule(bestName, "时间已到", false);
        } else {
            floatWin.setSchedule(null, "", false);
        }
    }

    private EditText focusedEdit;

    /** 给弹窗里的输入框统一挂上:记录焦点 + 自动收键盘 */
    private EditText mkEdit(String hint, String text, boolean multiline) {
        final EditText e = new EditText(this);
        if (hint != null) e.setHint(hint);
        if (text != null) e.setText(text);
        if (multiline) {
            e.setGravity(Gravity.TOP | Gravity.START);
            e.setTextSize(13);
            e.setTypeface(android.graphics.Typeface.MONOSPACE);
        }
        e.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                if (has) focusedEdit = e;
            }
        });
        return e;
    }

    // ================================================================ 菜单
    private void openMenu() {
        if (rootFrame == null) return;
        MenuPanel.show(this, rootFrame, statusBarHeight(),
                selectedAppName, new MenuPanel.OnPick() {
                    @Override public void onPick(String key) {
                        if (MenuPanel.ITEM_HELP.equals(key)) {
                            openHelp();
                        } else if (MenuPanel.ITEM_CHECK_UPDATE.equals(key)) {
                            // 菜单底部点版本号 → 检查更新
                            manualCheckUpdate();
                        } else if (MenuPanel.ITEM_ABOUT.equals(key)) {
                            About.show(MainActivity.this);
                        } else if (MenuPanel.ITEM_SETTINGS.equals(key)) {
                            showSettings();
                        } else if (MenuPanel.ITEM_APP_LIST.equals(key)) {
                            showAppList();
                        }
                    }
                });
    }

    /** 帮助页:所有"为什么 / 怎么办"都在这里(设置里只留能点的开关) */
    private void openHelp() {
        Help.show(this, new Help.OnPick() {
            @Override public void onPick(String key) {
                if (Help.T_PAGE.equals(key)) {
                    Help.page(MainActivity.this, PageWatcher.enabled());
                } else if (Help.T_KEYBOARD.equals(key)) {
                    showKeyboardHelp();
                } else if (Help.T_WIFI.equals(key)) {
                    showFirstSetupGuide();
                } else if (Help.T_NOTICE.equals(key)) {
                    showStartupNotice(true);
                } else if (Help.T_SLEEP.equals(key)) {
                    Help.sleep(MainActivity.this);
                } else if (Help.T_FLOAT.equals(key)) {
                    Help.floatWindow(MainActivity.this);
                } else if (Help.T_EMPTY.equals(key)) {
                    Help.empty(MainActivity.this);
                } else if (Help.T_DATA.equals(key)) {
                    Help.data(MainActivity.this);
                } else if (Help.T_KILL.equals(key)) {
                    Help.kill(MainActivity.this);
                }
            }
        });
    }

    /** 设置页:悬浮窗 / 配网(具体功能在 P7、P8 实现) */
    /**
     * 设置页 —— **只放能点的开关和入口**。
     *
     * 为什么这么改:原来这里混了一堆说明性文字(键盘弹窗怎么解、
     * 连不上 WiFi 怎么办、使用前须知…),越堆越长,
     * 真正的功能开关反而找不到。现在解释都挪到 **[菜单 → 帮助]** 了。
     */
    private void showSettings() {
        LinearLayout box = Dialogs.column(this);

        // ---- 板子连接 ----
        box.addView(Dialogs.label(this, "板子连接"));
        TextView addr = Dialogs.value(this, board.baseUrl(), C_ACC);
        addr.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { askHost(); }
        });
        box.addView(addr);
        TextView find = Dialogs.value(this, "🔍 自动查找板子", C_WARN);
        find.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { autoFindBoard(true); }
        });
        box.addView(find);

        // ---- 时间 ----
        box.addView(Dialogs.label(this, "时间"));
        TextView tz = Dialogs.value(this,
                time.city() + "   " + time.zoneOffsetText(), C_ACC);
        tz.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { askCity(); }
        });
        box.addView(tz);

        // ---- 悬浮窗 ----
        box.addView(Dialogs.label(this, "悬浮窗"));
        final boolean on = (floatWin != null && floatWin.isShowing());
        TextView fw = Dialogs.value(this,
                on ? "✅ 已开启(点一下关闭)" : "⭕ 已关闭(点一下开启)",
                on ? C_OK : C_DIM);
        fw.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleFloatWindow(); }
        });
        box.addView(fw);

        // ---- 执行时保持屏幕常亮 ----
        box.addView(Dialogs.label(this, "执行时保持屏幕常亮"));
        boolean canWrite = KeepAwake.hasPermission(this);
        boolean kaOn = KeepAwake.enabled(this);
        TextView kaBtn = Dialogs.value(this,
                (kaOn ? "✅ 已开启" : "⭕ 已关闭")
                + " · 系统息屏:" + KeepAwake.describe(KeepAwake.currentTimeout(this))
                + "  (点一下切换)",
                kaOn ? C_OK : C_DIM);
        kaBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean now = KeepAwake.enabled(MainActivity.this);
                KeepAwake.setEnabled(MainActivity.this, !now);
                if (now) KeepAwake.release(MainActivity.this);
                toast(now ? "已关闭屏幕常亮" : "已开启:执行期间不让息屏");
                showSettings();
            }
        });
        box.addView(kaBtn);
        if (!canWrite) {
            TextView perm = Dialogs.value(this,
                    "⚠️ 缺「修改系统设置」权限 —— 点这里去开", C_ERR);
            perm.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    KeepAwake.requestPermission(MainActivity.this);
                    toast("找到「触摸注入板」并允许修改系统设置");
                }
            });
            box.addView(perm);
        } else {
            box.addView(Dialogs.label(this,
                    "已授权。执行开始改成「永不」,结束自动改回。"));
        }

        // ---- 页面识别(无障碍)----
        box.addView(Dialogs.label(this, "页面识别(无障碍)"));
        boolean pw = false, pwSwitch = false;
        String pwCur = "";
        try {
            pw = PageWatcher.enabled();
            pwSwitch = PageWatcher.enabledInSettings(this);
            pwCur = PageWatcher.summary();
        } catch (Throwable ignored) {}
        // 三种状态要分清,否则"开关开着但没连上"会被误以为已经开了
        String pwText; int pwColor;
        if (pw) {
            pwText = "✅ 已开启" + (pwCur.isEmpty() ? "" : " · " + pwCur) + "  (点一下去管理)";
            pwColor = C_OK;
        } else if (pwSwitch) {
            pwText = "⚠️ 开关是开的,但没连上 —— 重启手机才能恢复";
            pwColor = C_ERR;
        } else {
            pwText = "❌ 未开启 —— 点这里去开启";
            pwColor = C_ERR;
        }
        TextView pwv = Dialogs.value(this, pwText, pwColor);
        pwv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new android.content.Intent(
                            android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    toast("在列表里找「触摸注入板 · 页面识别」并打开");
                } catch (Throwable e) {
                    toast("请手动到 设置 → 无障碍 里开启");
                }
            }
        });
        box.addView(pwv);
        TextView pwHelp = Dialogs.value(this, "❓ 它是干什么的 / 打不开怎么办", C_DIM);
        pwHelp.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Help.page(MainActivity.this, PageWatcher.enabled());
            }
        });
        box.addView(pwHelp);

        // ---- 给板子配网 ----
        box.addView(Dialogs.label(this, "给板子配网"));
        TextView wf = Dialogs.value(this, "点这里修改板子连接的 WiFi", C_ACC);
        wf.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { askWifi(); }
        });
        box.addView(wf);

        // ---- 底部:版本 + 检查更新 ----
        View sep = new View(this);
        sep.setBackgroundColor(C_LINE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        slp.topMargin = dp(18);
        sep.setLayoutParams(slp);
        box.addView(sep);

        int vc = 0;
        try {
            vc = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Throwable ignored) {}

        LinearLayout verBox = new LinearLayout(this);
        verBox.setOrientation(LinearLayout.VERTICAL);
        verBox.setGravity(android.view.Gravity.CENTER);
        verBox.setPadding(dp(10), dp(14), dp(10), dp(14));
        android.graphics.drawable.GradientDrawable vbg =
                new android.graphics.drawable.GradientDrawable();
        vbg.setColor(0x221f6feb);
        vbg.setStroke(dp(1), 0x661f6feb);
        vbg.setCornerRadius(dp(8));
        verBox.setBackground(vbg);
        verBox.setClickable(true);
        verBox.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { manualCheckUpdate(); }
        });
        LinearLayout.LayoutParams vblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vblp.topMargin = dp(12);
        verBox.setLayoutParams(vblp);

        TextView verTv = new TextView(this);
        verTv.setText("触摸注入板 v" + Server.localVersionName(this) + " (build " + vc + ")");
        verTv.setTextColor(C_ACC);
        verTv.setTextSize(14);
        verTv.setGravity(android.view.Gravity.CENTER);
        verBox.addView(verTv);

        TextView verHint = new TextView(this);
        verHint.setText("🔄 点这里检查更新");
        verHint.setTextColor(C_WARN);
        verHint.setTextSize(12);
        verHint.setGravity(android.view.Gravity.CENTER);
        verHint.setPadding(0, dp(4), 0, 0);
        verBox.addView(verHint);

        TextView helpHint = new TextView(this);
        helpHint.setText("更多说明在 菜单 ☰ → 帮助");
        helpHint.setTextColor(C_DIM);
        helpHint.setTextSize(11);
        helpHint.setGravity(android.view.Gravity.CENTER);
        helpHint.setPadding(0, dp(10), 0, 0);
        box.addView(verBox);
        box.addView(helpHint);

        Dialogs.show(this, "设置", box, null, null, "关闭");
    }

    /** App 列表:跳到独立的全屏选择页(弹窗与输入法冲突,详见 AppListActivity) */
    private void showAppList() {
        startActivity(new android.content.Intent(this, AppListActivity.class));
    }

    /**
     * ⚠️ 这里**曾经**在 onPause 里停掉 pageTick,想给流程编辑器让带宽 ——
     *    结果是个大坑:你一进大麦(App 退到后台),**页面推送就停了**,
     *    板子永远收到旧页面(用户实测反馈"切来切去都不变")。
     *
     *    真正该修的是**板子侧** —— 跑流程时它的 WebServer 完全不应答
     *    (Scripts.run 同步阻塞,delay 期间 loop 停转)。
     *    已经在 script_engine 里加了 idleWait(等待时服务网络),
     *    实测并发读接口 **40/40 成功、0 失败**,所以这里不需要再让了。
     *
     *    页面推送必须**一直跑** —— 它是页面识别和 waitfor 的命根子。
     */
    @Override
    protected void onResume() {
        super.onResume();
        // ⚠️ 回到自己界面才复位 —— 用来判断"目标 App 起来了"(见 waitAppForegroundThenExec)
        selfStopped = false;
        // 从 App 列表页回来时,刷新已选中的目标
        SharedPreferences sp = getSharedPreferences("board", MODE_PRIVATE);
        String n = sp.getString("appName", "");
        String p = sp.getString("appPkg", "");
        if (!n.equals(selectedAppName) || !p.equals(selectedAppPkg)) {
            selectedAppName = n;
            selectedAppPkg = p;
            if (tab == TAB_SCRIPTS) showTab(TAB_SCRIPTS);
        }
    }

    /** 启动已选中的目标 App。返回是否成功 */
    private boolean launchSelectedApp() {
        if (selectedAppPkg == null || selectedAppPkg.isEmpty()) {
            toast("请先在菜单 → App 列表里选择目标应用");
            return false;
        }
        try {
            android.content.Intent i =
                    getPackageManager().getLaunchIntentForPackage(selectedAppPkg);
            if (i == null) {
                toast("这个应用没有启动入口,可能已被卸载");
                return false;
            }
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

            // 只在**真的挡住右上角**时才提示让路。
            // 不再自动隐藏 —— 自动隐藏是一刀切(不管压没压住都藏、藏多久靠猜),
            // 改成提示 + 手动按钮,行为可预期。
            if (floatWin != null && floatWin.isShowing()) {
                android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                if (floatWin.coversTopRight(dm.widthPixels, dm.heightPixels)) {
                    toast("悬浮窗可能挡住广告的「跳过」按钮,可点悬浮窗上的 🙈 让它让路");
                }
            }

            startActivity(i);
            return true;
        } catch (Exception e) {
            toast("启动失败:" + e.getMessage());
            return false;
        }
    }

    // ================================================================ 键盘弹窗帮助
    /**
     * 三星「是否连入外接键盘」弹窗的说明与解决入口。
     *
     * 这个弹窗是系统行为,App 关不掉。这里给三条可操作的办法,
     * 并提供一键跳到蓝牙设置(去关那个设备的「文本输入」开关)。
     */
    private void showKeyboardHelp() {
        LinearLayout box = Dialogs.column(this);

        box.addView(Dialogs.value(this, "为什么会弹", C_FG));
        TextView t1 = new TextView(this);
        t1.setTextSize(12);
        t1.setTextColor(C_DIM);
        t1.setText("只要板子和手机是「已配对」状态,三星在你点输入框时就会问一次。"
                + "这和板子是不是键盘无关 —— 它现在只是个触摸屏。");
        box.addView(t1);

        box.addView(Dialogs.value(this, "办法 1:直接点「否」", C_OK));
        TextView t2 = new TextView(this);
        t2.setTextSize(12);
        t2.setTextColor(C_DIM);
        t2.setText("点掉之后输入完全正常,同一次使用里不会再弹。最省事。");
        box.addView(t2);

        box.addView(Dialogs.value(this, "办法 2:关掉该设备的「文本输入」", C_OK));
        TextView t3 = new TextView(this);
        t3.setTextSize(12);
        t3.setTextColor(C_DIM);
        t3.setText("蓝牙设置 → ESP32S3 Touch HID 右侧齿轮 → 把「文本输入」关掉。\n"
                + "这等于告诉系统:别把它当输入设备。可能是最彻底的方案。");
        box.addView(t3);

        box.addView(Dialogs.value(this, "办法 3:要长时间打字就临时取消配对", C_OK));
        TextView t4 = new TextView(this);
        t4.setTextSize(12);
        t4.setTextColor(C_DIM);
        t4.setText("配对记录本身就是触发器。不用板子时解绑,打字就不受影响。");
        box.addView(t4);

        final android.app.AlertDialog[] h = new android.app.AlertDialog[1];
        h[0] = Dialogs.show(this, "关于外接键盘弹窗", box, "去蓝牙设置",
                new Dialogs.OnOk() {
                    @Override public void onOk() {
                        h[0].dismiss();
                        try {
                            startActivity(new android.content.Intent(
                                    android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
                        } catch (Throwable e) {
                            toast("请手动打开 设置 → 蓝牙");
                        }
                    }
                }, "知道了");
    }

    // ================================================================ 配网(P8)
    /** 给板子配网:局域网下发新的 WiFi 凭据(失败自动回退) */
    private void askWifi() {
        LinearLayout box = Dialogs.column(this);
        box.addView(Dialogs.label(this, "WiFi 名称(SSID)"));
        final EditText ssid = mkEdit(null, "", false);
        box.addView(ssid);
        box.addView(Dialogs.label(this, "WiFi 密码"));
        final EditText pass = mkEdit(null, "", false);
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(pass);
        TextView tip = new TextView(this);
        tip.setText("板子会立刻尝试连接新 WiFi,最多等 20 秒。\n"
                + "如果连不上(密码错/信号弱),会自动回退到原来的网络,不会失联。\n"
                + "注意:配网期间手机和板子要能互相访问。");
        tip.setTextColor(C_WARN);
        tip.setTextSize(11);
        box.addView(tip);

        Dialogs.show(this, "给板子配网", box, "开始连接", new Dialogs.OnOk() {
            @Override public void onOk() {
                String ss = ssid.getText().toString().trim();
                String pp = pass.getText().toString();
                if (ss.isEmpty()) { toast("请填 WiFi 名称"); return; }
                toast("正在连接,最多等 20 秒…");
                board.wifi(ss, pp, new Board.Cb() {
                    @Override public void done(boolean ok, String body) {
                        toast(Board.messageOf(body));
                        ui.postDelayed(new Runnable() {
                            @Override public void run() { showTab(TAB_STATUS); }
                        }, 800);
                    }
                });
            }
        }, "取消");
    }

    // ================================================================ 悬浮窗
    private void toggleFloatWindow() {
        if (floatWin != null && floatWin.isShowing()) {
            floatWin.cancelRestore();       // 手动关就别再自动弹回来
            floatWin.hide();
            getSharedPreferences("board", MODE_PRIVATE).edit()
                    .putBoolean("floatWin", false).apply();
            toast("悬浮窗已关闭");
            return;
        }
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Dialogs.confirm(this, "需要悬浮窗权限",
                    "悬浮窗需要「显示在其他应用上层」权限,点确定去系统设置里打开。",
                    "去设置", new Dialogs.OnOk() {
                        @Override public void onOk() {
                            try {
                                startActivity(new android.content.Intent(
                                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:" + getPackageName())));
                            } catch (Throwable t) {
                                toast("请手动到 设置→应用→显示在其他应用上层 里开启");
                            }
                        }
                    });
            return;
        }
        tryShowFloat();
        getSharedPreferences("board", MODE_PRIVATE).edit()
                .putBoolean("floatWin", true).apply();
        toast("悬浮窗已开启,拖动左侧 ⠿ 可以移动");
    }

    private void tryShowFloat() {
        if (floatWin == null) return;
        if (!android.provider.Settings.canDrawOverlays(this)) return;
        floatWin.show();
        floatWin.setHeader(selectedAppName, time == null ? null : time.nowText());
        if (lastLogText != null) floatWin.setLog(lastLogText);
    }

    /** 悬浮窗用的最近日志 */
    private String lastLogText = null;
    private volatile String runningName = "";
    /** 给 PageWatcher 查应用名用的全局 Context(只读,不持有 Activity) */
    private static android.content.Context sAppCtx = null;
    public static android.content.pm.PackageManager appContextStatic() {
        return sAppCtx == null ? null : sAppCtx.getPackageManager();
    }

    private boolean finding = false;        // 正在广播找板子
    private int     failCount = 0;          // 连续请求失败次数
    private boolean triedAutoFind = false;  // 本轮是否已自动查找过
    private JSONObject lastStatus = null;      // 最近一次 /api/status
    private JSONObject pendingFwUpdate = null; // 控制中心给的固件更新信息
    private volatile boolean runningNow = false;   // 板子正在跑流程
    private volatile long pagePushUntil = 0;       // 到点前继续推页面
    private int pagePushN = 0;
    private int lastPageRev = -1;                  // 上次推送给板子的页面版本
    private String boardMac = "";                  // 板子的 WiFi MAC
    private volatile boolean boardBusy = false;    // 板子正在忙(跑流程或频率循环)
    private boolean lastNeedAlive = false;         // 调试用
    private long schedReportAt = 0;                // 上次上报定时的时间
    private String schedFingerprint = "";          // 定时内容的指纹(变了才重报)   // 板子当前正在跑的流程名
    /** 上一次"哪些流程已过期"的快照 —— 变化时才重排列表 */
    private String lastExpiredSig = null;

    private int floatProbeTick = 0;

    /**
     * 让悬浮窗的**可触摸拖柄**在流程运行期间隐藏。
     *
     * 为什么必须做:拖柄是悬浮窗里唯一可触摸的部分,如果它正好盖住流程要点的
     * 位置,那次点击就会被它吃掉,流程静默失败。所以只要板子在跑流程,
     * 就把拖柄藏起来(面板本身不可触摸,可以留着看日志)。
     */
    private void syncFloatExecuting() {
        if (floatWin == null || !floatWin.isShowing()) return;
        if (++floatProbeTick < 3) return;      // 不必每秒都问
        floatProbeTick = 0;
        board.status(new Board.Cb() {
            @Override public void done(boolean ok, String body) {
                if (!ok) return;
                try {
                    JSONObject o = new JSONObject(body);
                    JSONObject r = o.optJSONObject("run");
                    boolean run = r != null && r.optBoolean("running", false);
                    // 💡 把板子报的灯状态显示出来(不用再盯板子)
                    updateLedRow(r == null ? null : r.optString("led", ""));
                    if (floatWin != null && floatWin.isShowing()) {
                        floatWin.setExecuting(run);
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    // ================================================================ 时间
    /** 点时间:立即同步网络时间 */
    private void doSyncNow() {
        if (clockView != null) clockView.setText("同步中…");
        time.sync(new TimeKeeper.SyncCb() {
            @Override public void done(boolean ok, String msg) {
                if (clockView != null) clockView.setText(time.nowText());
                toast(msg);
            }
        });
    }

    /** 长按时间:时区 / 同步状态设置 */
    private void askTimeSettings() {
        LinearLayout box = Dialogs.column(this);

        box.addView(Dialogs.label(this, "当前时间"));
        box.addView(Dialogs.value(this, time.nowFullText(), C_FG));
        box.addView(Dialogs.label(this, "同步状态"));
        box.addView(Dialogs.value(this, time.statusText(), 0xff3fb950));
        box.addView(Dialogs.label(this, "与网络的偏差"));
        box.addView(Dialogs.value(this, time.offsetText(), C_FG));

        box.addView(Dialogs.label(this, "时区 / 城市"));
        final TextView pick = Dialogs.value(this,
                time.city() + "   " + time.zoneOffsetText(), C_ACC);
        box.addView(pick);

        final AlertDialog[] holder = new AlertDialog[1];
        holder[0] = Dialogs.show(this, "时间设置", box, "立即同步", new Dialogs.OnOk() {
            @Override public void onOk() { holder[0].dismiss(); doSyncNow(); }
        }, "关闭");
        // 点"时区"那一行弹出城市选择
        pick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                holder[0].dismiss();
                askCity();
            }
        });
    }

    /** 城市 + 时区选择 */
    private void askCity() {
        LinearLayout box = Dialogs.column(this);
        box.addView(Dialogs.label(this, "选择城市(同时决定时区)"));

        final AlertDialog[] holder = new AlertDialog[1];
        for (final String[] city : TimeKeeper.CITIES) {
            Button b = new Button(this);
            b.setText(city[0] + "    " + city[1]);
            b.setTextSize(13);
            b.setAllCaps(false);
            b.setTextColor(C_FG);
            b.setBackgroundColor(0xff21262d);
            b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            b.setPadding(dp(12), 0, dp(12), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
            lp.bottomMargin = dp(4);
            b.setLayoutParams(lp);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    time.setZone(city[0], city[1]);
                    if (clockView != null) clockView.setText(time.nowText());
                    holder[0].dismiss();
                    toast("时区已设为 " + city[0] + " (" + city[1] + ")");
                }
            });
            box.addView(b);
        }
        holder[0] = Dialogs.show(this, "时区设置", box, null, null, "取消");
    }

    // ================================================================ 地址 / 杂项
    private void askHost() {
        final EditText e = mkEdit(null, board.host(), false);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout box = Dialogs.column(this);
        box.addView(Dialogs.label(this, "板子串口日志里的 IP,例如 192.168.5.37"));
        box.addView(e);
        Dialogs.show(this, "板子地址", box, "保存", new Dialogs.OnOk() {
            @Override public void onOk() {
                board.setHost(e.getText().toString());
                getSharedPreferences("board", MODE_PRIVATE)
                        .edit().putString("host", board.host()).apply();
                toast("已保存:" + board.baseUrl());
                showTab(TAB_STATUS);
            }
        }, "取消");
    }

    /** 读系统状态栏高度 */
    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) {
            int h = getResources().getDimensionPixelSize(id);
            if (h > 0) return h;
        }
        return dp(28);   // 兜底
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void toast(String s) {
        if (s == null || s.isEmpty()) return;
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
