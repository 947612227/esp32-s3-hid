package com.dsh.touchpad;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;

/**
 * 「日志」页签:只管页面上的日志框和增量追加。
 *
 * 拉数据仍在 MainActivity.pullLogs —— 它同时要喂悬浮窗,
 * 而悬浮窗在用户没进过日志页时也要有日志,所以不能挪到这里。
 */
final class LogsTab {
    private final MainActivity a;
    private TextView logView;           // 日志框;没进过日志页时是 null
    private int seenLogs = 0;           // 已经显示到板子日志的第几行

    LogsTab(MainActivity a) { this.a = a; }

    /** 日志框建过没有(没建过且悬浮窗也不在 ⇒ pullLogs 可以不发请求) */
    boolean isBuilt() { return logView != null; }

    /** 把整页控件加到 content 里,并立刻拉一次 */
    void build(LinearLayout content) {
        LinearLayout c = a.card("运行日志(板子最近 80 行)");
        c.addView(a.btnRow(null, new String[][]{
                {"刷新", "r"}, {"清空", "c"}
        }, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if ("c".equals(v.getTag())) {
                    a.board.clearLogs(new Board.Cb() {
                        @Override public void done(boolean ok, String body) {
                            seenLogs = 0;
                            logView.setText("(暂无日志)");
                        }
                    });
                } else {
                    seenLogs = 0;
                    logView.setText("");
                    a.pullLogs();
                }
            }
        }));

        logView = new TextView(a);
        logView.setText("(暂无日志)");
        logView.setTextColor(0xffc9d1d9);      // 提亮一点,11sp 太小太暗看着累
        logView.setTextSize(12);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setBackgroundColor(0xff010409);
        logView.setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8));
        // ⚠️ 高度必须是 WRAP_CONTENT。
        //    以前写死 dp(480) —— 内容超过就被**裁掉**,
        //    长一点的流程日志(一次 30+ 行)后面根本看不到。
        //    改成跟内容走,由外层页面的 ScrollView 负责滚动。
        logView.setTextIsSelectable(true);      // 能选中复制某几行,方便贴给别人看
        logView.setLineSpacing(a.dp(2), 1.0f);
        c.addView(logView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(c);

        seenLogs = 0;
        a.pullLogs();
    }

    /** 拉日志失败 */
    void showError(String body) {
        if (logView != null) logView.setText("读取失败:" + body);
    }

    /** 收到板子日志全量(最近 80 行):只追加没显示过的;板子日志变短(被清空)就从头显示 */
    void onLines(JSONArray lines) {
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
    }
}
