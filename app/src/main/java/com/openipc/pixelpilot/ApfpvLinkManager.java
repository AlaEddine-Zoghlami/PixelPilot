package com.openipc.pixelpilot;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.view.View;

import com.openipc.pixelpilot.databinding.ActivityVideoBinding;
import com.openipc.wfbngrtl8812.ApfpvStaLink;
import com.openipc.wfbngrtl8812.ApfpvStaLink.StaState;

import java.util.HashMap;
import java.util.Map;

/**
 * ApfpvLinkManager — PixelPilot-side manager for APFPV station mode.
 *
 * Deliberately MIRRORS WfbLinkManager (same USB-permission flow, same
 * attached-adapter bookkeeping, same fd handoff) so it slots into VideoActivity
 * the same way. The DIFFERENCES are exactly the "stateful credentialed
 * connection" concepts WFB-ng lacks:
 *
 *   - startAdapter() passes SSID + passphrase (not just channel/bw)
 *   - it observes a connection LIFECYCLE that can fail, and drives the UI
 *     message accordingly (WfbLinkManager just printed "Starting wfb-ng...")
 *
 * The video path after STREAMING is identical — RTP lands on port 5600 and the
 * existing VideoPlayer renders it. This class only manages link establishment.
 */
public class ApfpvLinkManager {
    public static final String ACTION_USB_PERMISSION = "com.openipc.pixelpilot.USB_PERMISSION";

    private final Context context;
    private final ActivityVideoBinding binding;
    private final ApfpvStaLink staLink;

    private int     wifiChannel = 40;        // default to legal 5.2 GHz UNII-1
    private int     bandWidth   = 20;        // 20 MHz = full 200 mW under PSD cap
    private String  ssid        = "OpenIPC";
    private String  passphrase  = "12345678";

    private final Map<String, UsbDevice> activeAdapters = new HashMap<>();

    public ApfpvLinkManager(Context context, ActivityVideoBinding binding, ApfpvStaLink staLink) {
        this.context = context;
        this.binding = binding;
        this.staLink = staLink;

        // Drive the connection-state UI — the surface WFB-ng never needed.
        this.staLink.setStatusListener(new ApfpvStaLink.StaStatusListener() {
            @Override public void onStateChanged(StaState s) {
                // Full station-mode funnel: SCANNING -> ARMING -> ... -> STREAMING,
                // or a FAIL_* terminal state. One event per transition.
                Telemetry.event("apfpv_state", "state", s.name());
                // On a successful link, persist the active adapter so it
                // auto-reconnects next time (VRX keeps the "auto" wlx).
                if (s == StaState.STREAMING && wlx != null && connectingAdapterName != null) {
                    wlx.rememberConnected(connectingAdapterName);
                }
                showState(s);
            }
            @Override public void onRssi(int dbm)            { showRssi(dbm); }
            @Override public void onError(String detail)     {
                Telemetry.event("apfpv_error", "detail", detail != null ? detail : "");
                showError(detail);
            }
        });
    }

    public void setChannel(int ch)        { this.wifiChannel = ch; }
    public void setBandwidth(int bw)      { this.bandWidth = bw; }
    public void setCredentials(String s, String p) { this.ssid = s; this.passphrase = p; }

    private android.hardware.usb.UsbManager usbManager;
    private com.openipc.pixelpilot.apfpv.WlxAdapters wlx;
    private String connectingAdapterName;   // adapter currently being brought up
    /** Multi-adapter: enumerate all Realtek dongles (VRX wlx parity), bind the active one. */
    public synchronized void refreshAdapters() {
        if (wlx == null) wlx = new com.openipc.pixelpilot.apfpv.WlxAdapters(context);
        wlx.enumerate();
        com.openipc.pixelpilot.apfpv.WlxAdapters.Adapter a = wlx.active();
        if (a != null) { connectingAdapterName = a.name; startAdapter(a.device); }
    }
    /** Expose the adapter list for the UI (gs apfpv wlx). */
    public com.openipc.pixelpilot.apfpv.WlxAdapters adapters() {
        if (wlx == null) { wlx = new com.openipc.pixelpilot.apfpv.WlxAdapters(context); wlx.enumerate(); }
        return wlx;
    }
    /** Make a specific adapter active and bind it (set gs apfpv wlx <name> on). */
    public synchronized boolean selectAdapter(String name) {
        if (wlx == null) refreshAdapters();
        com.openipc.pixelpilot.apfpv.WlxAdapters.Adapter a = wlx.setActive(name);
        if (a == null) return false;
        connectingAdapterName = a.name;
        return startAdapter(a.device);
    }
    public synchronized void stopAdapters() {
        if (staLink != null) staLink.disconnect();
    }

    /** Open the first RTL8812AU dongle and run an all-SSID scan for the picker.
     *  Results stream to the listener on a worker thread (marshal UI updates).
     *  Returns false if there's no dongle / permission / driver yet. */
    public synchronized boolean scanSsids(int perChannelMs, boolean includeDfs, ApfpvStaLink.ScanListener l) {
        UsbManager mgr = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (mgr == null || staLink == null || staLink.handle() == 0L) return false;
        UsbDevice dev = null;
        for (UsbDevice d : mgr.getDeviceList().values())
            if (d.getVendorId() == 0x0bda) { dev = d; break; }   // Realtek
        if (dev == null) { showMessage("APFPV: no RTL8812AU dongle to scan"); return false; }
        if (!mgr.hasPermission(dev)) {
            requestPermission(mgr, dev);
            showMessage("APFPV: allow USB access, then scan again");
            return false;
        }
        UsbDeviceConnection conn = mgr.openDevice(dev);
        if (conn == null) { showMessage("APFPV: couldn't open dongle to scan"); return false; }
        int fd = conn.getFileDescriptor();
        if (fd < 0) { conn.close(); return false; }
        Telemetry.event("apfpv_scan", "vidpid",
                String.format("%04X:%04X", dev.getVendorId(), dev.getProductId()),
                "dfs", String.valueOf(includeDfs));
        staLink.scan(fd, perChannelMs, includeDfs, l);
        return true;
    }

    /** VRX EIRP-calibration: make the dongle broadcast a hardcoded SSID so a
     *  SECOND phone (no dongle) can scan + measure this dongle's EIRP at a known
     *  distance. Opens the dongle like scanSsids(); beacons until stopBeaconCal(). */
    public synchronized boolean startBeaconCal(String ssid, int channel, int txIndex) {
        UsbManager mgr = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (mgr == null || staLink == null || staLink.handle() == 0L) return false;
        UsbDevice dev = null;
        for (UsbDevice d : mgr.getDeviceList().values())
            if (d.getVendorId() == 0x0bda) { dev = d; break; }
        if (dev == null) { showMessage("VRX beacon: no dongle"); return false; }
        if (!mgr.hasPermission(dev)) {
            requestPermission(mgr, dev);
            showMessage("VRX beacon: allow USB access, then retry");
            return false;
        }
        UsbDeviceConnection conn = mgr.openDevice(dev);
        if (conn == null) { showMessage("VRX beacon: couldn't open dongle"); return false; }
        int fd = conn.getFileDescriptor();
        if (fd < 0) { conn.close(); return false; }
        Telemetry.event("apfpv_beacon_cal", "ch", String.valueOf(channel), "idx", String.valueOf(txIndex));
        // Device bring-up (Init) inside startBeaconCal blocks for seconds — run it
        // off the UI thread or it ANRs. The injector then runs on its own native thread.
        final int fdF = fd; final String ssidF = ssid; final int chF = channel, idxF = txIndex;
        new Thread(() -> staLink.startBeaconCal(fdF, ssidF, chF, idxF), "apfpv-beacon-start").start();
        showMessage("VRX beacon ON: \"" + ssid + "\" ch" + channel + " (TX idx " + txIndex + ")");
        return true;
    }

    public synchronized void stopBeaconCal() {
        if (staLink != null) staLink.stopBeaconCal();
        showMessage("VRX beacon OFF");
    }

    // --- mirrors WfbLinkManager.startAdapter, but credentialed + stateful ----
    // Every external precondition here can fail at runtime (no device, permission
    // not yet granted, the dongle still claimed by the WFB stack we just stopped,
    // an uninitialised native handle). Each was previously unchecked and would
    // crash the app when switching to APFPV dongle mode with a device connected —
    // notably openDevice() returning null and being dereferenced for its fd.
    public synchronized boolean startAdapter(UsbDevice dev) {
        if (dev == null) { Telemetry.event("apfpv_start_fail", "reason", "no_device"); return false; }
        String vidpid = String.format("%04X:%04X", dev.getVendorId(), dev.getProductId());
        Telemetry.event("apfpv_start", "vidpid", vidpid, "ch", String.valueOf(wifiChannel));
        UsbManager mgr = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (mgr == null) {
            Telemetry.event("apfpv_start_fail", "reason", "no_usb_service");
            showMessage("APFPV: USB service unavailable"); return false;
        }

        // No permission yet: request it (mirrors the WFB path) and bail — the
        // grant lets a subsequent switch/refresh succeed. Never open without it.
        if (!mgr.hasPermission(dev)) {
            Telemetry.event("apfpv_perm_request", "vidpid", vidpid);
            requestPermission(mgr, dev);
            showMessage("APFPV: allow USB access, then switch again");
            return false;
        }

        // openDevice() returns null if the open races or the fd is still held by
        // the stack we just tore down — must not deref it for getFileDescriptor().
        UsbDeviceConnection conn = mgr.openDevice(dev);
        if (conn == null) {
            Telemetry.event("apfpv_start_fail", "reason", "open_null");
            showMessage("APFPV: couldn't open dongle (busy?) — re-plug and retry");
            return false;
        }
        int fd = conn.getFileDescriptor();
        if (fd < 0) {
            conn.close();
            Telemetry.event("apfpv_start_fail", "reason", "bad_fd");
            showMessage("APFPV: invalid dongle handle");
            return false;
        }

        // The native station object must exist before we call into JNI, or the
        // native side dereferences a null handle (SIGSEGV, not a Java exception).
        if (staLink == null || staLink.handle() == 0L) {
            conn.close();
            Telemetry.event("apfpv_start_fail", "reason", "native_uninit");
            showMessage("APFPV: station driver not initialised");
            return false;
        }

        showMessage("APFPV: connecting to \"" + ssid + "\"…");   // channel follows the AP (discovery)

        // Flow event + Crashlytics keys so a crash in the native chain below is
        // pinpointed (the JNI call can SIGSEGV — no Java stack otherwise).
        Crash.key("apfpv_channel", String.valueOf(wifiChannel));
        Telemetry.setMode("APFPV");
        Telemetry.event("apfpv_native_connect", "vidpid", vidpid, "fd", String.valueOf(fd));

        // nativeStaConnect runs the WHOLE gated chain (ARM -> auth -> assoc ->
        // WPA2 -> DHCP -> RTP) and BLOCKS until it reaches STREAMING or fails.
        // It MUST run off the UI thread — running it inline froze the main thread
        // and Android killed the app with an ANR. Drive it on a worker; progress
        // arrives via the state callbacks (which post back to the UI thread).
        // PHONE-ASSISTED: the phone's own Wi-Fi already sees the AP, so resolve its
        // BSSID + channel here and hand them to the dongle — it then arms straight
        // to that BSSID/channel instead of the slow, flaky beacon sweep.
        String bssid = ""; int resolvedCh = wifiChannel;
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                for (android.net.wifi.ScanResult r : wm.getScanResults()) {
                    if (ssid.equals(r.SSID)) { bssid = r.BSSID; resolvedCh = freqToChannel(r.frequency); break; }
                }
            }
        } catch (Exception ignored) {}
        if (!bssid.isEmpty()) wifiChannel = resolvedCh;
        Telemetry.event("apfpv_resolve", "bssid", bssid.isEmpty() ? "none" : bssid, "ch", String.valueOf(resolvedCh));
        final long handle = staLink.handle();
        final int fdF = fd, chF = wifiChannel, bwF = bandWidth;
        final String ssidF = ssid, passF = passphrase, bssidF = bssid;
        new Thread(() -> {
            // State callbacks drive the UI; STREAMING => video on 5600.
            ApfpvStaLink.nativeStaConnect(handle, staLink, fdF, chF, bwF, ssidF, passF, bssidF);
            // Turn on dongle-RSSI -> VTX:12345 feedback (better than stock phone-APFPV)
            ApfpvStaLink.nativeStaSetLqFeedback(handle, true);
        }, "apfpv-connect").start();
        return true;
    }

    /** Wi-Fi centre frequency (MHz) -> 802.11 channel number. */
    private static int freqToChannel(int freqMHz) {
        if (freqMHz == 2484) return 14;
        if (freqMHz >= 2412 && freqMHz <= 2472) return (freqMHz - 2407) / 5;   // 2.4 GHz 1-13
        if (freqMHz >= 5000 && freqMHz <= 5900) return (freqMHz - 5000) / 5;   // 5 GHz
        return 40;
    }

    /** Ask the user for USB access to this dongle (same intent the WFB path uses). */
    private void requestPermission(UsbManager mgr, UsbDevice dev) {
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(
                context, 0, new Intent(ACTION_USB_PERMISSION), flags);
        mgr.requestPermission(dev, pi);
    }

    /** Post a status line to the shared message view from any thread. */
    private void showMessage(String msg) {
        binding.tvMessage.post(() -> {
            binding.tvMessage.setVisibility(View.VISIBLE);
            binding.tvMessage.setText(msg);
        });
    }

    // --- map lifecycle/failures to user-visible messages ---------------------
    // Framed as the user-facing dongle flow: adapter -> connecting to SSID ->
    // connected to SSID -> APFPV found (streaming) / not found.
    private String labelFor(StaState s) {
        int ch = (staLink != null) ? staLink.getChannel() : 0;
        String onCh = ch > 0 ? " (ch" + ch + ")" : "";
        switch (s) {
            case SCANNING:       return "Searching for \"" + ssid + "\"…";
            case ARMING:         return "Found \"" + ssid + "\"" + onCh + " — arming…";
            case AUTHENTICATING: return "Found" + onCh + " — authenticating…";
            case ASSOCIATING:    return "Associating" + onCh + "…";
            case HANDSHAKING:    return "WPA2 handshake" + onCh + "…";
            case DHCP:           return "Connected" + onCh + " — getting IP…";
            case STREAMING:      return "APFPV connected — video on \"" + ssid + "\"" + onCh;
            case FAIL_NO_AP:     return "\"" + ssid + "\" not found — check it's on + in range";
            case FAIL_TX:        return "Found" + onCh + " but no auth reply (range / AP busy?)";
            case FAIL_NO_ACK:    return "Deauthed during association" + onCh;
            case FAIL_AUTH:      return "Assoc/handshake failed — wrong password?";
            case FAIL_DHCP:      return "Associated" + onCh + " — no IP (not an APFPV AP?)";
            case LINK_LOST:      return "Link lost — reconnecting…";
            case RECONNECTING:   return "Reconnecting…";
            case IDLE:           return hasDongle() ? "Adapter ready" : "No adapter";
            default:             return "";
        }
    }

    /** Concise current APFPV status line for the menu / OSD. */
    public String statusLine() {
        if (!hasDongle()) return "No adapter";
        StaState s = (staLink != null) ? staLink.stateEnum() : StaState.IDLE;
        String l = labelFor(s);
        return l.isEmpty() ? "Adapter connected" : l;
    }

    /** True if an RTL8812AU dongle is currently attached. */
    public boolean hasDongle() {
        UsbManager mgr = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (mgr == null) return false;
        for (UsbDevice d : mgr.getDeviceList().values())
            if (d.getVendorId() == 0x0bda) return true;
        return false;
    }

    private void showState(StaState s) {
        // Streaming hides the banner (video is up); everything else shows the label.
        final String label = labelFor(s);
        final String msg = (s == StaState.STREAMING) ? "" : (label.isEmpty() ? "" : "APFPV: " + label);
        binding.tvMessage.post(() -> {
            binding.tvMessage.setVisibility(msg.isEmpty() ? View.GONE : View.VISIBLE);
            binding.tvMessage.setText(msg);
        });
    }

    private void showRssi(int dbm)  { /* feed OSD RSSI widget */ }
    private void showError(String d){ /* log / toast */ }

    private long staLinkHandle() { return staLink.handle(); }
}
