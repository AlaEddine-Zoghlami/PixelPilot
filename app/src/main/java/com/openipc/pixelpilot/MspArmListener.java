package com.openipc.pixelpilot;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;

/**
 * Listens for MSP telemetry forwarded from the VTX and reports flight-controller ARM/DISARM edges.
 *
 * <p>WHERE THE BYTES COME FROM. msposd on the VTX reads MSP from the FC on /dev/ttyS3 and can
 * forward it over UDP with its {@code -o <ip>:<port>} flag. Two important details, both established
 * by capturing the real wire:
 *
 * <ul>
 *   <li>msposd MUST run with {@code -d} ("Parse MSP and draw OSD"). Without it msposd is in
 *       "Simple UART Reading mode" and never <em>asks</em> the FC for anything — the stream is then
 *       100% MSP_DISPLAYPORT (cmd 182), i.e. pre-rendered "draw these characters at row/col" OSD
 *       commands with no structured telemetry at all, so there is no arm bit to read. With
 *       {@code -d} msposd polls the FC and MSP_STATUS (cmd 101) appears.</li>
 *   <li>On the dongle path the forwarded UDP only reaches an OS socket while the APFPV IP bridge /
 *       TUN is up (ApfpvVpnService, auto-started on STREAMING once VPN consent exists). Without it
 *       192.168.0.10 lives only inside devourer's userspace stack and nothing arrives here — the
 *       feature then simply never triggers, which is why every failure path below is quiet rather
 *       than fatal. On phone-Wi-Fi mode the OS receives it unconditionally.</li>
 * </ul>
 *
 * <p>Deliberately Java-only: routing this through the native RX path instead would put work on the
 * single RX worker thread whose latency we tune carefully, for no benefit.
 */
public final class MspArmListener {
    private static final String TAG = "MspArm";

    /** Standard telemetry port; must match msposd's {@code -o <ip>:PORT}. */
    public static final int DEFAULT_PORT = 14550;

    private static final int MSP_STATUS = 101;
    private static final int MSP_STATUS_EX = 150;

    public interface ArmCallback {
        /** Called only on a CHANGE of arm state, never per packet. */
        void onArmChanged(boolean armed);
    }

    private final int port;
    private final ArmCallback callback;
    private Thread thread;
    private DatagramSocket socket;
    private volatile boolean running;

    /** null until the first MSP_STATUS arrives, so we never fire an edge from an assumed state. */
    private Boolean lastArmed;

    public MspArmListener(int port, ArmCallback callback) {
        this.port = port;
        this.callback = callback;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::run, "MspArmListener");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        // Close from this thread to break the blocking receive() immediately; the worker's own
        // reads then throw and it exits. Don't join() — callers are on the UI thread.
        DatagramSocket s = socket;
        if (s != null) s.close();
        thread = null;
        lastArmed = null;
    }

    /** Last known arm state, or null if no MSP_STATUS has been seen yet. */
    public Boolean isArmed() { return lastArmed; }

    private void run() {
        byte[] buf = new byte[2048];
        while (running) {
            try {
                if (socket == null || socket.isClosed()) {
                    socket = new DatagramSocket(port);
                    socket.setSoTimeout(1000);   // so `running` is re-checked ~1 Hz
                    Log.i(TAG, "listening for MSP on UDP " + port);
                }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                parse(buf, p.getLength());
            } catch (SocketTimeoutException ignored) {
                // idle — expected whenever the FC/msposd isn't forwarding
            } catch (Exception e) {
                if (!running) break;
                // Most likely the port is already bound (e.g. the legacy MAVLink listener also
                // uses 14550) or the interface went away on a link drop. Back off and retry rather
                // than dying permanently, since the link comes and goes.
                Log.w(TAG, "MSP socket error (" + e.getMessage() + ") — retrying in 2s");
                DatagramSocket s = socket;
                if (s != null) s.close();
                socket = null;
                try { Thread.sleep(2000); } catch (InterruptedException ie) { break; }
            }
        }
        DatagramSocket s = socket;
        if (s != null) s.close();
        socket = null;
        Log.i(TAG, "MSP listener stopped");
    }

    /**
     * Scan a datagram for MSP v1 frames and act on MSP_STATUS.
     *
     * <p>msposd aggregates several MSP frames per datagram, so this walks the whole buffer instead
     * of assuming one frame. Frames are not reassembled across datagrams: MSP_STATUS is re-sent
     * continuously, so dropping a straddling frame costs at most one sample, and carrying a
     * partial-frame buffer would risk resyncing onto payload bytes that happen to spell "$M".
     */
    private void parse(byte[] b, int len) {
        int i = 0;
        while (i + 5 <= len) {
            // MSP v1: '$' 'M' <dir> <payloadLen> <cmd> <payload...> <crc>. Direction '>' is
            // FC->host; '<' is host->FC (our own requests echoed back), which we ignore.
            if (b[i] != '$' || b[i + 1] != 'M' || b[i + 2] != '>') { i++; continue; }
            int payloadLen = b[i + 3] & 0xFF;
            int cmd = b[i + 4] & 0xFF;
            int payloadStart = i + 5;
            if (payloadStart + payloadLen + 1 > len) break;   // truncated — stop, don't guess

            // XOR checksum over len|cmd|payload guards against resyncing mid-payload.
            int crc = payloadLen ^ cmd;
            for (int k = 0; k < payloadLen; k++) crc ^= (b[payloadStart + k] & 0xFF);
            if ((crc & 0xFF) == (b[payloadStart + payloadLen] & 0xFF)) {
                if ((cmd == MSP_STATUS || cmd == MSP_STATUS_EX) && payloadLen >= 10) {
                    handleStatus(b, payloadStart);
                }
                i = payloadStart + payloadLen + 1;
            } else {
                i++;   // bad CRC: treat as a false "$M>" match and keep scanning
            }
        }
    }

    /**
     * MSP_STATUS / MSP_STATUS_EX share a prefix: u16 cycleTime, u16 i2cErrorCount, u16 sensor,
     * u32 flightModeFlags. Bit 0 of flightModeFlags is Betaflight box id 0 = ARM.
     *
     * <p>Verified against the live FC: flags read 0x00000104 while disarmed, i.e. bits 2 and 8 —
     * and box id 2 is HORIZON, which matched the OSD's "HOR" mode field at the same moment. That
     * cross-check is what confirms this offset and bit numbering are right.
     */
    private void handleStatus(byte[] b, int payloadStart) {
        int o = payloadStart + 6;
        long flags = ((long) (b[o] & 0xFF))
                | ((long) (b[o + 1] & 0xFF) << 8)
                | ((long) (b[o + 2] & 0xFF) << 16)
                | ((long) (b[o + 3] & 0xFF) << 24);
        boolean armed = (flags & 1L) != 0;
        if (lastArmed == null || lastArmed != armed) {
            Log.i(TAG, (lastArmed == null ? "initial" : "edge") + " arm state: "
                    + (armed ? "ARMED" : "DISARMED") + String.format(" (flags=0x%08x)", flags));
            boolean first = lastArmed == null;
            lastArmed = armed;
            // Fire on the first sample too: if the app starts while already armed we still want
            // recording to begin.
            if (!first || armed) callback.onArmChanged(armed);
        }
    }
}
