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
 * 支持的 op:status / scripts / logs / logs:clear(读→清→读)/ schedstop:null / control:home / gesture:tap
 *           schedtest(--es name 流程名 --el ms 延时)/ schedclear(--es name 流程名):定时精度自测
 */
public class DebugReceiver extends BroadcastReceiver {

    private static final String TAG = "TouchPadDebug";

    @Override
    public void onReceive(Context ctx, Intent it) {
        String op = it.getStringExtra("op");
        if (op == null) op = "status";

        // 默认用全 App 共用的实例(地址、传输方式和界面一致);
        // 广播带 host 时临时指定地址,不改共用实例
        String host = it.getStringExtra("host");
        final Board b = host != null ? Board.forHost(ctx, host) : Board.get(ctx);

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
        } else if ("logs:clear".equals(op)) {
            // 读日志 → 清空 → 立刻再读(整串在 1.5 秒缓存期内):第二次读必须真走网络、读到清空后的内容
            final Board.Cb last = cb;
            b.logs(new Board.Cb() {
                @Override public void done(boolean ok, String body) {
                    b.clearLogs(new Board.Cb() {
                        @Override public void done(boolean ok2, String body2) {
                            b.logs(last);
                        }
                    });
                }
            });
        } else if ("schedtest".equals(op) || "schedclear".equals(op)) {
            // 定时精度自测:--es name 流程名 [--el ms 延时毫秒]
            String name = it.getStringExtra("name");
            if (name == null || MainActivity.CURRENT == null) {
                Log.i(TAG, "RESULT ok=false body=缺 name 或主界面没起来");
            } else if ("schedtest".equals(op)) {
                MainActivity.CURRENT.debugSchedTest(name, it.getLongExtra("ms", 8000));
            } else {
                MainActivity.CURRENT.debugSchedClear(name);
            }
        } else if ("schedstop:null".equals(op)) {
            // 不带回调发请求(界面里"停止脚本"就是这么调的),验证请求照样发出去
            b.scheduleStop(null);
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
