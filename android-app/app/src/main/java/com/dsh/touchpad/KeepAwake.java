package com.dsh.touchpad;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/**
 * 执行期间不让手机息屏。
 *
 * ══════════════════════════════════════════════════════════════════
 *  为什么需要
 *
 *  跑流程时目标 App 在前台、我们的 App 在后台,所以:
 *    · Activity 上加 FLAG_KEEP_SCREEN_ON **不管用**(那是给前台窗口用的)
 *    · 只能从**系统层**下手
 *
 *  手机设置里息屏时间只给到"分钟"级别,没有"永不"选项。
 *  但 `Settings.System.SCREEN_OFF_TIMEOUT` 这个键**接受超大值** ——
 *  写 2147483647(约 24.8 天)就等于"永不"。
 *
 *  所以我们:执行开始 → 改成超大值;执行结束 → 改回去。
 *  这样既不会永久改用户的设置,又能保证执行期间不息屏。
 * ══════════════════════════════════════════════════════════════════
 */
public class KeepAwake {

    /** 约 24.8 天,等于"永不" */
    private static final int NEVER = 2147483647;
    private static final String PREF = "board";
    private static final String K_ON = "keepAwake";         // 开关(默认开)
    private static final String K_SAVED = "savedScreenOff"; // 改之前的值

    /** 有没有「修改系统设置」权限(改息屏时间需要它) */
    public static boolean hasPermission(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= 23) return Settings.System.canWrite(ctx);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 跳系统页面申请权限 */
    public static void requestPermission(Activity act) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + act.getPackageName()));
            act.startActivity(i);
        } catch (Throwable e) {
            try {
                act.startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS));
            } catch (Throwable ignored) {}
        }
    }

    /** 开关(默认开) */
    public static boolean enabled(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getBoolean(K_ON, true);
    }

    public static void setEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putBoolean(K_ON, on).apply();
    }

    // ---------------------------------------------------------------- 保持 / 恢复

    private static boolean holding = false;

    /**
     * 开始执行时调:把息屏时间改成"永不"。
     * 幂等 —— 重复调不会有副作用。
     */
    public static void hold(Context ctx) {
        if (holding) return;
        if (!enabled(ctx) || !hasPermission(ctx)) return;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            int cur = Settings.System.getInt(ctx.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT, 600000);
            // 只在还没改过的时候记原值(避免覆盖成 NEVER)
            if (cur != NEVER) {
                sp.edit().putInt(K_SAVED, cur).apply();
            }
            Settings.System.putInt(ctx.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT, NEVER);
            holding = true;
        } catch (Throwable ignored) {}
    }

    /**
     * 执行结束后调:把息屏时间改回原来的值。
     */
    public static void release(Context ctx) {
        if (!holding) return;
        holding = false;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            int saved = sp.getInt(K_SAVED, 600000);
            if (saved <= 0 || saved == NEVER) saved = 600000;
            Settings.System.putInt(ctx.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT, saved);
        } catch (Throwable ignored) {}
    }

    /** 进程被杀时兜底:下次启动如果发现还是 NEVER,就恢复回去 */
    public static void restoreIfStuck(Context ctx) {
        try {
            int cur = Settings.System.getInt(ctx.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT, 600000);
            if (cur == NEVER) {
                SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
                int saved = sp.getInt(K_SAVED, 600000);
                if (saved <= 0 || saved == NEVER) saved = 600000;
                Settings.System.putInt(ctx.getContentResolver(),
                        Settings.System.SCREEN_OFF_TIMEOUT, saved);
            }
        } catch (Throwable ignored) {}
    }

    /** 现在系统里的息屏时间(毫秒),读不到返回 -1 */
    public static int currentTimeout(Context ctx) {
        try {
            return Settings.System.getInt(ctx.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT, -1);
        } catch (Throwable e) {
            return -1;
        }
    }

    public static String describe(int ms) {
        if (ms < 0) return "读不到";
        if (ms == NEVER) return "永不";
        if (ms < 60000) return (ms / 1000) + " 秒";
        return (ms / 60000) + " 分钟";
    }
}
