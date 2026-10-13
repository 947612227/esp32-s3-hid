package com.dsh.touchpad;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 后台常驻服务(前台服务 + 常驻通知)。
 *
 * ══════════════════════════════════════════════════════════════════
 *  为什么必须有它
 *
 *  跑流程时**大麦在前台、我们的 App 在后台**。
 *  Android 10 上后台进程随时会被系统回收,MIUI 尤其激进 ——
 *  实测:切到大麦几秒后 App 进程就没了。
 *
 *  进程一死:
 *    · 页面识别停止工作 → 板子收不到页面 → **waitfor 永远超时**
 *    · 定时任务不会触发
 *
 *  **前台服务 + 常驻通知**是 Android 官方给"必须后台运行"的唯一正路:
 *  系统不会回收带前台服务的进程(除非用户手动强停)。
 * ══════════════════════════════════════════════════════════════════
 */
public class KeepAliveService extends Service {

    private static final String CHANNEL_ID = "touchpad_keepalive";
    private static final int    NOTIF_ID   = 1001;
    private static final String ACTION_STOP = "com.dsh.touchpad.STOP_KEEPALIVE";

    /** 当前是否在跑(给界面判断用) */
    private static volatile boolean running = false;
    private static volatile String  reason  = "";

    public static boolean isRunning() { return running; }
    public static String  currentReason() { return reason; }

    /**
     * 启动(幂等)。reason 会显示在通知里,例如「流程执行中」「页面识别」。
     */
    public static void start(Context ctx, String why) {
        reason = why == null ? "" : why;
        try {
            Intent i = new Intent(ctx, KeepAliveService.class);
            android.util.Log.i("TP_KEEPALIVE", "start() 请求启动,reason=" + reason);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Throwable e) {
            android.util.Log.e("TP_KEEPALIVE", "start() 失败: " + e, e);
        }
    }

    /** 停止。 */
    public static void stop(Context ctx) {
        running = false;
        try {
            ctx.stopService(new Intent(ctx, KeepAliveService.class));
        } catch (Throwable ignored) {}
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // 通知里的「停止」按钮:清掉 service 意图就退到后台,不杀进程
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            running = false;
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        running = true;
        try {
            startForeground(NOTIF_ID, buildNotification());
            android.util.Log.i("TP_KEEPALIVE", "startForeground 成功");
        } catch (Throwable e) {
            android.util.Log.e("TP_KEEPALIVE", "startForeground 失败: " + e, e);
        }
        // 被系统杀掉后尽量自动重启
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 通知

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    "后台运行", NotificationManager.IMPORTANCE_LOW);   // LOW:不响不震
            ch.setDescription("流程执行 / 页面识别期间保持后台运行,避免被系统回收");
            ch.setShowBadge(false);
            ch.enableLights(false);
            ch.enableVibration(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) {}
    }

    private Notification buildNotification() {
        // 点通知回到 App
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CHANNEL_ID);
        else b = new Notification.Builder(this);

        String sub = (reason == null || reason.isEmpty()) ? "保持后台运行" : reason;
        b.setContentTitle("触摸注入板 · " + sub)
         .setContentText("别划掉这条通知,划掉后系统可能杀掉后台,"
                 + "页面识别和定时就会停")
         .setSmallIcon(android.R.drawable.ic_menu_compass)
         .setContentIntent(pi)
         .setOngoing(true)                       // 不可划掉
         .setShowWhen(false);
        return b.build();
    }
}
