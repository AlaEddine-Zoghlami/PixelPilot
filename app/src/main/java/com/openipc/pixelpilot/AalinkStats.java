package com.openipc.pixelpilot;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Polls aalink's own status from the VTX.
 *
 * <p>These values exist ONLY on the air side. Uplink % in particular is aalink's own RSSI
 * measurement, so it cannot be derived on the ground at all — and once msposd stopped burning the
 * status line into the video there was no path for it to reach us. aalink already knows how to
 * export it: with {@code EXTERNAL_OSD=1} it writes key=value pairs to {@code /tmp/aalink_ext.msg},
 * which is symlinked into the VTX webroot and fetched here.
 *
 * <p>HTTP rather than UDP because the VTX has no UDP-capable userspace tool at all — busybox
 * {@code nc} rejects {@code -u}, busybox {@code logger} has no {@code -R}, and {@code /dev/udp} is
 * unsupported by ash. On the dongle path this needs the APFPV IP bridge / TUN to be up
 * (ApfpvVpnService); on phone-Wi-Fi it works unconditionally. Failures are quiet and simply leave
 * the values stale, because the link legitimately comes and goes.
 */
public final class AalinkStats {
    private static final String TAG = "AalinkStats";

    /** Standard VTX address in AP mode. */
    public static final String DEFAULT_HOST = "192.168.0.1";

    private Thread thread;
    private volatile boolean running;
    private final String url;

    // -1 means "not known yet" so callers can render "--" rather than a misleading 0.
    public volatile int up = -1, down = -1, mcs = -1, kbps = -1, bw = -1, ch = -1, txpwr = -1, q = -1;
    // Real streamed fps (venc) + SoC temperature (°C) — appended by the VTX aalink_udp relay.
    public volatile int fps = -1, tempC = -1;
    private volatile long lastOkMs = 0;
    private int fails = 0;

    public AalinkStats(String host) {
        this.url = "http://" + host + "/aalink_ext.msg";
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::run, "AalinkStats");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        thread = null;
    }

    /** True while the data is recent enough to trust; otherwise callers should fall back. */
    public boolean fresh() { return lastOkMs != 0 && (System.currentTimeMillis() - lastOkMs) < 5000; }

    /** Port the VTX aalink_udp relay pushes /tmp/aalink_ext.msg to. */
    public static final int UDP_PORT = 14551;

    // UDP instead of HTTP: on the dongle the TCP uplink to the VTX is dead (the HTTP GET's SYN is
    // never answered), so the old poll always failed and the aalink line stayed blank -- while MSP
    // works because it is PUSHED inbound. The VTX-side aalink_udp binary sends the same key=value
    // payload as a UDP datagram, which reaches this socket the same way MSP does. No uplink needed.
    private void run() {
        Log.i(TAG, "listening for aalink on UDP " + UDP_PORT);
        byte[] buf = new byte[2048];
        java.net.DatagramSocket sock = null;
        while (running) {
            try {
                if (sock == null || sock.isClosed()) {
                    sock = new java.net.DatagramSocket(UDP_PORT);
                    sock.setSoTimeout(1000);   // so `running` is re-checked ~1 Hz
                }
                java.net.DatagramPacket p = new java.net.DatagramPacket(buf, buf.length);
                sock.receive(p);
                if (p.getLength() > 0) {
                    parse(new String(buf, 0, p.getLength(), java.nio.charset.StandardCharsets.US_ASCII));
                    lastOkMs = System.currentTimeMillis();
                }
            } catch (java.net.SocketTimeoutException ignored) {
                // idle — no aalink push this second (relay not running / link down). Keep last values.
            } catch (Exception e) {
                if (!running) break;
                // Port already bound or interface gone; back off and retry rather than dying.
                Log.w(TAG, "aalink UDP error (" + e.getMessage() + ") — retrying in 2s");
                if (sock != null) { sock.close(); sock = null; }
                try { Thread.sleep(2000); } catch (InterruptedException ie) { break; }
            }
        }
        if (sock != null) sock.close();
        Log.i(TAG, "stopped");
    }

    private void parse(String s) {
        for (String line : s.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            int iv;
            try { iv = Integer.parseInt(v); } catch (NumberFormatException e) { continue; }
            switch (k) {
                case "rssi_local": up = iv; break;    // uplink %  (air-side measurement)
                case "rssi_udp":   down = iv; break;  // downlink % (what we feed back to aalink)
                case "mcs":        mcs = iv; break;
                case "target_kbps": kbps = iv; break;
                case "width_mhz":  bw = iv; break;
                case "channel":    ch = iv; break;
                case "txpwr_dbm":  txpwr = iv; break;
                case "q":          q = iv; break;
                case "fps":        fps = iv; break;     // real streamed fps (venc Fps_1s)
                case "temp_c":     tempC = iv; break;   // SoC temperature °C
                default: break;
            }
        }
    }

    /** The status line, formatted like the one msposd used to burn into the video. */
    public String line() {
        if (!fresh()) return "aalink --";
        return "LQ up:" + pct(up) + " dn:" + pct(down)
                + " | MCS:" + (mcs < 0 ? "--" : mcs)
                + " | " + (kbps < 0 ? "--" : kbps) + "kbps"
                + (fps < 0 ? "" : " | " + fps + "fps")
                + " | Ch:" + (ch < 0 ? "--" : ch) + "-" + (bw < 0 ? "--" : bw) + "mhz"
                + " | tx" + (txpwr < 0 ? "--" : txpwr) + "dBm"
                + (tempC < 0 ? "" : " | " + tempC + "°C");
    }

    private static String pct(int v) { return v < 0 ? "--" : (v + "%"); }
}
