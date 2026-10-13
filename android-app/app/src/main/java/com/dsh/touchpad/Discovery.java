package com.dsh.touchpad;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * 板子自动发现(UDP 广播)。
 *
 * ══════════════════════════════════════════════════════════════
 *  为什么需要:板子走 DHCP,换网络 / 重启后 IP 会变,
 *  每次都让用户手输 IP 太麻烦。
 *
 *  做法:手机广播一句 "TOUCHPAD_DISCOVER",
 *        板子回一句 "TOUCHPAD_HERE <ip> <port>",
 *        手机就知道板子在哪了。
 *
 *  为什么用 UDP 广播而不是 mDNS:
 *    很多路由器默认**不转发组播**,但广播基本都能通。
 *    而且广播不需要任何额外权限(有 INTERNET 就够)。
 * ══════════════════════════════════════════════════════════════
 */
public class Discovery {

    public static final int PORT = 45678;
    private static final String PROBE = "TOUCHPAD_DISCOVER";
    private static final String REPLY = "TOUCHPAD_HERE";

    public interface Cb {
        /** 找到就回调 IP;没找到回调 null */
        void done(String host);
    }

    /**
     * 广播探测。
     * @param timeoutMs 最多等多久
     */
    public static void find(final int timeoutMs, final Cb cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                DatagramSocket sock = null;
                String found = null;
                try {
                    sock = new DatagramSocket();
                    sock.setBroadcast(true);
                    sock.setSoTimeout(400);

                    byte[] probe = PROBE.getBytes("UTF-8");

                    // 同时发到"全网广播"和"本网段广播",提高命中率
                    for (InetAddress dst : targets()) {
                        try {
                            sock.send(new DatagramPacket(probe, probe.length, dst, PORT));
                        } catch (Throwable ignored) {}
                    }

                    long end = System.currentTimeMillis() + timeoutMs;
                    byte[] buf = new byte[256];
                    while (System.currentTimeMillis() < end && found == null) {
                        try {
                            DatagramPacket rp = new DatagramPacket(buf, buf.length);
                            sock.receive(rp);
                            String s = new String(rp.getData(), 0, rp.getLength(), "UTF-8").trim();
                            if (s.startsWith(REPLY)) {
                                String[] p = s.split("\\s+");
                                if (p.length >= 2 && p[1].matches("[0-9.]+")) found = p[1];
                            }
                        } catch (java.net.SocketTimeoutException te) {
                            // 超时就再广播一次(可能刚上电还没起来)
                            for (InetAddress dst : targets()) {
                                try {
                                    sock.send(new DatagramPacket(probe, probe.length, dst, PORT));
                                } catch (Throwable ignored) {}
                            }
                        } catch (Throwable ignored) {
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (sock != null) sock.close();
                }
                if (cb != null) cb.done(found);
            }
        }, "board-discovery").start();
    }

    /** 候选广播地址:255.255.255.255 + 本机所在网段的广播地址 */
    private static List<InetAddress> targets() {
        List<InetAddress> out = new ArrayList<InetAddress>();
        try { out.add(InetAddress.getByName("255.255.255.255")); } catch (Throwable ignored) {}
        try {
            java.util.Enumeration<java.net.NetworkInterface> nis =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b != null && !out.contains(b)) out.add(b);
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }
}
