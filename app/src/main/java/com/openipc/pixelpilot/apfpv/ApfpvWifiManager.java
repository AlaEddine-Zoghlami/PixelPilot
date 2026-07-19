package com.openipc.pixelpilot.apfpv;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Collections;
import java.util.List;

/**
 * ApfpvWifiManager — the THIRD link mode: APFPV over the PHONE'S OWN Wi-Fi chip,
 * with no RTL8812AU dongle and no devourer driver.
 *
 * WHY THIS IS A SEPARATE MODE (vs the dongle APFPV path in ApfpvLinkManager):
 *   - There is no USB device, no monitor mode, no native 802.11 de-encapsulation.
 *   - The phone associates to greg's AP ("OpenIPC") as a normal managed station
 *     and gets a DHCP lease on 192.168.0.0/24 (phone ~= 192.168.0.10, VTX .0.1).
 *   - greg's majestic streams plain UDP/RTP to 192.168.0.10:5600, which the OS
 *     hands to the EXISTING VideoPlayer UDP socket (INADDR_ANY:5600) — so the
 *     video path needs no new native code at all.
 *   - Air config is the SAME as dongle APFPV: SSH to 192.168.0.1 (AirSshClient).
 *   - RSSI for OSD comes from WifiManager.getConnectionInfo().getRssi(), not
 *     dongle radiotap.
 *   - The LQ feedback that greg's aalink expects (it PINGs 192.168.0.10 and
 *     listens on UDP 12345 for an RSSI byte) is sent from here using the
 *     WifiManager RSSI.
 *
 * THE CRITICAL ANDROID PROBLEM THIS SOLVES:
 *   greg's AP has NO internet uplink. By default Android marks such a Wi-Fi
 *   network "no internet" and routes app sockets (the SSH/TCP connection AND
 *   the video UDP socket) to cellular/another network instead — so without
 *   binding, video never arrives and SSH connects to the wrong place (or fails).
 *   We request the specific Wi-Fi network and bindProcessToNetwork() so ALL of
 *   this process's sockets traverse the Wi-Fi link to the VTX.
 *
 * NOTE: This is new ground — upstream PixelPilot is dongle-only and has no
 * network-binding code to mirror. Untested on hardware in this form.
 */
public class ApfpvWifiManager {

    public interface Listener {
        void onState(String state);
        void onRssi(int dbm);
        void onError(String detail);
    }

    private final Context ctx;
    private final ConnectivityManager cm;
    private final WifiManager wifi;
    private Listener listener;

    private String ssid = "OpenIPC";
    private String pass = "12345678";

    // VTX expects LQ feedback here (greg10.2 aalink.conf: PING_DEST=192.168.0.10,
    // UDP_PORT=12345). We are 192.168.0.10; the VTX (192.168.0.1) reads our RSSI.
    private static final String VTX_IP   = "192.168.0.1";
    private static final int    LQ_PORT  = 12345;
    // 5 Hz is enough for the aalink rate-controller (was 100ms=10Hz — excessive UDP
    // traffic competes with video RTP on the Wi-Fi link, degrading quality).
    private static final int    LQ_PERIOD_MS = 200;

    // WIFI POWER-SAVE KILLER: without a low-latency WifiLock, Android dozes the WiFi radio
    // between beacons and batches RX — the stream halts for 1-2s periodically (inputFps=0
    // dropouts) even though the AP keeps transmitting. FULL_LOW_LATENCY (API 29+) disables
    // power save while held + app foreground; FULL_HIGH_PERF is the pre-29 fallback.
    private WifiManager.WifiLock wifiLock;
    // The OS-managed persistent network suggestion (replaces the transient local-only specifier
    // that Android tore down every ~20-40s). Removed in stop().
    private WifiNetworkSuggestion mSuggestion;

    private ConnectivityManager.NetworkCallback netCb;
    // Outstanding request that holds the no-internet Wi-Fi (VTX AP) UP so Android
    // does not tear it down in favour of validated cellular (the "phone keeps
    // disconnecting from OpenIPC" / locallyGenerated reason-3 loop).
    private ConnectivityManager.NetworkCallback holdCb;
    private volatile Network boundNetwork;
    private volatile boolean running = false;
    private volatile boolean ampduPushed = false;   // pushed rtw_ampdu_enable=1 to the VTX this session

    private Thread rssiThread, lqThread;
    private final Handler ui = new Handler(Looper.getMainLooper());

    public ApfpvWifiManager(Context ctx, Listener l) {
        this.ctx = ctx.getApplicationContext();
        this.listener = l;
        this.cm = (ConnectivityManager) this.ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.wifi = (WifiManager) this.ctx.getSystemService(Context.WIFI_SERVICE);
    }

    public void setCredentials(String s, String p) {
        if (s != null) this.ssid = s;
        if (p != null) this.pass = p;
    }

    /**
     * Associate to the AP and bind this process to it. API 29+ uses
     * WifiNetworkSpecifier (the modern, permissionless local-network path).
     * On success, bindProcessToNetwork pins sockets so video + SSH go over Wi-Fi.
     */
    /** Hold a low-latency WifiLock for the whole session — kills WiFi power-save RX batching. */
    private void acquireWifiLock() {
        if (wifiLock != null && wifiLock.isHeld()) return;
        try {
            int mode = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = wifi.createWifiLock(mode, "PixelPilot:apfpv");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } catch (Exception e) {
            emitError("WifiLock: " + e.getMessage());
        }
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        acquireWifiLock();
        // If the phone is ALREADY on Wi-Fi (the bench/home AP, or the VTX AP joined
        // from system settings), do NOT force a new association — bind this process to
        // the current Wi-Fi so the RTP provider's stream reaches the existing :5600
        // UDP socket. Identical data path to the dongle (plain UDP video), no re-join.
        Network cur = currentWifiNetwork();
        if (cur != null) {
            boundNetwork = cur;
            try { cm.bindProcessToNetwork(cur); } catch (Exception ignored) {}
            // bindProcessToNetwork only ROUTES our sockets to Wi-Fi — it does NOT keep
            // the network alive. The VTX AP has no uplink, so if mobile data is on (and
            // internet-validated), Android drops the AP for cellular (locallyGenerated
            // reason 3, in a reconnect loop). Hold an outstanding no-INTERNET Wi-Fi
            // request so ConnectivityService keeps the AP up while we're streaming.
            holdWifiNoInternet();
            emitState((looksLikeApfpv(cur) ? "APFPV on current Wi-Fi (VTX 192.168.0.1)"
                                           : "using current Wi-Fi") + " — listening for RTP on :5600");
            maybeSetVtxAmpduOn(cur);
            startRssiLoop();
            startLqLoop();
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            emitError("phone-Wi-Fi APFPV needs Android 10+ (WifiNetworkSpecifier)");
            running = false;
            return;
        }
        // PERSISTENT path (the disconnect fix): a local-only WifiNetworkSpecifier is TRANSIENT by
        // Android design — the OS tears it down every ~20-40s, and only ONE can exist per app so any
        // re-request RESETS the factory and kills the live connection (the "wifi disconnect after
        // time" + the request-id churn 70821→70824→70830). Instead SUGGEST the network: the OS
        // auto-connects and MAINTAINS it like a saved network (no periodic teardown, no per-connect
        // picker after the one-time "allow suggestions" approval). We OBSERVE via registerNetworkCallback
        // (not requestNetwork) and bind when it comes up. Research: developer.android.com wifi-bootstrap
        // + home-assistant/android#3961 — no API keeps a local-only specifier alive persistently.
        emitState("suggesting " + ssid + " — the OS auto-connects and keeps it up");
        try {
            mSuggestion = new WifiNetworkSuggestion.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(pass)
                    .setIsAppInteractionRequired(false)
                    .build();
            wifi.removeNetworkSuggestions(Collections.singletonList(mSuggestion));  // clear a stale copy
            int rc = wifi.addNetworkSuggestions(Collections.singletonList(mSuggestion));
            emitState("addNetworkSuggestions rc=" + rc
                    + (rc == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS ? " (ok — approve the one-time prompt)" : ""));
        } catch (Exception e) {
            emitError("addNetworkSuggestions failed: " + e.getMessage());
        }
        // Keep the no-internet AP from being reaped for cellular (same as the on-WiFi branch).
        holdWifiNoInternet();
        // OBSERVE Wi-Fi; bind + stream when the suggested APFPV network connects. onLost does NOT
        // re-request (the OS auto-reconnects the suggestion) — that's what stops the self-teardown churn.
        netCb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                NetworkCapabilities c = cm.getNetworkCapabilities(network);
                if (c == null || !c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return;
                boundNetwork = network;
                cm.bindProcessToNetwork(network);
                emitState(looksLikeApfpv(network)
                        ? "APFPV connected (VTX 192.168.0.1) — RTP on :5600"
                        : "Wi-Fi connected — waiting for APFPV subnet");
                maybeSetVtxAmpduOn(network);
                startRssiLoop();
                startLqLoop();
            }
            @Override public void onLost(Network network) {
                // OS-managed suggestion → it will auto-reconnect. Do NOT re-request (that caused the
                // teardown churn). Just clear the process binding until onAvailable fires again.
                if (boundNetwork != null && boundNetwork.equals(network)) {
                    boundNetwork = null;
                    try { cm.bindProcessToNetwork(null); } catch (Exception ignored) {}
                }
                emitState("Wi-Fi dropped — OS reconnecting the suggested network…");
            }
        };
        NetworkRequest observe = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        try {
            cm.registerNetworkCallback(observe, netCb);
        } catch (Exception e) {
            running = false;
            emitError("registerNetworkCallback failed: " + e.getMessage());
        }
    }

    /**
     * Hold an outstanding request for a Wi-Fi network that does NOT require INTERNET.
     * ConnectivityService counts this as a reason to keep the VTX AP up, so it won't
     * reap the no-uplink AP in favour of validated cellular — the app-side equivalent
     * of `settings put global network_avoid_bad_wifi 0`, but scoped to us and needing
     * no special permission. Re-binds the process on (re)availability so video/SSH
     * keep flowing over Wi-Fi. Safe to call when already on the AP.
     */
    private void holdWifiNoInternet() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        if (holdCb != null) return;
        NetworkRequest hold = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        holdCb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                boundNetwork = network;
                try { cm.bindProcessToNetwork(network); } catch (Exception ignored) {}
            }
        };
        try {
            cm.requestNetwork(hold, holdCb);
        } catch (Exception e) {
            holdCb = null; // best-effort; bindProcessToNetwork still routes our sockets
        }
    }

    /**
     * Phone-Wi-Fi is the active client → the VTX should run A-MPDU ON (the phone CAN emit the
     * compressed BlockAck, so aggregation lifts it to ~30 Mbps). Push rtw_ampdu_enable=1 over SSH,
     * once per session, only if this AP is actually an APFPV VTX. The process is already bound to
     * this Wi-Fi (bindProcessToNetwork), so AirSshClient's socket to 192.168.0.1 travels over it.
     * Mirrors the dongle path in ApfpvLinkManager which pushes =0. Pref-gated (apfpv_auto_ampdu).
     */
    private void maybeSetVtxAmpduOn(Network net) {
        if (ampduPushed || net == null || !looksLikeApfpv(net)) return;
        boolean auto = ctx.getSharedPreferences("pixelpilot", Context.MODE_PRIVATE)
                .getBoolean("apfpv_auto_ampdu", true);
        if (!auto) return;
        ampduPushed = true;
        ui.postDelayed(() -> {
            AirSshClient ssh = new AirSshClient();
            ssh.useApfpvHost();   // 192.168.0.1 over the bound Wi-Fi
            ssh.setAmpdu(true, (ok, out) -> {
                com.openipc.pixelpilot.Telemetry.event("apfpv_set_ampdu", "on", "true", "ok", String.valueOf(ok));
                if (!ok) ampduPushed = false;   // retry on the next connect
                emitState("VTX A-MPDU ON " + (ok ? "applied (phone-Wi-Fi)" : "failed"));
            });
        }, 1500);   // small delay so the association/route settles before SSH
    }

    public synchronized void stop() {
        running = false;
        ampduPushed = false;   // re-push on the next association
        if (wifiLock != null) {
            try { if (wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) {}
            wifiLock = null;
        }
        if (rssiThread != null) { rssiThread.interrupt(); rssiThread = null; }
        if (lqThread != null)   { lqThread.interrupt();   lqThread = null; }
        try { cm.bindProcessToNetwork(null); } catch (Exception ignored) {}
        if (netCb != null) {
            try { cm.unregisterNetworkCallback(netCb); } catch (Exception ignored) {}
            netCb = null;
        }
        if (holdCb != null) {
            try { cm.unregisterNetworkCallback(holdCb); } catch (Exception ignored) {}
            holdCb = null;
        }
        if (mSuggestion != null) {
            try { wifi.removeNetworkSuggestions(Collections.singletonList(mSuggestion)); } catch (Exception ignored) {}
            mSuggestion = null;
        }
        boundNetwork = null;
        emitState("stopped");
    }

    /** Heuristic: is the associated AP an APFPV air unit? APFPV assigns the phone
     *  192.168.0.x and the VTX is the 192.168.0.1 gateway — detect that subnet. */
    private boolean looksLikeApfpv(Network net) {
        try {
            android.net.LinkProperties lp = cm.getLinkProperties(net);
            if (lp == null) return false;
            for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                java.net.InetAddress a = la.getAddress();
                if (a != null && a.getHostAddress() != null
                        && a.getHostAddress().startsWith("192.168.0.")) return true;
            }
            for (android.net.RouteInfo r : lp.getRoutes()) {
                java.net.InetAddress g = r.getGateway();
                if (g != null && "192.168.0.1".equals(g.getHostAddress())) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** The currently-active Wi-Fi network, or null if the phone isn't on Wi-Fi. */
    private Network currentWifiNetwork() {
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Current Wi-Fi RSSI in dBm, or -127 if unavailable. */
    public int currentRssi() {
        try {
            if (wifi != null && wifi.getConnectionInfo() != null)
                return wifi.getConnectionInfo().getRssi();
        } catch (Exception ignored) {}
        return -127;
    }

    // Poll WifiManager RSSI for the OSD/link metric.
    private void startRssiLoop() {
        rssiThread = new Thread(() -> {
            while (running && !Thread.currentThread().isInterrupted()) {
                int dbm = currentRssi();
                emitRssi(dbm);
                try { Thread.sleep(250); } catch (InterruptedException e) { break; }
            }
        }, "apfpv-wifi-rssi");
        rssiThread.start();
    }

    // Send LQ feedback to the VTX. greg's aalink listens on UDP 192.168.0.1:12345
    // and expects a bare RSSI percentage (0-100) — confirmed by the native LqFeedback.cpp
    // which sends BOTH "gs_string=..." and bare "%d". The gs_string format may NOT parse
    // on the current aalink binary ("confirmed to move the VTX downlink %" per LqFeedback).
    // Send ONLY the bare percentage — it's the format known to work.
    // Rate: 5 Hz (was 10 Hz) — less UDP competing with video RTP on the Wi-Fi link.
    private void startLqLoop() {
        lqThread = new Thread(() -> {
            DatagramSocket sock = null;
            try {
                sock = new DatagramSocket();
                if (boundNetwork != null) {
                    try { boundNetwork.bindSocket(sock); } catch (Exception ignored) {}
                }
                InetAddress dst = InetAddress.getByName(VTX_IP);
                while (running && !Thread.currentThread().isInterrupted()) {
                    int pct = rssiToPct(currentRssi());
                    byte[] payload = Integer.toString(pct).getBytes();
                    DatagramPacket pkt = new DatagramPacket(payload, payload.length, dst, LQ_PORT);
                    try { sock.send(pkt); } catch (Exception ignored) {}
                    try { Thread.sleep(LQ_PERIOD_MS); } catch (InterruptedException e) { break; }
                }
            } catch (Exception e) {
                emitError("LQ sender: " + e.getMessage());
            } finally {
                if (sock != null) sock.close();
            }
        }, "apfpv-wifi-lq");
        lqThread.start();
    }

    /** OpenIPC GS calibration: sig_pct = 2*(dBm+100), clamped [0,100]. */
    static int rssiToPct(int dbm) {
        int pct = 2 * (dbm + 100);
        if (pct < 0) pct = 0;
        if (pct > 100) pct = 100;
        return pct;
    }

    private void emitState(String s) {
        com.openipc.pixelpilot.Telemetry.event("apfpv_wifi_state", "state", s);
        if (listener != null) ui.post(() -> listener.onState(s));
    }
    private void emitRssi(int d)     { if (listener != null) ui.post(() -> listener.onRssi(d)); }
    private void emitError(String s) {
        com.openipc.pixelpilot.Telemetry.event("apfpv_wifi_error", "detail", s != null ? s : "");
        if (listener != null) ui.post(() -> listener.onError(s));
    }
}
