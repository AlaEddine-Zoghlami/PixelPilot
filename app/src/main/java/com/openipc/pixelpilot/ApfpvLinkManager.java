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
                // On a successful link, persist the active adapter so it
                // auto-reconnects next time (VRX keeps the "auto" wlx).
                if (s == StaState.STREAMING && wlx != null && connectingAdapterName != null) {
                    wlx.rememberConnected(connectingAdapterName);
                }
                showState(s);
            }
            @Override public void onRssi(int dbm)            { showRssi(dbm); }
            @Override public void onError(String detail)     { showError(detail); }
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

    // --- mirrors WfbLinkManager.startAdapter, but credentialed + stateful ----
    // Every external precondition here can fail at runtime (no device, permission
    // not yet granted, the dongle still claimed by the WFB stack we just stopped,
    // an uninitialised native handle). Each was previously unchecked and would
    // crash the app when switching to APFPV dongle mode with a device connected —
    // notably openDevice() returning null and being dereferenced for its fd.
    public synchronized boolean startAdapter(UsbDevice dev) {
        if (dev == null) return false;
        UsbManager mgr = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (mgr == null) { showMessage("APFPV: USB service unavailable"); return false; }

        // No permission yet: request it (mirrors the WFB path) and bail — the
        // grant lets a subsequent switch/refresh succeed. Never open without it.
        if (!mgr.hasPermission(dev)) {
            requestPermission(mgr, dev);
            showMessage("APFPV: allow USB access, then switch again");
            return false;
        }

        // openDevice() returns null if the open races or the fd is still held by
        // the stack we just tore down — must not deref it for getFileDescriptor().
        UsbDeviceConnection conn = mgr.openDevice(dev);
        if (conn == null) {
            showMessage("APFPV: couldn't open dongle (busy?) — re-plug and retry");
            return false;
        }
        int fd = conn.getFileDescriptor();
        if (fd < 0) {
            conn.close();
            showMessage("APFPV: invalid dongle handle");
            return false;
        }

        // The native station object must exist before we call into JNI, or the
        // native side dereferences a null handle (SIGSEGV, not a Java exception).
        if (staLink == null || staLink.handle() == 0L) {
            conn.close();
            showMessage("APFPV: station driver not initialised");
            return false;
        }

        showMessage("APFPV: connecting to \"" + ssid + "\" ch" + wifiChannel);

        // Breadcrumbs so a crash in the native chain below is pinpointed in the
        // Crashlytics report (the JNI call can SIGSEGV — no Java stack otherwise).
        Crash.key("link_mode", "APFPV");
        Crash.key("apfpv_channel", String.valueOf(wifiChannel));
        Crash.log(String.format("APFPV startAdapter: dongle %04X:%04X fd=%d -> nativeStaConnect",
                                 dev.getVendorId(), dev.getProductId(), fd));

        // Runs the WHOLE gated native chain: ARM -> auth -> assoc -> WPA2 ->
        // DHCP -> RTP. State callbacks drive the UI; STREAMING => video on 5600.
        ApfpvStaLink.nativeStaConnect(staLink.handle(), staLink, fd,
                                      wifiChannel, bandWidth, ssid, passphrase);
        // Turn on dongle-RSSI -> VTX:12345 feedback (better than stock phone-APFPV)
        ApfpvStaLink.nativeStaSetLqFeedback(staLink.handle(), true);
        return true;
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
    private void showState(StaState s) {
        final String msg;
        switch (s) {
            case SCANNING:       msg = "APFPV: scanning for " + ssid + "…"; break;
            case ARMING:         msg = "APFPV: arming station mode…"; break;
            case AUTHENTICATING: msg = "APFPV: authenticating…"; break;
            case ASSOCIATING:    msg = "APFPV: associating…"; break;
            case HANDSHAKING:    msg = "APFPV: WPA2 handshake…"; break;
            case DHCP:           msg = "APFPV: getting IP…"; break;
            case STREAMING:      msg = ""; break;                  // hide; video is up
            case FAIL_NO_AP:     msg = "APFPV: AP not found — check channel/range"; break;
            case FAIL_TX:        msg = "APFPV: adapter TX problem (descriptor)"; break;
            case FAIL_NO_ACK:    msg = "APFPV: adapter can't ACK in station mode "
                                       + "(use WFB-ng instead)"; break;
            case FAIL_AUTH:      msg = "APFPV: wrong password / handshake failed"; break;
            case FAIL_DHCP:      msg = "APFPV: associated but no IP"; break;
            case LINK_LOST:      msg = "APFPV: link lost"; break;
            default:             msg = ""; break;
        }
        binding.tvMessage.post(() -> {
            binding.tvMessage.setVisibility(msg.isEmpty() ? View.GONE : View.VISIBLE);
            binding.tvMessage.setText(msg);
        });
    }

    private void showRssi(int dbm)  { /* feed OSD RSSI widget */ }
    private void showError(String d){ /* log / toast */ }

    private long staLinkHandle() { return staLink.handle(); }
}
