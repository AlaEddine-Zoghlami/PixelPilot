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
    private static final int SR = 32000;          // EdgeTX pack native rate

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

    /** Decode a WAV asset to raw PCM (skip the header), cache it. Null if missing/bad. */
    private byte[] load(String name) {
        byte[] c = cache.get(name);
        if (c != null) return c.length == 0 ? null : c;
        try (InputStream is = assets.open(dir + "/" + name)) {
            byte[] all = readAll(is);
            int off = pcmDataOffset(all);
            if (off < 0 || off >= all.length) { cache.put(name, new byte[0]); return null; }
            byte[] pcm = new byte[all.length - off];
            System.arraycopy(all, off, pcm, 0, pcm.length);
            cache.put(name, pcm);
            return pcm;
        } catch (Exception e) {
            Log.w(TAG, "missing clip " + name + " (" + e.getMessage() + ")");
            cache.put(name, new byte[0]);           // negative-cache: warn once
            return null;
        }
    }

    /** Find the 'data' chunk offset in a RIFF/WAVE file (usually 44, but be robust). */
    private static int pcmDataOffset(byte[] b) {
        if (b.length < 12 || b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F') return -1;
        int i = 12;
        while (i + 8 <= b.length) {
            int size = (b[i+4] & 0xFF) | ((b[i+5] & 0xFF) << 8) | ((b[i+6] & 0xFF) << 16) | ((b[i+7] & 0xFF) << 24);
            if (b[i] == 'd' && b[i+1] == 'a' && b[i+2] == 't' && b[i+3] == 'a') return i + 8;
            i += 8 + size + (size & 1);
        }
        return -1;
    }

    private static byte[] readAll(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
