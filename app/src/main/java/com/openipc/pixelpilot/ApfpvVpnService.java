package com.openipc.pixelpilot;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.openipc.wfbngrtl8812.ApfpvStaLink;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

/**
 * ApfpvVpnService — routes TCP/UDP to the VTX (192.168.0.1) through the USB dongle when the
 * phone is NOT on the VTX Wi-Fi. The devourer link is a userspace stack (not an Android
 * Network), so a plain socket to 192.168.0.1 would go out the phone's Wi-Fi/cellular and never
 * reach the VTX. This TUN captures 192.168.0.1 traffic and pumps it through the devourer's
 * general-IP bridge (ApfpvStation.sendIpPacket / RxDeframe.setIpSink), so AirSshClient's SSH —
 * and any HTTP to the VTX — works on the dongle path exactly as it does over phone-Wi-Fi.
 *
 *   uplink   : app socket -> OS routes 192.168.0.1 -> TUN -> ApfpvStaLink.sendIp -> dongle -> VTX
 *   downlink : VTX -> dongle -> native IP sink -> UDP 127.0.0.1:5601 -> here -> TUN -> app socket
 *
 * The TUN address is 192.168.0.10/24 (the dongle's DHCP identity) and it routes ONLY
 * 192.168.0.1/32, so RTP (loopback :5600) and all other traffic are untouched.
 *
 * Mirrors WfbNgVpnService. Start after the dongle link is connected; requires VpnService
 * consent (VpnService.prepare()) from an Activity first.
 */
public class ApfpvVpnService extends VpnService {
    private static final String TAG = "ApfpvVpnService";
    public static final String  VTX_IP = "192.168.0.1";
    private static final int    DOWNLINK_PORT = 5601;   // native IP sink -> here (must match apfpv_jni)

    private ParcelFileDescriptor tun = null;
    private Thread tunToDongle, dongleToTun;
    private volatile boolean running = false;

    /** True while the VTX route (192.168.0.1 -> TUN -> dongle) is up. Read by the UI to report
     *  whether a settings SSH will travel over the dongle vs the phone's Wi-Fi. */
    public static volatile boolean sRunning = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) { shutdown(); return START_NOT_STICKY; }
        if (running) return START_STICKY;
        // Only ONE Android VpnService can hold the TUN at a time. If the WFB-ng VPN is up (or was
        // started this session) it captures the routing table and our 192.168.0.1/32 route never
        // takes effect → SSH/LQ to the VTX silently go nowhere. Tear it down before we establish.
        try { stopService(new Intent(this, WfbNgVpnService.class)); } catch (Exception ignored) {}
        // Use the dongle's ACTUAL DHCP lease as the TUN address. If the TUN sources from an IP the
        // dongle didn't lease (the old hardcoded .10), the VTX can't ARP/route replies back to it →
        // SSH times out and aalink ignores the LQ (both need the round-trip). Fall back to .10 only
        // if the lease isn't known yet (matches the native claimStatic_192_168_0_10 fallback).
        int leaseBe = ApfpvStaLink.leaseIp();   // host byte order, 0 if none
        String tunAddr = (leaseBe != 0)
                ? ((leaseBe >> 24) & 0xff) + "." + ((leaseBe >> 16) & 0xff) + "."
                  + ((leaseBe >> 8) & 0xff) + "." + (leaseBe & 0xff)
                : "192.168.0.10";
        Log.i(TAG, "TUN address = " + tunAddr + " (lease " + (leaseBe != 0 ? "known" : "unknown, using .10 fallback") + ")");
        try {
            tun = new Builder()
                    .setSession("apfpv-vtx")
                    .addAddress(tunAddr, 24)          // the dongle's real station IP (DHCP lease)
                    .addRoute(VTX_IP, 32)             // ONLY the VTX -> TUN (RTP/loopback untouched)
                    .establish();
            if (tun == null) { Log.e(TAG, "establish() returned null"); stopSelf(); return START_NOT_STICKY; }
        } catch (Exception e) {
            Log.e(TAG, "Failed to establish TUN", e); stopSelf(); return START_NOT_STICKY;
        }
        running = true;
        sRunning = true;
        ApfpvStaLink.setIpBridge(true);   // arm the native downlink -> UDP 5601
        startThreads();
        Log.i(TAG, "APFPV VPN up: 192.168.0.10/24, route " + VTX_IP + "/32 via dongle");
        return START_STICKY;
    }

    private void startThreads() {
        final FileInputStream in = new FileInputStream(tun.getFileDescriptor());
        final FileOutputStream out = new FileOutputStream(tun.getFileDescriptor());

        // Uplink: TUN -> dongle. Each read() is one IP packet destined to the VTX.
        tunToDongle = new Thread(() -> {
            byte[] buf = new byte[32 * 1024];
            long pktCount = 0;
            try {
                while (running) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    if (n == 0) continue;
                    byte[] pkt = new byte[n];
                    System.arraycopy(buf, 0, pkt, 0, n);
                    // DIAGNOSTIC: confirm LQ/SSH packets to 192.168.0.1 actually reach the TUN.
                    // Decode dst port (UDP/TCP) from the IPv4 header so we can tell LQ (12345) from SSH.
                    if (++pktCount <= 5 || (pktCount % 64) == 0) {
                        int proto = (n > 9) ? (pkt[9] & 0xff) : -1;
                        int ihl = (n > 0) ? (pkt[0] & 0x0f) * 4 : 0;
                        int dport = (n >= ihl + 4) ? (((pkt[ihl + 2] & 0xff) << 8) | (pkt[ihl + 3] & 0xff)) : -1;
                        Log.i(TAG, "tun->dongle #" + pktCount + " len=" + n + " proto=" + proto + " dport=" + dport);
                    }
                    ApfpvStaLink.sendIp(pkt, n);   // -> native sendIpPacket -> CCMP -> dongle
                }
            } catch (IOException e) { if (running) Log.e(TAG, "tun->dongle", e); }
        }, "apfpv-tun-tx");

        // Downlink: native sink -> UDP 5601 -> TUN. Each datagram is one decrypted IP packet.
        dongleToTun = new Thread(() -> {
            byte[] buf = new byte[32 * 1024];
            try (DatagramSocket sock = new DatagramSocket(null)) {
                sock.setReuseAddress(true);
                sock.bind(new InetSocketAddress("127.0.0.1", DOWNLINK_PORT));
                while (running) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    sock.receive(p);
                    if (p.getLength() > 0) out.write(p.getData(), p.getOffset(), p.getLength());
                }
            } catch (IOException e) { if (running) Log.e(TAG, "dongle->tun", e); }
        }, "apfpv-tun-rx");

        tunToDongle.start();
        dongleToTun.start();
    }

    private void shutdown() {
        running = false;
        sRunning = false;
        ApfpvStaLink.setIpBridge(false);
        if (tunToDongle != null) tunToDongle.interrupt();
        if (dongleToTun != null) dongleToTun.interrupt();
        if (tun != null) { try { tun.close(); } catch (IOException ignored) {} tun = null; }
        stopSelf();
    }

    @Override public void onDestroy() { super.onDestroy(); shutdown(); }
    @Override public void onRevoke()  { super.onRevoke();  shutdown(); }
}
