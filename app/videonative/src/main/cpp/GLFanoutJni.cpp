//
// GLFanoutJni.cpp — JNI bridge: GLFanoutManager (Java) <-> GLFanoutRenderer (native).
// See GLFanoutRenderer.h / GLFanoutManager.java for the design.
//
#include <jni.h>
#include <android/native_window_jni.h>
#include <android/bitmap.h>
#include "GLFanoutRenderer.h"

#define R(h) reinterpret_cast<GLFanoutRenderer*>(h)

// GL fan-out DVR mp4 writer — implemented in VideoPlayer.cpp, which owns the single minimp4
// MINIMP4_IMPLEMENTATION (its guard is disabled, so including minimp4.h here too would duplicate
// every MP4E_* symbol at link). The fan-out encoder's NALUs (video, + OSD when recordOsd) flow
// through these.
extern "C" void* glfanout_dvr_start(int fd, int w, int h, int fps, int fmp4, int h265);
extern "C" void  glfanout_dvr_write(void* dvr, const uint8_t* data, size_t size, int64_t ptsUs);
extern "C" void  glfanout_dvr_stop(void* dvr);
static void* g_glDvr    = nullptr;   // primary stream writer (Raw OR OSD)
static void* g_glDvrRaw = nullptr;   // secondary clean writer (Raw+OSD mode)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_openipc_videonative_GLFanoutManager_nativeInit(JNIEnv* env, jobject, jobject displaySurface) {
    if (displaySurface == nullptr) return 0;
    ANativeWindow* win = ANativeWindow_fromSurface(env, displaySurface);
    if (win == nullptr) return 0;
    auto* r = new GLFanoutRenderer();
    if (!r->initDisplay(win)) { delete r; ANativeWindow_release(win); return 0; }
    return reinterpret_cast<jlong>(r);
}

JNIEXPORT jint JNICALL
Java_com_openipc_videonative_GLFanoutManager_nativeOesTexture(JNIEnv*, jobject, jlong h) {
    return h ? (jint) R(h)->oesTexture() : 0;
}

JNIEXPORT void JNICALL
Java_com_openipc_videonative_GLFanoutManager_nativeSetEncoderSurface(JNIEnv* env, jobject, jlong h, jobject encSurface) {
    if (!h) return;
    ANativeWindow* w = encSurface ? ANativeWindow_fromSurface(env, encSurface) : nullptr;
    R(h)->setEncoderWindow(w);
}

// VR second eye: render the single decoded frame to a 2nd display surface too. Pass null to detach.
JNIEXPORT void JNICALL
Java_com_openipc_videonative_GLFanoutManager_nativeSetDisplay2(JNIEnv* env, jobject, jlong h, jobject surface2) {
    if (!h) return;
    ANativeWindow* w = surface2 ? ANativeWindow_fromSurface(env, surface2) : nullptr;
    R(h)->setDisplayWindow2(w);
}

JNIEXPORT void JNICALL
Java_com_openipc_videonative_GLFanoutManager_nativeSetRecordOsd(JNIEnv*, jobject, jlong h, jboolean on) {
    if (h) R(h)->setRecordOsd(on == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_openipc_videonative_GLFanoutManager_nativeUpdateOsd(JNIEnv* env, jobject, jlong h, jobject bitmap) {
    if (!h || bitmap == nullptr) return;
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return;
    R(h)->updateOsd(pixels, (int) info.width, (int) info.height);
    AndroidBitmap_unlockPixels(env, bitmap);
}

JNIEXPORT void JNICALL
Java_com_openipc_videonative_GLFanoutManager_nativeRenderFrame(JNIEnv* env, jobject, jlong h, jfloatArray jmat) {
    if (!h || jmat == nullptr) return;
    float m[16];
    env->GetFloatArrayRegion(jmat, 0, 16, m);
    R(h)->renderFrame(m);
}

// Encode-only: feeds the DVR encoder at the full decode rate (90/120fps) but SKIPS the
// display eglSwapBuffers. When the render throttle drops display frames to prevent BLAST
// exhaustion, this keeps the encoder timestamps in sync with the real frame cadence.
// Without it the encoder gets fewer frames than its configured fps → timestamps drift →
// recording plays progressively slower (the "recording fps wrong" bug).
JNIEXPORT void JNICALL
Java_com_openipc_videonative_GLFanoutManager_nativeRenderFrameEncodeOnly(JNIEnv* env, jobject, jlong h, jfloatArray jmat) {
    if (!h || jmat == nullptr) return;
    float m[16];
    env->GetFloatArrayRegion(jmat, 0, 16, m);
    R(h)->renderFrameEncodeOnly(m);
}

JNIEXPORT void JNICALL
Java_com_openipc_videonative_GLFanoutManager_nativeRelease(JNIEnv*, jobject, jlong h) {
    if (h) delete R(h);
}

JNIEXPORT void JNICALL
Java_com_openipc_videonative_GLFanoutManager_nativeStartDvr(
    JNIEnv*, jobject, jlong h, jint fd, jint w, jint hh, jint fps, jint bitrate, jboolean fmp4, jboolean h265) {
    if (!h || g_glDvr) return;
    g_glDvr = glfanout_dvr_start((int) fd, (int) w, (int) hh, (int) fps,
                                 fmp4 == JNI_TRUE ? 1 : 0, h265 == JNI_TRUE ? 1 : 0);
    if (!g_glDvr) return;
    // The cb runs on the encoder drain thread; the writer (g_glDvr) outlives it — stopDvr joins
    // the drain thread before glfanout_dvr_stop frees it.
    if (!R(h)->startDvr((int) w, (int) hh, (int) fps, (int) bitrate,
                         [](const uint8_t* d, size_t s, bool, int64_t pts) { glfanout_dvr_write(g_glDvr, d, s, pts); },
                         h265 == JNI_TRUE)) {
        glfanout_dvr_stop(g_glDvr); g_glDvr = nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_openipc_videonative_GLFanoutManager_nativeStopDvr(JNIEnv*, jobject, jlong h) {
    if (h) R(h)->stopDvr();   // flush EOS + join drain -> all NALUs written -> safe to free
    if (g_glDvr) { glfanout_dvr_stop(g_glDvr); g_glDvr = nullptr; }
}

// Secondary RAW stream (Raw+OSD): a parallel clean-video encoder/writer alongside the OSD one.
JNIEXPORT void JNICALL
Java_com_openipc_videonative_GLFanoutManager_nativeStartDvrRaw(
    JNIEnv*, jobject, jlong h, jint fd, jint w, jint hh, jint fps, jint bitrate, jboolean fmp4) {
    if (!h || g_glDvrRaw) return;
    g_glDvrRaw = glfanout_dvr_start((int) fd, (int) w, (int) hh, (int) fps, fmp4 == JNI_TRUE ? 1 : 0, 0);
    if (!g_glDvrRaw) return;
    if (!R(h)->startDvrRaw((int) w, (int) hh, (int) fps, (int) bitrate,
                            [](const uint8_t* d, size_t s, bool, int64_t pts) { glfanout_dvr_write(g_glDvrRaw, d, s, pts); }, false)) {
        glfanout_dvr_stop(g_glDvrRaw); g_glDvrRaw = nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_openipc_videonative_GLFanoutManager_nativeStopDvrRaw(JNIEnv*, jobject, jlong h) {
    if (h) R(h)->stopDvrRaw();
    if (g_glDvrRaw) { glfanout_dvr_stop(g_glDvrRaw); g_glDvrRaw = nullptr; }
}

}  // extern "C"
