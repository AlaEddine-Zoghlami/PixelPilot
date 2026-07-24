package com.openipc.videonative;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.util.concurrent.CountDownLatch;

/**
 * GLFanoutManager — Java half of the GL fan-out (pairs with native GLFanoutRenderer.h).
 *
 * The decoder renders ONE frame into the SurfaceTexture this class owns; the native EGL/GL
 * pass then fans that single frame out to BOTH the display Surface and the DVR encoder
 * Surface. Shared decode (perf) + SurfaceTexture render (fixes MediaTek/emulator direct-render
 * + 10-bit) + OSD-composite DVR.
 *
 * THREADING: EGL context, SurfaceTexture creation, updateTexImage(), and the native render
 * MUST all run on one thread (the GL thread) — eglMakeCurrent binds the context to a thread.
 * So this owns a HandlerThread: nativeInit + the SurfaceTexture are created on it via post(),
 * the OnFrameAvailable callback is delivered to its Handler, and every native call hops onto
 * it. The decoder (any thread) just renders into inputSurface(); the GL thread does the rest.
 *
 * LIFECYCLE:
 *   1. init(displaySurface): on the GL thread — nativeInit (EGL ctx + OES tex), new
 *      SurfaceTexture(texId), setOnFrameAvailableListener(this, glHandler), inputSurface.
 *   2. Hand inputSurface() to the decoder INSTEAD of the SurfaceView surface (the repoint).
 *   3. onFrameAvailable (GL thread) -> updateTexImage + getTransformMatrix -> nativeRenderFrame.
 */
public class GLFanoutManager implements SurfaceTexture.OnFrameAvailableListener {

    static { System.loadLibrary("VideoNative"); }   // same lib as VideoDecoder/VideoPlayer

    private long           nativeHandle = 0;
    private SurfaceTexture surfaceTexture;
    private Surface        inputSurface;
    private final float[]  texMatrix = new float[16];
    private HandlerThread  glThread;
    private Handler        glHandler;
    // Render throttle: the display refreshes at its own fixed rate. Rendering faster fills
    // the BLAST buffer queue → NO_BUFFER_AVAILABLE → renderFps collapses to 0 after ~30s.
    // updateTexImage() drains every frame (releases SurfaceTexture buffers so the decoder
    // never blocks), but eglSwapBuffers is called at most once per display refresh.
    // Display throttle: eglSwapBuffers on the display is rate-limited to the display's refresh
    // rate so BLAST buffers don't exhaust. But the DVR ENCODER must still receive every frame at
    // the decode rate — otherwise its timestamps drift (encoder configured for 90fps, only gets
    // 60fps → recording plays progressively slower, the "recording fps is wrong the longer the
    // video" bug). nativeRenderFrameEncodeOnly runs the encode pass (eglSwapBuffers on encoder
    // surface) but SKIPS eglSwapBuffers on the display surface — so the encoder stays in sync
    // with the source while the display self-regulates at its own refresh rate.
    //
    // The interval MUST match the display's actual negotiated refresh rate, not an assumed
    // constant: on a 120Hz panel a hardcoded 60fps cap here means the display only gets a fresh
    // buffer on roughly every other vsync, at whatever phase the free-running decode arrival
    // happens to land in relative to that cap -- a beat/drift pattern between the two rates that
    // reads as judder even though both the stream and the panel are genuinely ~120fps.
    // VideoActivity calls setDisplayRefreshRateHz() with the mode it actually negotiated
    // (Display.getSupportedModes()) once the window's attributes are applied. Default (60fps)
    // only applies before that call lands or if it's never called.
    private long lastDisplayNs = 0;
    private static volatile long DISPLAY_INTERVAL_NS = 16_666_667L; // 60fps fallback until VideoActivity reports the real rate

    /** Called once VideoActivity knows the display's actual negotiated refresh rate, so the
     *  display-swap throttle matches the real panel instead of an assumed 60Hz. */
    public static void setDisplayRefreshRateHz(float hz) {
        if (hz < 20f || hz > 1000f) return; // sanity-guard against a bogus/unavailable reading
        DISPLAY_INTERVAL_NS = (long) (1_000_000_000L / hz);
    }

    // Stage-timing instrumentation (temporary, for localizing the multi-second BLAST
    // "didn't commit buffer" stalls seen at high throughput): wraps each of the three
    // native/framework calls this callback makes and logs whichever one actually blocks.
    // Threshold well above any normal per-call cost (sub-ms to a couple ms) so only a
    // genuine stall trips it, not routine jitter.
    private static final long STAGE_WARN_NS = 30_000_000L; // 30ms

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        if (nativeHandle == 0 || surfaceTexture == null) return;
        try {
            long t0 = System.nanoTime();
            surfaceTexture.updateTexImage();   // always drain to release decoder buffers
            long t1 = System.nanoTime();
            surfaceTexture.getTransformMatrix(texMatrix);
            long now = t1;
            long t2, t3;
            boolean didSwap = now - lastDisplayNs >= DISPLAY_INTERVAL_NS;
            if (!didSwap) {
                // Encode-only: feed the DVR at full rate, don't swap to display
                t2 = System.nanoTime();
                nativeRenderFrameEncodeOnly(nativeHandle, texMatrix);
                t3 = System.nanoTime();
            } else {
                lastDisplayNs = now;
                t2 = System.nanoTime();
                nativeRenderFrame(nativeHandle, texMatrix);
                t3 = System.nanoTime();
            }
            long dUpdate = t1 - t0, dRender = t3 - t2;
            if (dUpdate > STAGE_WARN_NS || dRender > STAGE_WARN_NS) {
                android.util.Log.w("GLFanoutStage",
                    "slow stage: updateTexImage=" + (dUpdate / 1_000_000) + "ms render="
                    + (dRender / 1_000_000) + "ms (display=" + (didSwap ? "swap" : "skip") + ")");
            }
        } catch (Exception e) { /* SurfaceTexture released — ignore */ }
    }

    /** Create the GL thread + EGL context bound to the display, build the SurfaceTexture the
     *  decoder will render into. Blocks until set up. @return true on success. */
    public boolean init(final Surface displaySurface) {
        glThread = new HandlerThread("GLFanout");
        glThread.start();
        glHandler = new Handler(glThread.getLooper());
        final boolean[] ok = {false};
        final CountDownLatch latch = new CountDownLatch(1);
        glHandler.post(() -> {
            nativeHandle = nativeInit(displaySurface);
            if (nativeHandle != 0) {
                int texId = nativeOesTexture(nativeHandle);
                surfaceTexture = new SurfaceTexture(texId);
                // onFrameAvailable fires on glHandler thread — render DIRECTLY, no post()
                surfaceTexture.setOnFrameAvailableListener(this, glHandler);
                inputSurface = new Surface(surfaceTexture);
                ok[0] = true;
            }
            latch.countDown();
        });
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (!ok[0]) { releaseThread(); }
        return ok[0];
    }

    /** The Surface to give the decoder INSTEAD of the SurfaceView's surface (the repoint). */
    public Surface inputSurface() { return inputSurface; }

    /** Enable/disable DVR recording: pass the encoder's input Surface (or null to stop). */
    public void setEncoderSurface(final Surface encoderSurface) {
        if (glHandler != null) glHandler.post(() -> {
            if (nativeHandle != 0) nativeSetEncoderSurface(nativeHandle, encoderSurface);
        });
    }

    /** VR: render the single decode to a SECOND display surface (the other eye) too — one decode,
     *  two eyes. Pass null to detach. Runs on the GL thread (EGL surface create/destroy). */
    public void setSecondDisplay(final Surface displaySurface2) {
        if (glHandler != null) glHandler.post(() -> {
            if (nativeHandle != 0) nativeSetDisplay2(nativeHandle, displaySurface2);
        });
    }

    public void setRecordOsd(final boolean on) {
        if (glHandler != null) glHandler.post(() -> {
            if (nativeHandle != 0) nativeSetRecordOsd(nativeHandle, on);
        });
    }

    /** Upload the captured OSD layer (an RGBA_8888 Bitmap of the overlay View) to the GPU so the
     *  DVR pass blends it. Call when the OSD changes; the upload runs on the GL thread, so the
     *  caller must keep the Bitmap valid (not recycle it) until this returns control. */
    public void updateOsd(final Bitmap osd) {
        if (glHandler != null && osd != null) glHandler.post(() -> {
            if (nativeHandle != 0) nativeUpdateOsd(nativeHandle, osd);
        });
    }

    /** Start the GL fan-out DVR: the shared decode is re-encoded (with the OSD composited when
     *  recordOsd) into the .mp4 on the given fd. Runs on the GL thread (EGL surface creation). */
    public void startDvr(final int fd, final int w, final int h, final int fps, final int bitrate,
                         final boolean fmp4, final boolean h265, final boolean recordOsd, final Bitmap osd) {
        if (glHandler == null) return;
        glHandler.post(() -> {
            if (nativeHandle == 0) return;
            nativeSetRecordOsd(nativeHandle, recordOsd);
            if (recordOsd && osd != null) nativeUpdateOsd(nativeHandle, osd);
            nativeStartDvr(nativeHandle, fd, w, h, fps, bitrate, fmp4, h265);
        });
    }

    public void stopDvr() {
        if (glHandler != null) glHandler.post(() -> {
            if (nativeHandle != 0) nativeStopDvr(nativeHandle);
        });
    }

    /** Secondary clean (raw) DVR stream, parallel to startDvr's OSD stream — for Raw+OSD mode. */
    public void startDvrRaw(final int fd, final int w, final int h, final int fps, final int bitrate, final boolean fmp4) {
        if (glHandler == null) return;
        glHandler.post(() -> { if (nativeHandle != 0) nativeStartDvrRaw(nativeHandle, fd, w, h, fps, bitrate, fmp4); });
    }
    public void stopDvrRaw() {
        if (glHandler != null) glHandler.post(() -> { if (nativeHandle != 0) nativeStopDvrRaw(nativeHandle); });
    }

    public void release() {
        if (glHandler != null) {
            glHandler.post(() -> {
                if (inputSurface != null)   { inputSurface.release();   inputSurface = null; }
                if (surfaceTexture != null) { surfaceTexture.release(); surfaceTexture = null; }
                if (nativeHandle != 0)      { nativeRelease(nativeHandle); nativeHandle = 0; }
            });
        }
        releaseThread();
    }

    private void releaseThread() {
        if (glThread != null) { glThread.quitSafely(); glThread = null; glHandler = null; }
    }

    // --- JNI (implemented in GLFanoutJni.cpp) ----------------------------------------------
    private native long nativeInit(Surface displaySurface);
    private native int  nativeOesTexture(long handle);
    private native void nativeSetEncoderSurface(long handle, Surface encoderSurface);
    private native void nativeSetDisplay2(long handle, Surface displaySurface2);
    private native void nativeSetRecordOsd(long handle, boolean on);
    private native void nativeUpdateOsd(long handle, Bitmap osd);
    private native void nativeRenderFrame(long handle, float[] texMatrix);
    private native void nativeRenderFrameEncodeOnly(long handle, float[] texMatrix);
    private native void nativeRelease(long handle);
    private native void nativeStartDvr(long handle, int fd, int w, int h, int fps, int bitrate, boolean fmp4, boolean h265);
    private native void nativeStopDvr(long handle);
    private native void nativeStartDvrRaw(long handle, int fd, int w, int h, int fps, int bitrate, boolean fmp4);
    private native void nativeStopDvrRaw(long handle);
}
