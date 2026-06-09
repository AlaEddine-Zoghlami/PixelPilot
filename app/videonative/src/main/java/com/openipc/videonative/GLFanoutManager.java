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

    @Override
    public void onFrameAvailable(SurfaceTexture st) {   // runs on the GL thread (glHandler)
        if (nativeHandle == 0) return;
        st.updateTexImage();                  // pulls the decoded frame into the OES texture
        st.getTransformMatrix(texMatrix);
        nativeRenderFrame(nativeHandle, texMatrix);      // fan out: display + encoder
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
}
