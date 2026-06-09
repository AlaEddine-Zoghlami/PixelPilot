package com.openipc.pixelpilot;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import com.openipc.pixelpilot.databinding.ActivityVideoBinding;

/**
 * LinkModeCoordinator — seamless WFB-ng <-> APFPV switching, mirroring exactly
 * what the VRX gsmenu does for "set gs system rx_mode" but adapted to the phone.
 *
 * THE VRX SEQUENCE (from gsmenu.sh, verified):
 *   to apfpv:  stop adaptive-link + wifibroadcast services
 *              disable them in /etc/default
 *              rmmod 8812eu / 88XXau_wfb ; modprobe them   (RELOAD THE DRIVER)
 *              write wpa_supplicant.apfpv.conf
 *              bring wlx iface up with udhcpc.apfpv.script (no routes/dns)
 *   to wfb:    ifdown + remove wlx iface configs
 *              rmmod / modprobe                              (RELOAD THE DRIVER)
 *              enable wifibroadcast + adaptive-link, start them
 *
 * THE KEY INSIGHT: the switch is NOT just a flag. The VRX RELOADS the USB
 * driver module (rmmod/modprobe) because the dongle must be re-initialized in a
 * different mode (monitor vs managed/station). On the phone the equivalent is:
 *   - fully stop the active LinkManager (release the UsbDevice connection)
 *   - close the native driver (frees the libusb handle on that fd)
 *   - re-open the USB device and re-init the driver in the target mode
 * That is what makes the switch SEAMLESS rather than requiring an app restart:
 * we tear down one transport's hold on the dongle and hand the same fd to the
 * other, exactly as rmmod/modprobe hands the netdev between drivers.
 */
public class LinkModeCoordinator {
    /**
     * Three link modes:
     *   WFB        — RTL8812AU dongle in monitor mode (devourer/WfbNgLink).
     *   APFPV      — RTL8812AU dongle in station mode (devourer/ApfpvStaLink).
     *   APFPV_WIFI — the PHONE'S Wi-Fi chip associates to greg's AP, no dongle.
     *                Video arrives as plain UDP to the existing 5600 socket;
     *                air config still SSH to 192.168.0.1; RSSI from WifiManager.
     */
    public enum Mode { WFB, APFPV, APFPV_WIFI }

    private final Context context;
    private final ActivityVideoBinding binding;
    private final WfbLinkManager wfb;
    private final ApfpvLinkManager apfpv;
    private final com.openipc.pixelpilot.apfpv.ApfpvWifiManager apfpvWifi;
    private final UsbManager usbManager;
    private Mode current;

    public interface ModeChangeListener { void onModeChanged(Mode m, boolean ok, String detail); }

    public LinkModeCoordinator(Context ctx, ActivityVideoBinding b,
                               WfbLinkManager wfb, ApfpvLinkManager apfpv,
                               com.openipc.pixelpilot.apfpv.ApfpvWifiManager apfpvWifi,
                               Mode initial) {
        this.context = ctx; this.binding = b; this.wfb = wfb; this.apfpv = apfpv;
        this.apfpvWifi = apfpvWifi;
        this.usbManager = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        this.current = initial;
    }

    public Mode current() { return current; }

    /**
     * Seamless switch. Equivalent of the VRX's stop -> rmmod/modprobe ->
     * reconfigure -> start. Returns immediately; result via listener.
     *
     * @param target   the mode to switch to
     * @param ssid     APFPV SSID (ignored for WFB)
     * @param pass     APFPV password (ignored for WFB)
     */
    public synchronized void switchTo(Mode target, String ssid, String pass, ModeChangeListener cb) {
        Telemetry.event("link_switch", "from", current.name(), "to", target.name());
        Telemetry.setMode(target.name());
        if (target == current) { if (cb != null) cb.onModeChanged(current, true, "already in mode"); return; }

        // 1. STOP the active stack. Dongle modes release the libusb fd; the
        //    phone-Wi-Fi mode tears down its network binding + association.
        Telemetry.event("link_stop_prev", "mode", current.name());
        switch (current) {
            case WFB:        wfb.stopAdapters();   break;
            case APFPV:      apfpv.stopAdapters(); break;
            case APFPV_WIFI: if (apfpvWifi != null) apfpvWifi.stop(); break;
        }
        // Defensive: a DONGLE transport must NEVER have the phone-Wi-Fi network binding
        // active. apfpvWifi.start() calls cm.bindProcessToNetwork(wifi), which pins this
        // process's 5600 UDP socket to the phone Wi-Fi — so the dongle's stream never
        // reaches the receiver and video is BLACK. Always tear down phone-Wi-Fi (it is
        // idempotent and clears bindProcessToNetwork(null)) when entering APFPV/WFB dongle
        // mode, even if the coordinator didn't think APFPV_WIFI was current (the binding
        // can leak across cold-start / non-switch paths).
        if ((target == Mode.APFPV || target == Mode.WFB) && apfpvWifi != null) {
            apfpvWifi.stop();
        }

        // 2. Persist the new mode (cold start comes up in the right mode).
        String modeStr = target == Mode.APFPV ? "apfpv"
                       : target == Mode.APFPV_WIFI ? "apfpv_wifi" : "wfb";
        context.getSharedPreferences("pixelpilot", Context.MODE_PRIVATE).edit()
            .putString("link_mode", modeStr)
            .putString("apfpv_ssid", ssid != null ? ssid : "OpenIPC")
            .putString("apfpv_pass", pass != null ? pass : "12345678")
            .apply();

        // 3. START the target mode.
        current = target;
        boolean ok;
        String detail;
        switch (target) {
            case APFPV:
                apfpv.setCredentials(ssid != null ? ssid : "OpenIPC",
                                     pass != null ? pass : "12345678");
                ok = rebind(apfpv::startAdapter);
                detail = ok ? "switched to APFPV (dongle)" : "no RTL8812AU dongle found";
                break;
            case APFPV_WIFI:
                // No dongle. Associate the phone's Wi-Fi to the AP and bind
                // sockets to it; video arrives on the existing 5600 UDP socket.
                if (apfpvWifi == null) { ok = false; detail = "wifi manager unavailable"; break; }
                apfpvWifi.setCredentials(ssid != null ? ssid : "OpenIPC",
                                         pass != null ? pass : "12345678");
                apfpvWifi.start();
                ok = true;  // async; real result arrives via the manager's listener
                detail = "joining " + (ssid != null ? ssid : "OpenIPC") + " (phone Wi-Fi)\u2026";
                break;
            case WFB:
            default:
                ok = rebind(wfb::startAdapter);
                detail = ok ? "switched to WFB-ng (dongle)" : "no RTL8812AU dongle found";
                break;
        }
        Telemetry.event("link_start_result", "mode", target.name(), "ok", String.valueOf(ok));
        if (cb != null) cb.onModeChanged(current, ok, detail);
    }

    /** Find the AU dongle and hand it to the target manager's startAdapter. */
    private boolean rebind(java.util.function.Function<UsbDevice, Boolean> starter) {
        if (usbManager == null) return false;
        for (UsbDevice dev : usbManager.getDeviceList().values()) {
            if (dev.getVendorId() == 0x0bda) {   // Realtek (RTL8812AU family)
                Boolean r = starter.apply(dev);
                return r != null && r;
            }
        }
        return false;
    }

    /**
     * USB hotplug for DONGLE modes — call on ACTION_USB_DEVICE_ATTACHED/DETACHED.
     * On physical removal the active adapter is torn down (the libusb fd is gone); on
     * re-insert we re-acquire USB permission and restart the adapter, which re-runs
     * scan/auth/assoc/4-way back to streaming. No-op in phone-Wi-Fi mode (no dongle).
     * The library's own RX-timeout supervisor handles out-of-range/link-loss; THIS
     * handles the dongle hardware physically coming and going.
     */
    public synchronized void onUsbHotplug(ModeChangeListener cb) {
        if (current == Mode.APFPV_WIFI || usbManager == null) return;
        UsbDevice dongle = null;
        for (UsbDevice dev : usbManager.getDeviceList().values()) {
            if (dev.getVendorId() == 0x0bda) { dongle = dev; break; }   // Realtek RTL8812AU family
        }
        if (dongle == null) {
            // Physically removed -> tear down the active adapter and wait for re-insert.
            Telemetry.event("usb_dongle_removed", "mode", current.name());
            if (current == Mode.APFPV) apfpv.stopAdapters(); else wfb.stopAdapters();
            if (cb != null) cb.onModeChanged(current, false, "dongle removed — re-insert to reconnect");
            return;
        }
        // Present: ensure USB permission (re-granted per attach), then (re)start.
        if (!usbManager.hasPermission(dongle)) {
            PendingIntent pi = PendingIntent.getBroadcast(context, 0,
                new Intent(WfbLinkManager.ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
            usbManager.requestPermission(dongle, pi);
            return;   // permission grant triggers another hotplug pass
        }
        Telemetry.event("usb_dongle_reattach", "mode", current.name());
        // CRITICAL: stop the previous station FIRST. A re-inserted dongle is a NEW fd, but
        // the old station's supervisor may still be spinning on the dead fd — and
        // ApfpvStation::connect() only (re)starts the supervisor when _run is clear
        // (`autoReconnect && !_run.exchange(true)`). Without this stop, the fresh connect
        // never gets a supervisor and stays stuck on FAIL_NO_AP ("not in range"). stop()
        // clears _run so the new startAdapter() spins up a fresh, retrying supervisor.
        if (current == Mode.APFPV) apfpv.stopAdapters(); else wfb.stopAdapters();
        boolean ok = (current == Mode.APFPV)
                ? Boolean.TRUE.equals(apfpv.startAdapter(dongle))
                : Boolean.TRUE.equals(wfb.startAdapter(dongle));
        if (cb != null) cb.onModeChanged(current, ok, ok ? "dongle reconnected" : "dongle re-init failed");
    }
}
