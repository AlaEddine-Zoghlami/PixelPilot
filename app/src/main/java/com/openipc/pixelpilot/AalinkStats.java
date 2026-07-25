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

    private void run() {
        Log.i(TAG, "polling " + url);
        while (running) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                // Short timeouts: a dead link must not stall this thread for seconds on end.
                c.setConnectTimeout(1500);
                c.setReadTimeout(1500);
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                    String ln;
                    while ((ln = r.readLine()) != null) sb.append(ln).append('\n');
                }
                if (sb.length() > 0) { parse(sb.toString()); lastOkMs = System.currentTimeMillis(); }
                fails = 0;
            } catch (Exception ignored) {
                // VTX unreachable / bridge down — keep the last values and back off.
                if (fails < 60) fails++;
            } finally {
                if (c != null) c.disconnect();
            }
            // BACK OFF ON FAILURE. On the dongle path this request traverses the APFPV IP bridge /
            // TUN, i.e. the same link that carries video. Retrying every second while it is down
            // pushed steady connection attempts into that TX path and loaded the CPU the RX worker
            // needs, which showed up as the app becoming unresponsive on dongle while phone-Wi-Fi
            // (where the request never leaves the OS) was fine. 1s when healthy, up to 15s when not.
            // Cap at 60 s, not 15 s. Measured on-device: the TUN *uplink* to the VTX is dead
            // (ping 192.168.0.1 over tun0 = 100% loss) even though the route is correct and the
            // DOWNLINK works -- MSP arrives and arm state decodes. So every attempt is a TCP SYN
            // that can never be answered, pushed into the same dongle TX path the video uses. Until
            // the uplink is fixed this must cost as close to nothing as possible.
            long delay = (fails == 0) ? 1000L : Math.min(60000L, 1000L * (1L << Math.min(fails, 6)));
            try { Thread.sleep(delay); } catch (InterruptedException e) { break; }
        }
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
                + " | ch" + (ch < 0 ? "--" : ch) + " " + (bw < 0 ? "--" : bw) + "MHz"
                + " | tx" + (txpwr < 0 ? "--" : txpwr) + "dBm";
    }

    private static String pct(int v) { return v < 0 ? "--" : (v + "%"); }
}
