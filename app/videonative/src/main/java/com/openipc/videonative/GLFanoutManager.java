package com.openipc.videonative;

import android.graphics.SurfaceTexture;
import android.view.Surface;

/**
 * GLFanoutManager — Java half of the GL fan-out (pairs with native GLFanoutRenderer.h).
 *
 * The decoder renders ONE frame into the SurfaceTexture this class owns; the native EGL/GL
 * pass then fans that single frame out to BOTH the display Surface and the DVR encoder
 * Surface. This is the master fix: shared decode (perf) + MediaTek HW render via
 * SurfaceTexture (drops the SW-HEVC fallback + fixes 10-bit) + OSD-composite DVR.
 *
 * LIFECYCLE (focused-session wiring, NOT yet hooked into VideoDecoder):
 *   1. nativeInit(displaySurface) -> native creates the EGL ctx + OES texture, returns the
 *      GL texture id.
 *   2. new SurfaceTexture(texId); setOnFrameAvailableListener(this).
 *   3. inputSurface() = new Surface(surfaceTexture) -> hand THIS to the decoder instead of
 *      the SurfaceView surface (the "repoint" — the change that turns it into a fan-out).
 *   4. onFrameAvailable -> updateTexImage + getTransformMatrix -> nativeRenderFrame(matrix).
 *
 * THREADING: the SurfaceTexture must be created on the thread that owns the GL context, and
 * updateTexImage()/onFrameAvailable run there too — so this is driven from the native GL
 * thread (the verify-on-device detail to get right).
 *
 * STATUS: SCAFFOLD. Native methods are declared; their JNI bodies + the VideoDecoder repoint
 * + on-device verification are the remaining integration steps. Do NOT wire into the live
 * decode path until the full loop renders on the Oppo (then delete the MediaTek SW fallback).
 */
public class GLFanoutManager implements SurfaceTexture.OnFrameAvailableListener {

    static { System.loadLibrary("VideoNative"); }   // same lib as VideoDecoder/VideoPlayer

    private long           nativeHandle = 0;
    private SurfaceTexture surfaceTexture;
    private Surface        inputSurface;
    private final float[]  texMatrix = new float[16];

    /** Create the EGL context bound to the display, build the SurfaceTexture the decoder
     *  will render into. @return true on success. */
    public synchronized boolean init(Surface displaySurface) {
        nativeHandle = nativeInit(displaySurface);
        if (nativeHandle == 0) return false;
        int texId = nativeOesTexture(nativeHandle);
        surfaceTexture = new SurfaceTexture(texId);
        surfaceTexture.setOnFrameAvailableListener(this);
        inputSurface = new Surface(surfaceTexture);
        return true;
    }

    /** The Surface to give the decoder INSTEAD of the SurfaceView's surface (the repoint). */
    public Surface inputSurface() { return inputSurface; }

    /** Enable/disable DVR recording: pass the encoder's input Surface (or null to stop). */
    public synchronized void setEncoderSurface(Surface encoderSurface) {
        if (nativeHandle != 0) nativeSetEncoderSurface(nativeHandle, encoderSurface);
    }

    public synchronized void setRecordOsd(boolean on) {
        if (nativeHandle != 0) nativeSetRecordOsd(nativeHandle, on);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        synchronized (this) {
            if (nativeHandle == 0) return;
            st.updateTexImage();              // pulls the decoded frame into the OES texture
            st.getTransformMatrix(texMatrix);
            nativeRenderFrame(nativeHandle, texMatrix);   // fan out: display + encoder
        }
    }

    public synchronized void release() {
        if (inputSurface != null)   { inputSurface.release();   inputSurface = null; }
        if (surfaceTexture != null) { surfaceTexture.release(); surfaceTexture = null; }
        if (nativeHandle != 0)      { nativeRelease(nativeHandle); nativeHandle = 0; }
    }

    // --- JNI (bodies TODO in the focused integration session) -------------------------------
    private native long nativeInit(Surface displaySurface);
    private native int  nativeOesTexture(long handle);
    private native void nativeSetEncoderSurface(long handle, Surface encoderSurface);
    private native void nativeSetRecordOsd(long handle, boolean on);
    private native void nativeRenderFrame(long handle, float[] texMatrix);
    private native void nativeRelease(long handle);
}
