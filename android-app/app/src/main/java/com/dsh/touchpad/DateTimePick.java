package com.dsh.touchpad;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 时间字符串的解析与格式化,格式统一为 yyyy-MM-dd HH:mm:ss */
public class DateTimePick {

    private static final String PAT = "yyyy-MM-dd HH:mm:ss";

    public static String fmt(long ms) {
        SimpleDateFormat f = new SimpleDateFormat(PAT, Locale.CHINA);
        return f.format(new Date(ms));
    }

    /** 解析;失败返回 -1 */
    public static long parse(String s) {
        if (s == null) return -1;
        s = s.trim().replace('T', ' ');
        String[] pats = {PAT, "yyyy-MM-dd HH:mm", "yyyy/MM/dd HH:mm:ss",
                "yyyy/MM/dd HH:mm", "MM-dd HH:mm:ss", "HH:mm:ss", "HH:mm"};
        for (String p : pats) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p, Locale.CHINA);
                f.setLenient(false);
                Date d = f.parse(s);
                if (d == null) continue;
                long t = d.getTime();
                // 只给了时间(没有日期)时,补上今天
                if (p.equals("HH:mm") || p.equals("HH:mm:ss")) {
                    java.util.Calendar now = java.util.Calendar.getInstance();
                    java.util.Calendar c = java.util.Calendar.getInstance();
                    c.setTimeInMillis(t);
                    now.set(java.util.Calendar.HOUR_OF_DAY, c.get(java.util.Calendar.HOUR_OF_DAY));
                    now.set(java.util.Calendar.MINUTE, c.get(java.util.Calendar.MINUTE));
                    now.set(java.util.Calendar.SECOND, c.get(java.util.Calendar.SECOND));
                    now.set(java.util.Calendar.MILLISECOND, 0);
                    t = now.getTimeInMillis();
                } else if (p.startsWith("MM-dd")) {
                    java.util.Calendar now = java.util.Calendar.getInstance();
                    java.util.Calendar c = java.util.Calendar.getInstance();
                    c.setTimeInMillis(t);
                    now.set(java.util.Calendar.MONTH, c.get(java.util.Calendar.MONTH));
                    now.set(java.util.Calendar.DAY_OF_MONTH, c.get(java.util.Calendar.DAY_OF_MONTH));
                    now.set(java.util.Calendar.HOUR_OF_DAY, c.get(java.util.Calendar.HOUR_OF_DAY));
                    now.set(java.util.Calendar.MINUTE, c.get(java.util.Calendar.MINUTE));
                    now.set(java.util.Calendar.SECOND, c.get(java.util.Calendar.SECOND));
                    now.set(java.util.Calendar.MILLISECOND, 0);
                    t = now.getTimeInMillis();
                }
                return t;
            } catch (Exception ignored) {}
        }
        return -1;
    }
}
