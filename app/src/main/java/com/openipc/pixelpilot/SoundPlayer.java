package com.openipc.pixelpilot;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Queued WAV playback for the voice alerts — the Android counterpart of the Windows
 * SoundPlayer.h.
 *
 * <p>WHY AUDIOTRACK, not SoundPool/MediaPlayer. Announcements are CONCATENATED clips
 * ("battery" + "fifteen" + "volts"), so they must play back-to-back with no gap and in
 * order. AudioTrack in streaming mode lets a worker thread write each clip's PCM directly
 * into one continuous stream, which is exactly gapless concatenation — SoundPool is
 * fire-and-forget (overlaps) and MediaPlayer chaining leaves audible gaps.
 *
 * <p>Clips are the EdgeTX pack (mono 16-bit PCM WAV, 32 kHz), decoded once from assets and
 * cached as PCM byte[]. A whole playlist is queued as one unit so two events never interleave.
 */
public final class SoundPlayer {
    private static final String TAG = "SoundPlayer";
    // Fixed device/track rate. Clips are resampled to this from whatever rate their WAV header
    // declares (mirrors the Windows player's SDL_BuildAudioCVT), so a mixed-rate pack — or one that
    // isn't the rate we assumed — plays at correct pitch/speed instead of 2x-fast an octave high.
    private static final int SR = 32000;

    private final AssetManager assets;
    private final String dir;                     // "sounds"
    private final Map<String, byte[]> cache = new HashMap<>();
    private final BlockingQueue<List<String>> playlists = new ArrayBlockingQueue<>(8);
    private volatile boolean enabled = true;
    private volatile boolean running;
    private Thread worker;
    private AudioTrack track;

    public SoundPlayer(Context ctx, String assetDir) {
        this.assets = ctx.getAssets();
        this.dir = assetDir;
    }

    public synchronized void start() {
        if (running) return;
        int min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(SR)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(Math.max(min, SR))    // ~0.5 s
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track.play();
        running = true;
        worker = new Thread(this::run, "SoundPlayer");
        worker.setDaemon(true);
        worker.start();
        Log.i(TAG, "started (32 kHz mono), clips from assets/" + dir);
    }

    public synchronized void shutdown() {
        running = false;
        if (worker != null) worker.interrupt();
        if (track != null) { try { track.stop(); track.release(); } catch (Exception ignored) {} track = null; }
    }

    public void setEnabled(boolean on) {
        enabled = on;
        if (!on) playlists.clear();               // go quiet immediately on mute
    }
    public boolean enabled() { return enabled; }

    /** Queue a playlist (clip asset names, e.g. "battry.wav", "num/0015.wav"). */
    public void play(List<String> clips) {
        if (!enabled || !running || clips == null || clips.isEmpty()) return;
        // Drop rather than build a lengthening backlog if alerts outrun playback.
        if (playlists.remainingCapacity() == 0) return;
        playlists.offer(new ArrayList<>(clips));
    }

    private void run() {
        while (running) {
            List<String> pl;
            try { pl = playlists.take(); } catch (InterruptedException e) { break; }
            if (!enabled) continue;
            for (String clip : pl) {
                byte[] pcm = load(clip);
                if (pcm != null && track != null) track.write(pcm, 0, pcm.length);
            }
        }
    }

    /**
     * Decode a WAV asset to 16-bit mono PCM at {@link #SR}, resampling from the file's own rate, and
     * cache it. Mirrors the Windows player: read the clip's real format, convert to the device format.
     * Null if missing/bad.
     */
    private byte[] load(String name) {
        byte[] c = cache.get(name);
        if (c != null) return c.length == 0 ? null : c;
        try (InputStream is = assets.open(dir + "/" + name)) {
            byte[] all = readAll(is);
            Wav w = parseWav(all);
            if (w == null) { cache.put(name, new byte[0]); return null; }
            byte[] pcm = toMono16(all, w);
            if (w.rate != SR) pcm = resample16(pcm, w.rate, SR);
            cache.put(name, pcm);
            return pcm;
        } catch (Exception e) {
            Log.w(TAG, "missing clip " + name + " (" + e.getMessage() + ")");
            cache.put(name, new byte[0]);           // negative-cache: warn once
            return null;
        }
    }

    /** Parsed WAV geometry: PCM data window plus the source format we must convert from. */
    private static final class Wav { int off, len, rate, channels, bits; }

    /** Walk RIFF chunks for 'fmt ' (rate/channels/bits) and 'data' (offset/length). */
    private static Wav parseWav(byte[] b) {
        if (b.length < 12 || b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F'
                || b[8] != 'W' || b[9] != 'A' || b[10] != 'V' || b[11] != 'E') return null;
        Wav w = new Wav();
        int i = 12;
        while (i + 8 <= b.length) {
            int size = u32(b, i + 4);
            int body = i + 8;
            if (b[i] == 'f' && b[i+1] == 'm' && b[i+2] == 't' && b[i+3] == ' ' && body + 16 <= b.length) {
                w.channels = u16(b, body + 2);
                w.rate     = u32(b, body + 4);
                w.bits     = u16(b, body + 14);
            } else if (b[i] == 'd' && b[i+1] == 'a' && b[i+2] == 't' && b[i+3] == 'a') {
                w.off = body;
                w.len = Math.min(size, b.length - body);
            }
            if (size < 0) break;
            i = body + size + (size & 1);
        }
        // Only 16-bit PCM is supported (the whole pack is); anything else we can't safely play.
        if (w.rate <= 0 || w.bits != 16 || w.channels < 1 || w.len <= 0) return null;
        return w;
    }

    /** Downmix to mono 16-bit if needed; return a tightly-sized little-endian PCM buffer. */
    private static byte[] toMono16(byte[] b, Wav w) {
        if (w.channels == 1) {
            byte[] pcm = new byte[w.len & ~1];
            System.arraycopy(b, w.off, pcm, 0, pcm.length);
            return pcm;
        }
        int frame = 2 * w.channels;
        int frames = w.len / frame;
        byte[] out = new byte[frames * 2];
        for (int f = 0; f < frames; f++) {
            int acc = 0, base = w.off + f * frame;
            for (int ch = 0; ch < w.channels; ch++) acc += (short) (u16(b, base + ch * 2));
            int s = acc / w.channels;
            out[f * 2]     = (byte) (s & 0xFF);
            out[f * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        return out;
    }

    /** Linear-interpolate 16-bit mono PCM from srcRate to dstRate. */
    private static byte[] resample16(byte[] in, int srcRate, int dstRate) {
        int nIn = in.length / 2;
        if (nIn == 0) return in;
        long nOut = (long) nIn * dstRate / srcRate;
        byte[] out = new byte[(int) nOut * 2];
        for (int i = 0; i < nOut; i++) {
            double srcPos = (double) i * srcRate / dstRate;
            int i0 = (int) srcPos;
            int i1 = Math.min(i0 + 1, nIn - 1);
            double frac = srcPos - i0;
            int s0 = (short) ((in[i0*2] & 0xFF) | (in[i0*2+1] << 8));
            int s1 = (short) ((in[i1*2] & 0xFF) | (in[i1*2+1] << 8));
            int s = (int) Math.round(s0 + (s1 - s0) * frac);
            out[i*2]     = (byte) (s & 0xFF);
            out[i*2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        return out;
    }

    private static int u16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o+1] & 0xFF) << 8); }
    private static int u32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o+1] & 0xFF) << 8) | ((b[o+2] & 0xFF) << 16) | ((b[o+3] & 0xFF) << 24);
    }

    private static byte[] readAll(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
