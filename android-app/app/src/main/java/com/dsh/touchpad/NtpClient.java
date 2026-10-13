package com.dsh.touchpad;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * 极简 SNTP 客户端(RFC 4330)。
 *
 * 为什么不直接用系统时间:系统时间虽然也会自动同步,但:
 *   ① 用户要求"点击时间可以立即同步",需要一个主动触发的动作;
 *   ② 需要显示同步状态(上次同步时间、与网络的偏差)。
 *
 * 注意:普通 App **无权修改系统时钟**(需要 root)。所以这里的做法是
 * 记下"网络时间 - 本机时间"的偏差 offset,显示时用
 * `System.currentTimeMillis() + offset`,既能显示准确的网络时间,
 * 也能在两次同步之间平滑走时。
 */
public class NtpClient {

    /** NTP 纪元(1900-01-01)与 Unix 纪元(1970-01-01)相差的秒数 */
    private static final long NTP_UNIX_DELTA = 2208988800L;

    /** 依次尝试的服务器。国内优先,失败再退到国际池 */
    public static final String[] SERVERS = {
            "ntp.aliyun.com",
            "cn.pool.ntp.org",
            "time.windows.com",
            "pool.ntp.org",
    };

    public static class Result {
        public long epochMs;      // 网络时间(UTC 毫秒)
        public long roundTripMs;  // 往返延迟
        public String server;     // 成功的服务器
    }

    /** 同步一次:逐个尝试服务器,全部失败抛异常 */
    public static Result sync(int timeoutMs) throws Exception {
        Exception last = null;
        for (String host : SERVERS) {
            try {
                return query(host, timeoutMs);
            } catch (Exception e) {
                last = e;
            }
        }
        throw (last != null ? last : new Exception("没有可用的 NTP 服务器"));
    }

    /** 向单个服务器查询时间 */
    public static Result query(String host, int timeoutMs) throws Exception {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(timeoutMs);

            byte[] buf = new byte[48];
            buf[0] = 0x1B;              // LI=0, VN=3, Mode=3(客户端)

            InetAddress addr = InetAddress.getByName(host);
            DatagramPacket req = new DatagramPacket(buf, buf.length, addr, 123);

            long t0 = System.currentTimeMillis();
            socket.send(req);

            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            socket.receive(resp);
            long t3 = System.currentTimeMillis();

            if (resp.getLength() < 48) throw new Exception("响应太短");

            // "服务器发送时间戳"在第 40~47 字节(秒 + 小数部分)
            long seconds = readUint32(buf, 40);
            long fraction = readUint32(buf, 44);

            long ms = (seconds - NTP_UNIX_DELTA) * 1000L
                    + (fraction * 1000L) / 0x100000000L;

            Result r = new Result();
            // 粗略补偿一半的往返延迟
            r.epochMs = ms + (t3 - t0) / 2;
            r.roundTripMs = t3 - t0;
            r.server = host;
            return r;
        } finally {
            if (socket != null) socket.close();
        }
    }

    private static long readUint32(byte[] b, int off) {
        return ((b[off] & 0xFFL) << 24)
                | ((b[off + 1] & 0xFFL) << 16)
                | ((b[off + 2] & 0xFFL) << 8)
                | (b[off + 3] & 0xFFL);
    }
}
