//
// GLFanoutRenderer.h — ONE decoded GS stream -> display + DVR (the "GL fan-out").
//
// WHY (user-specified design): instead of two decodes (one for the SurfaceView, one for
// the DVR) or a screen-capture, the HW decoder renders ONCE into a SurfaceTexture, and a
// GL pass fans that single frame out to BOTH the display Surface AND the DVR encoder's
// input Surface. Four wins in one feature:
//   1. PERF      — one decode shared between live view + recording.
//   2. MTK RENDER— the MediaTek HEVC HW decoder renders BLACK to a *direct* SurfaceView but
//                  correctly to a SurfaceTexture->GL path. So this lets us DROP the SW-HEVC
//                  fallback in VideoDecoder.cpp (see that file's MediaTek workaround block).
//   3. 10-BIT    — with HW decode restored, HEVC Main10 works for free (no SW penalty).
//   4. OSD-DVR   — when recordOsd is on, the OSD layer is drawn as a texture in the GL pass
//                  so the recording is video+overlay; off -> clean video.
//
// PIPELINE:
//   AMediaCodec decoder --(render=true)--> SurfaceTexture (OES external tex)
//        GL pass (this class), per frame-available:
//           updateTexImage()
//           draw OES tex -> EGL window surface over the display SurfaceView   [eglSwapBuffers]
//           if recording: draw OES tex (+ OSD tex if recordOsd) -> EGL surface
//                         over the encoder input Surface                       [eglSwapBuffers]
//   encoder output -> minimp4 (existing VideoPlayer DVR writer / OnEncodedNalu)
//
// STATUS: SCAFFOLD. EGL/GLES core below. INTEGRATION REMAINING (the invasive part, do in a
// focused pass with build->flash->verify):
//   (a) VideoDecoder: configure decoder output to THIS->inputSurface() instead of the
//       SurfaceView's ANativeWindow (the one-line repoint that makes it a fan-out).
//   (b) wire onFrameAvailable from the SurfaceTexture (JNI callback) -> renderFrame().
//   (c) encoder + OSD-texture upload (reuse DvrTranscoder's encoder setup; OSD bitmap from
//       the Java OSD layer via a shared texture).
//   (d) after it renders on the Oppo, DELETE the MediaTek SW-HEVC fallback in VideoDecoder.cpp.
//
#ifndef PIXELPILOT_GLFANOUTRENDERER_H
#define PIXELPILOT_GLFANOUTRENDERER_H

#include <android/log.h>
#include <android/native_window.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <atomic>

class GLFanoutRenderer
{
  public:
    GLFanoutRenderer() = default;
    ~GLFanoutRenderer() { release(); }

    // Bind to the display SurfaceView's window. Creates the EGL context + the OES external
    // texture the decoder will render into. Returns the GL texture id for the SurfaceTexture.
    bool initDisplay(ANativeWindow* displayWindow)
    {
        display_ = displayWindow;
        egl_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (egl_ == EGL_NO_DISPLAY || !eglInitialize(egl_, nullptr, nullptr)) return fail("eglInitialize");
        const EGLint cfgAttr[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                                  EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                                  EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE};
        EGLint n = 0;
        if (!eglChooseConfig(egl_, cfgAttr, &cfg_, 1, &n) || n < 1) return fail("eglChooseConfig");
        const EGLint ctxAttr[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
        ctx_ = eglCreateContext(egl_, cfg_, EGL_NO_CONTEXT, ctxAttr);
        if (ctx_ == EGL_NO_CONTEXT) return fail("eglCreateContext");
        dispSurf_ = eglCreateWindowSurface(egl_, cfg_, display_, nullptr);
        if (dispSurf_ == EGL_NO_SURFACE) return fail("eglCreateWindowSurface(display)");
        if (!eglMakeCurrent(egl_, dispSurf_, dispSurf_, ctx_)) return fail("eglMakeCurrent");
        if (!buildProgram()) return false;
        glGenTextures(1, &oesTex_);          // GL_TEXTURE_EXTERNAL_OES, fed by SurfaceTexture
        ready_.store(true);
        return true;
    }

    GLuint oesTexture() const { return oesTex_; }

    // The encoder's input Surface (from MediaCodec createInputSurface), so the recording
    // shares the SAME decoded frame. Pass nullptr to stop recording.
    void setEncoderWindow(ANativeWindow* encWindow)
    {
        if (encSurf_ != EGL_NO_SURFACE) { eglDestroySurface(egl_, encSurf_); encSurf_ = EGL_NO_SURFACE; }
        enc_ = encWindow;
        if (enc_) encSurf_ = eglCreateWindowSurface(egl_, cfg_, enc_, nullptr);
    }

    void setRecordOsd(bool on) { recordOsd_.store(on); }
    void setOsdTexture(GLuint tex) { osdTex_ = tex; }   // RGBA texture of the OSD layer

    // Called per decoded frame (after SurfaceTexture.updateTexImage on the Java/JNI side,
    // which fills oesTex_). texMatrix = the 4x4 SurfaceTexture transform.
    void renderFrame(const float texMatrix[16])
    {
        if (!ready_.load()) return;
        // 1. Display.
        eglMakeCurrent(egl_, dispSurf_, dispSurf_, ctx_);
        drawOes(texMatrix, /*withOsd=*/false);
        eglSwapBuffers(egl_, dispSurf_);
        // 2. DVR encoder (if recording) — same frame, optional OSD composite.
        if (encSurf_ != EGL_NO_SURFACE) {
            eglMakeCurrent(egl_, encSurf_, encSurf_, ctx_);
            drawOes(texMatrix, /*withOsd=*/recordOsd_.load());
            eglSwapBuffers(egl_, encSurf_);
        }
    }

    void release()
    {
        ready_.store(false);
        if (egl_ != EGL_NO_DISPLAY) {
            eglMakeCurrent(egl_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (encSurf_  != EGL_NO_SURFACE) eglDestroySurface(egl_, encSurf_);
            if (dispSurf_ != EGL_NO_SURFACE) eglDestroySurface(egl_, dispSurf_);
            if (ctx_      != EGL_NO_CONTEXT) eglDestroyContext(egl_, ctx_);
            eglTerminate(egl_);
        }
        egl_ = EGL_NO_DISPLAY; ctx_ = EGL_NO_CONTEXT; dispSurf_ = encSurf_ = EGL_NO_SURFACE;
    }

  private:
    bool fail(const char* w) { __android_log_print(ANDROID_LOG_ERROR, "GLFanout", "%s failed", w); return false; }

    bool buildProgram()
    {
        // OES external-texture sampler (the SurfaceTexture format). TODO: blend osdTex_ on top
        // when withOsd — a second textured quad with alpha. Scaffold draws the video only.
        static const char* VS =
            "attribute vec4 aPos; attribute vec2 aUV; uniform mat4 uTex;"
            "varying vec2 vUV; void main(){ vUV=(uTex*vec4(aUV,0.,1.)).xy; gl_Position=aPos; }";
        static const char* FS =
            "#extension GL_OES_EGL_image_external : require\n"
            "precision mediump float; varying vec2 vUV; uniform samplerExternalOES uTexOes;"
            "void main(){ gl_FragColor = texture2D(uTexOes, vUV); }";
        prog_ = link(VS, FS);
        return prog_ != 0;
    }
    GLuint link(const char* vs, const char* fs)
    {
        auto sh = [](GLenum t, const char* s){ GLuint x=glCreateShader(t); glShaderSource(x,1,&s,nullptr); glCompileShader(x); return x; };
        GLuint v=sh(GL_VERTEX_SHADER,vs), f=sh(GL_FRAGMENT_SHADER,fs), p=glCreateProgram();
        glAttachShader(p,v); glAttachShader(p,f); glLinkProgram(p);
        GLint ok=0; glGetProgramiv(p,GL_LINK_STATUS,&ok);
        return ok ? p : 0;
    }
    void drawOes(const float texMatrix[16], bool withOsd)
    {
        // Scaffold: full-screen quad sampling oesTex_ with texMatrix. The encoder + OSD-blend
        // (withOsd) wiring is the integration step — see header TODOs.
        glUseProgram(prog_);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, oesTex_);
        glUniformMatrix4fv(glGetUniformLocation(prog_, "uTex"), 1, GL_FALSE, texMatrix);
        // TODO: bind the fullscreen-quad VBO + draw; if withOsd, blend osdTex_ quad on top.
        (void) withOsd;
    }

    ANativeWindow* display_ = nullptr;
    ANativeWindow* enc_     = nullptr;
    EGLDisplay egl_      = EGL_NO_DISPLAY;
    EGLContext ctx_      = EGL_NO_CONTEXT;
    EGLConfig  cfg_      = nullptr;
    EGLSurface dispSurf_ = EGL_NO_SURFACE;
    EGLSurface encSurf_  = EGL_NO_SURFACE;
    GLuint     prog_ = 0, oesTex_ = 0, osdTex_ = 0;
    std::atomic<bool> ready_{false};
    std::atomic<bool> recordOsd_{false};
};

#endif  // PIXELPILOT_GLFANOUTRENDERER_H
