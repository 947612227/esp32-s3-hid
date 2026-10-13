package com.dsh.touchpad;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 指令库(严格按 script_engine.cpp 里的真实解析逻辑整理,不是凭记忆写的)。
 *
 * 每条指令给出:助记符 / 中文名 / 参数说明 / 一句话作用 / 示例 / 参数定义。
 * 参数定义用于生成"可视化参数编辑"界面(滑块 + 实时预览)。
 */
public class CmdLib {

    /** 参数类型 */
    public static final int KIND_COORD = 0;   // 归一化坐标 0~1
    public static final int KIND_MS    = 1;   // 毫秒
    public static final int KIND_NUM   = 2;   // 普通整数
    public static final int KIND_GAP   = 3;   // 归一化间距 0~1

    public static class Param {
        public final String name;
        public final int kind;
        public final float min, max, def;
        public final String unit;

        public Param(String name, int kind, float min, float max, float def, String unit) {
            this.name = name; this.kind = kind;
            this.min = min; this.max = max; this.def = def; this.unit = unit;
        }
        /** 是否用滑块(坐标/时长/间距);纯文本参数不用 */
        public boolean slider() { return kind != KIND_NUM || max - min > 0; }
    }

    public static class Cmd {
        public final String letter;      // 助记符
        public final String group;       // 分组
        public final String name;        // 中文名
        public final String usage;       // 参数形式
        public final String what;        // 一句话作用
        public final String detail;      // 详细说明(参数逐项)
        public final String example;     // 可直接插入的示例(只作说明展示)
        public final String template;    // 插入编辑框用的"空指令模板"
        public final Param[] params;

        public Cmd(String letter, String group, String name, String usage,
                   String what, String detail, String example, Param[] params) {
            this(letter, group, name, usage, what, detail, example, params,
                 letter + " ");          // 默认模板 = 指令 + 一个空格
        }

        public Cmd(String letter, String group, String name, String usage,
                   String what, String detail, String example, Param[] params,
                   String template) {
            this.letter = letter; this.group = group; this.name = name;
            this.usage = usage; this.what = what; this.detail = detail;
            this.example = example; this.template = template; this.params = params;
        }
    }

    public static final String[] GROUPS = {"点击 / 长按", "滑动 / 手势", "按键", "流程控制"};

    private static final List<Cmd> ALL = new ArrayList<Cmd>();

    /** 坐标参数(默认值按常用的屏幕中部/底部给) */
    private static Param px(float def) { return new Param("X 坐标", KIND_COORD, 0f, 1f, def, ""); }
    private static Param py(float def) { return new Param("Y 坐标", KIND_COORD, 0f, 1f, def, ""); }
    /** 时长参数:名称不带单位,单位由 unit 字段承载(界面会拼到标签后面) */
    private static Param ms(int def)   { return new Param("持续", KIND_MS, 0f, 3000f, def, "ms"); }

    static {
        ALL.add(new Cmd("T", GROUPS[0], "点击", "T x y [ms]",
                "在指定位置点一下。",
                "x, y = 屏幕位置,写 0.5 表示 50% 处(也可以用 50% 或 16384)\n"
                + "ms = 按下停留多久,默认 60。太短可能被系统当成误触。",
                "T 0.5 0.5 60",
                new Param[]{px(0.5f), py(0.5f), ms(60)}));

        ALL.add(new Cmd("D", GROUPS[0], "双击", "D x y [间隔 ms]",
                "在指定位置快速点两下。",
                "间隔 = 两次点击之间等多久,默认 80 ms。太大就不算双击了。",
                "D 0.5 0.5 80",
                new Param[]{px(0.5f), py(0.5f),
                        new Param("间隔", KIND_MS, 40f, 500f, 80f, "ms")}));

        ALL.add(new Cmd("H", GROUPS[0], "长按", "H x y [ms]",
                "在指定位置按住不放。",
                "ms = 按住多久,默认 900。长按菜单通常要 500 以上。",
                "H 0.5 0.5 900",
                new Param[]{px(0.5f), py(0.5f),
                        new Param("按住", KIND_MS, 300f, 5000f, 900f, "ms")}));

        ALL.add(new Cmd("S", GROUPS[1], "直线滑动", "S x1 y1 x2 y2 [ms]",
                "从一个点直线滑到另一个点。",
                "x1,y1 = 起点;x2,y2 = 终点;ms = 滑多久,默认 300。\n"
                + "滑动时间太短容易被系统当成快速甩动。",
                "S 0.5 0.75 0.5 0.25 300",
                new Param[]{px(0.5f), py(0.75f), px(0.5f), py(0.25f), ms(300)}));

        ALL.add(new Cmd("C", GROUPS[1], "曲线滑动", "C x1 y1 控制点x 控制点y x2 y2 [ms]",
                "沿着一条弧线滑过去,比直线更接近人手。",
                "控制点决定弧线往哪边弯。控制点在中点旁边就会画出弧。",
                "C 0.2 0.8 0.7 0.5 0.2 0.2 320",
                new Param[]{px(0.2f), py(0.8f), px(0.7f), py(0.5f), px(0.2f), py(0.2f), ms(320)}));

        ALL.add(new Cmd("P", GROUPS[1], "双指滑动", "P x1 y1 x2 y2 [两指间距] [ms]",
                "两根手指同时滑动(滚动列表/页面)。",
                "两指在水平方向相距「间距」,一起从 y1 滑到 y2。间距默认 0.2。",
                "P 0.5 0.7 0.5 0.3 0.2 300",
                new Param[]{px(0.5f), py(0.7f), px(0.5f), py(0.3f),
                        new Param("两指间距", KIND_GAP, 0.05f, 0.4f, 0.2f, ""), ms(300)}));

        ALL.add(new Cmd("Z", GROUPS[1], "捏合缩放", "Z 中心x 中心y 起始间距 结束间距 [ms]",
                "两根手指靠拢或张开,用来放大/缩小。",
                "起始间距 > 结束间距 = 缩小;反过来 = 放大。间距范围 0~1。",
                "Z 0.5 0.5 0.6 0.2 300",
                new Param[]{px(0.5f), py(0.5f),
                        new Param("起始间距", KIND_GAP, 0.05f, 0.9f, 0.6f, ""),
                        new Param("结束间距", KIND_GAP, 0.05f, 0.9f, 0.2f, ""), ms(300)}));

        ALL.add(new Cmd("K", GROUPS[2], "键盘按键", "K 键码",
                "按一下键盘上的某个键(发送按键码)。",
                "键码用 USB HID 的 usage 值。例如:\n"
                + "  40 = 回车   41 = ESC   42 = 退格   43 = Tab\n"
                + "  74 = Home  79 = 右方向键  80 = 左方向键\n"
                + "注意:板子的键盘接口默认是关闭的(会触发手机的外接键盘弹窗)。",
                "K 40",
                new Param[]{new Param("键码(usage)", KIND_NUM, 0f, 255f, 40f, "")}));

        ALL.add(new Cmd("V", GROUPS[2], "多媒体键", "V 键码",
                "发送音量、播放等多媒体按键。",
                "常用:233 = 音量+   234 = 音量-   226 = 静音\n"
                + "     205 = 播放/暂停   181 = 下一曲   182 = 上一曲\n"
                + "注意:同键盘接口一样默认关闭。",
                "V 233",
                new Param[]{new Param("键码(usage)", KIND_NUM, 0f, 999f, 233f, "")}));

        ALL.add(new Cmd("wait", GROUPS[3], "等待", "wait ms",
                "停一会儿再执行下一步。",
                "界面切换、页面加载需要时间,步骤之间适当加等待更稳。",
                "wait 1500",
                new Param[]{new Param("等待", KIND_MS, 0f, 10000f, 1500f, "ms")},
                "wait 1500"));      // 等待给个 1500 的默认值,其余参数留空手填

        ALL.add(new Cmd("repeat", GROUPS[3], "重复", "repeat 次数",
                "把「这一行自己」重复执行 N 次。",
                "写在要重复的那一行上。例如「repeat 3」会让这一行跑 3 遍。",
                "repeat 3",
                new Param[]{new Param("次数", KIND_NUM, 1f, 32f, 3f, "")}));

        ALL.add(new Cmd("goto", GROUPS[3], "跳转", "goto 标号",
                "跳到某个标号继续执行。",
                "标号用「#名字」单独写一行。配合 goto 可以做循环。",
                "goto 循环",
                new Param[0]));

        // ---- 页面相关(需要开启「页面识别」)----
        ALL.add(new Cmd("waitfor", GROUPS[3], "等待页面", "waitfor 关键字 [超时ms]",
                "等到页面上出现某个关键字再继续。",
                "比 wait 3000 可靠得多:页面加载快就早走,慢就多等。\n"
                + "关键字写 Activity 短名(如 NcovSkuActivity)或页面上的文字都行,\n"
                + "包含匹配,忽略大小写。加 @ 前缀则只匹配 Activity。\n"
                + "超时(默认 15000ms)到了就继续往下走,不会卡死。\n"
                + "⚠️ 需要开启「页面识别」,并且只在流程运行期间有效。",
                "waitfor 预约想看票档 10000",
                new Param[0], "waitfor "));

        ALL.add(new Cmd("ifpage", GROUPS[3], "如果是页面", "ifpage 关键字",
                "当前页面匹配就执行接下来的步骤,否则跳到 else / endif。",
                "配合 else / endif 使用,可以按页面走不同分支。\n"
                + "关键字规则同 waitfor。可嵌套(最多 16 层)。\n"
                + "⚠️ 需要开启「页面识别」。",
                "ifpage NcovSkuActivity",
                new Param[0], "ifpage "));

        ALL.add(new Cmd("else", GROUPS[3], "否则", "else",
                "上面那支不成立时走这里。",
                "和 ifpage 配对。可以省略 —— 不写就是「没有 else」。",
                "else",
                new Param[0], "else"));

        ALL.add(new Cmd("endif", GROUPS[3], "结束判断", "endif",
                "结束 ifpage 分支。",
                "必须和 ifpage 配对。",
                "endif",
                new Param[0], "endif"));
    }

    public static List<Cmd> all() { return ALL; }

    /**
     * 插入编辑框用的文本。
     *
     * 只给"指令 + 参数占位",**不预填具体数值** ——
     * 因为坐标这类值用户会用拾取器或自己敲,预填了反而要删。
     * 例外:wait 带一个默认时长 1500。
     */
    public static String template(Cmd c) { return c.template; }

    public static List<Cmd> byGroup(String g) {
        List<Cmd> out = new ArrayList<Cmd>();
        for (Cmd c : ALL) if (c.group.equals(g)) out.add(c);
        return out;
    }

    public static Cmd find(String letter) {
        for (Cmd c : ALL) if (c.letter.equalsIgnoreCase(letter)) return c;
        return null;
    }

    /** 用一串参数值拼出可直接写入编辑框的一行 */
    /** 生成一行(带备注)。note 为 null/空 时不加 */
    public static String build(Cmd c, float[] vals, String note) {
        String base = build(c, vals);
        if (note == null || note.trim().isEmpty()) return base;
        return base + " " + note.trim();
    }

    public static String build(Cmd c, float[] vals) {
        StringBuilder sb = new StringBuilder(c.letter);
        for (int i = 0; i < c.params.length; i++) {
            Param p = c.params[i];
            sb.append(' ');
            if (p.kind == KIND_COORD || p.kind == KIND_GAP) {
                sb.append(String.format(java.util.Locale.US, "%.3f", vals[i]));
            } else {
                sb.append((int) vals[i]);
            }
        }
        return sb.toString();
    }

    // ================================================================ 反向解析

    /** 解析结果:一条指令 + 它的参数值(无法解析时为 null) */
    public static class Parsed {
        public final Cmd cmd;
        public final float[] vals;
        public final String label;   // 仅 goto 用
        public final String note;    // 备注(可空)
        public Parsed(Cmd c, float[] v, String lb) { this(c, v, lb, null); }
        public Parsed(Cmd c, float[] v, String lb, String nt) {
            cmd = c; vals = v; label = lb; note = nt;
        }
    }

    /**
     * 把一行文本解析回「指令 + 参数值」。
     *
     * 反向解析是可视化编辑的前提:只有能把 T 0.167 0.939 60 拆回
     * (cmd=T, x=0.167, y=0.939, ms=60),才能用滑块再编辑它。
     *
     * 解析失败返回 null(界面会显示成"无法识别的行",但仍保留原文本)。
     */
    public static Parsed parse(String line) {
        if (line == null) return null;
        String t = line.trim();
        if (t.isEmpty() || t.startsWith("#")) return null;

        String[] tok = t.split("\\s+");
        if (tok.length == 0) return null;

        Cmd c = find(tok[0]);
        if (c == null) return null;

        // goto:参数是标号(字符串),之后是备注
        if (c.params.length == 0) {
            String lb = tok.length >= 2 ? tok[1] : "";
            String nt = tok.length >= 3 ? join(tok, 2) : null;
            return new Parsed(c, new float[0], lb, nt);
        }

        float[] v = new float[c.params.length];
        int used = 1;
        for (int i = 0; i < c.params.length; i++) {
            if (i + 1 < tok.length && isNumeric(tok[i + 1])) {
                v[i] = parseOne(tok[i + 1], c.params[i]);
                used = i + 2;
            } else {
                v[i] = c.params[i].def;      // 缺参数用默认值
                break;                       // 后面都是备注了
            }
        }
        // 参数之后剩下的都是备注
        String note = used < tok.length ? join(tok, used) : null;
        return new Parsed(c, v, null, note);
    }

    /** token 是不是纯数值(可带 % 后缀)—— 用来区分参数和备注 */
    private static boolean isNumeric(String t) {
        if (t == null || t.isEmpty()) return false;
        String x = t.trim();
        if (x.endsWith("%")) x = x.substring(0, x.length() - 1);
        try { Float.parseFloat(x); return true; } catch (Exception e) { return false; }
    }

    private static String join(String[] tok, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < tok.length; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(tok[i]);
        }
        return sb.toString();
    }

    /** 单个参数值:支持 0.5 / 50% / 16384 */
    private static float parseOne(String s, Param p) {
        try {
            String x = s.trim();
            if (p.kind == KIND_COORD || p.kind == KIND_GAP) {
                if (x.endsWith("%")) {
                    return Float.parseFloat(x.substring(0, x.length() - 1)) / 100f;
                }
                float f = Float.parseFloat(x);
                if (f > 1f) f = f / 32767f;    // 原始值
                return Math.max(0f, Math.min(1f, f));
            }
            return Float.parseFloat(x);
        } catch (Exception e) {
            return p.def;
        }
    }

    /**
     * 人类可读的参数摘要,给可视化卡片显示用。
     * 例:点击 → "位置 (16.7%, 93.9%) · 停留 60ms"
     */
    public static String summary(Cmd c, float[] vals, String label, String note) {
        if (note != null && !note.trim().isEmpty()) return note.trim();
        return summary(c, vals, label);
    }

    public static String summary(Cmd c, float[] vals, String label) {
        if (c.params.length == 0) {
            return "标号:" + (label == null ? "" : label);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < c.params.length; i++) {
            Param p = c.params[i];
            if (i > 0) sb.append(" · ");
            if (p.kind == KIND_COORD) {
                sb.append(String.format(Locale.US, "%.1f%%", vals[i] * 100));
            } else if (p.kind == KIND_GAP) {
                sb.append(String.format(Locale.US, "%.2f", vals[i]));
            } else {
                sb.append((int) vals[i]);
                if (!p.unit.isEmpty()) sb.append(p.unit);
            }
        }
        return sb.toString();
    }

    /** 参数名(给摘要加前缀用) */
    public static String paramNames(Cmd c) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < c.params.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(c.params[i].name);
        }
        return sb.toString();
    }

    /** 参数的默认值数组 */
    public static float[] defaults(Cmd c) {
        float[] v = new float[c.params.length];
        for (int i = 0; i < v.length; i++) v[i] = c.params[i].def;
        return v;
    }
}
