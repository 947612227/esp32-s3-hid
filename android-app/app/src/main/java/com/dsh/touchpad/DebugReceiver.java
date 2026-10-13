package com.dsh.touchpad;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONObject;

/**
 * 调试用广播接收器(仅用于开发自测,不参与正常使用)。
 *
 * 为什么需要:界面按钮没法用 adb 点,但网络层必须验证。
 * 有了它就能用一条命令触发一次真实请求,结果打到 logcat:
 *
 *   adb shell am broadcast -a com.dsh.touchpad.DEBUG \
 *       --es op status -n com.dsh.touchpad/.DebugReceiver
 *
 * 支持的 op:status / scripts / logs / control:home / gesture:tap
 */
public class DebugReceiver extends BroadcastReceiver {

    private static final String TAG = "TouchPadDebug";

    @Override
    public void onReceive(Context ctx, Intent it) {
        String op = it.getStringExtra("op");
        if (op == null) op = "status";

        final Board b = new Board();
        String host = it.getStringExtra("host");
        if (host != null) b.setHost(host);
        else b.setHost(ctx.getSharedPreferences("board", Context.MODE_PRIVATE)
                .getString("host", ""));

        Log.i(TAG, "=== op=" + op + " host=" + b.baseUrl() + " ===");

        // 可选:顺便切到某个页签(验证界面不崩)
        String tabName = it.getStringExtra("tab");
        if (tabName != null && MainActivity.CURRENT != null) {
            MainActivity.CURRENT.showTabByName(tabName);
            Log.i(TAG, "SWITCHED TAB: " + tabName);
        }

        Board.Cb cb = new Board.Cb() {
            @Override public void done(boolean ok, String body) {
                Log.i(TAG, "RESULT ok=" + ok + " body=" + body);
            }
        };

        if (op.startsWith("control:")) {
            b.control(op.substring(8), cb);
        } else if (op.startsWith("gesture:")) {
            String g = op.substring(8);
            if ("tap".equals(g)) b.tap(0.5f, 0.5f, cb);
            else if ("home".equals(g)) b.control("home", cb);
            else b.gesture(g, null, cb);
        } else if ("scripts".equals(op)) {
            b.scripts(cb);
        } else if ("logs".equals(op)) {
            b.logs(cb);
        } else if ("sysinfo".equals(op)) {
            // 打一份简明的自检信息
            b.status(new Board.Cb() {
                @Override public void done(boolean ok, String body) {
                    if (!ok) { Log.i(TAG, "SELFCHECK FAIL: " + body); return; }
                    try {
                        JSONObject o = new JSONObject(body);
                        Log.i(TAG, "SELFCHECK OK: mode=" + o.optString("mode")
                                + " ip=" + o.optString("ip")
                                + " hid=" + o.optBoolean("hid")
                                + " scripts=" + o.optInt("scriptCount")
                                + " heap=" + o.optInt("heap"));
                    } catch (Exception e) {
                        Log.i(TAG, "SELFCHECK PARSE FAIL: " + e);
                    }
                }
            });
        } else {
            b.status(cb);
        }
    }
}
