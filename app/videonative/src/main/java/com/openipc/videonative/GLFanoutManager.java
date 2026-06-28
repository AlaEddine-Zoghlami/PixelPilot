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
    private final java.util.concurrent.atomic.AtomicInteger pendingFrames = new java.util.concurrent.atomic.AtomicInteger(0);
    private final Runnable renderTask = new Runnable() {
        @Override
        public void run() {
            if (nativeHandle == 0 || surfaceTexture == null) return;
            // Drain ALL pending frames, render only the freshest (FPV skip-to-latest).
            // updateTexImage blocks until a frame is available; each call consumes one.
            // The pendingFrames counter tells us how many onFrameAvailable callbacks
            // fired before this task ran — drain exactly that many.
            // Claim ALL pending frames AND reset the counter to 0 atomically. Critical:
            // onFrameAvailable only re-posts renderTask on the 0->1 edge, so the counter
            // MUST return to 0 every pass or the gate never re-arms. The old code
            // decremented per successful updateTexImage and `break` on exception left the
            // counter stuck >0 forever -> renderTask never posted again -> SurfaceTexture
            // buffer queue fills -> decoder blocks -> that eye freezes permanently (the
            // "worked then froze" VR symptom). getAndSet(0) re-arms the gate regardless.
            int toDrain = pendingFrames.getAndSet(0);
            while (toDrain > 0) {
                try {
                    surfaceTexture.updateTexImage();
                } catch (Exception e) {
                    break; // counter already reset; the next frame re-posts renderTask
                }
                toDrain--;
            }
            surfaceTexture.getTransformMatrix(texMatrix);
            nativeRenderFrame(nativeHandle, texMatrix);
        }
    };

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
                // deliver onFrameAvailable to the GL thread so render runs where the ctx lives
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

    @Override
    public void onFrameAvailable(SurfaceTexture st) {   // runs on the GL thread (glHandler)
        // Coalesce: N onFrameAvailable callbacks → 1 render of the newest frame.
        // Each callback increments the counter; only the first posts renderTask.
        // renderTask drains all pending frames and renders only the last → no
        // SurfaceTexture FIFO backlog → display stays real-time regardless of
        // source rate (120, 60, etc.).
        if (pendingFrames.incrementAndGet() == 1) {
            glHandler.post(renderTask);
        }
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
    private native void nativeSetRecordOsd(long handle, boolean on);
    private native void nativeUpdateOsd(long handle, Bitmap osd);
    private native void nativeRenderFrame(long handle, float[] texMatrix);
    private native void nativeRelease(long handle);
    private native void nativeStartDvr(long handle, int fd, int w, int h, int fps, int bitrate, boolean fmp4, boolean h265);
    private native void nativeStopDvr(long handle);
    private native void nativeStartDvrRaw(long handle, int fd, int w, int h, int fps, int bitrate, boolean fmp4);
    private native void nativeStopDvrRaw(long handle);
}
