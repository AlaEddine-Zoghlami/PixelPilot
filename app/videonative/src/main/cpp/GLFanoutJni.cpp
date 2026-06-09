//
// GLFanoutJni.cpp — JNI bridge: GLFanoutManager (Java) <-> GLFanoutRenderer (native).
// See GLFanoutRenderer.h / GLFanoutManager.java for the design.
//
#include <jni.h>
#include <android/native_window_jni.h>
#include <android/bitmap.h>
#include "GLFanoutRenderer.h"

#define R(h) reinterpret_cast<GLFanoutRenderer*>(h)

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

JNIEXPORT void JNICALL
Java_com_openipc_videonative_GLFanoutManager_nativeRelease(JNIEnv*, jobject, jlong h) {
    if (h) delete R(h);
}

}  // extern "C"
