//
// GLFanoutRenderer.h — ONE decoded GS stream -> display + DVR (the "GL fan-out").
//
// The HW decoder renders ONCE into a SurfaceTexture; a GL pass fans that single frame out to
// BOTH the display Surface AND the DVR encoder input Surface. Four wins: (1) PERF, one shared
// decode; (2) MTK RENDER, the MediaTek HEVC HW decoder renders black to a *direct* SurfaceView
// but correctly via SurfaceTexture->GL (-> drop the SW-HEVC fallback); (3) 10-BIT Main10 HW for
// free; (4) OSD-composite DVR.
//
// STATUS: EGL/GLES core + fullscreen-quad video render IMPLEMENTED (compiles). REMAINING
// (invasive, device-verify): (a) repoint VideoDecoder output to GLFanoutManager.inputSurface();
// (b) onFrameAvailable -> renderFrame() on the GL-context thread; (c) OSD-texture blend + encoder
// reuse from DvrTranscoder; (d) verify render on Oppo then DELETE the MediaTek SW-HEVC fallback.
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
#include <time.h>
#include "GLFanoutEncoder.h"

class GLFanoutRenderer
{
  public:
    GLFanoutRenderer() = default;
    ~GLFanoutRenderer() { release(); }

    bool initDisplay(ANativeWindow* displayWindow)
    {
        display_ = displayWindow;
        egl_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (egl_ == EGL_NO_DISPLAY || !eglInitialize(egl_, nullptr, nullptr)) return fail("eglInitialize");
        // EGL_RECORDABLE_ANDROID is REQUIRED so the same config can drive a MediaCodec input
        // surface (the DVR encoder) — without it the encoder errors and emits no output. Also
        // valid for the display window surface, so one config serves both.
        const EGLint cfgAttr[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                                  EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                                  EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
                                  EGL_RECORDABLE_ANDROID, 1, EGL_NONE};
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
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, oesTex_);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        ready_.store(true);
        return true;
    }

    GLuint oesTexture() const { return oesTex_; }

    void setEncoderWindow(ANativeWindow* encWindow)
    {
        if (encSurf_ != EGL_NO_SURFACE) { eglDestroySurface(egl_, encSurf_); encSurf_ = EGL_NO_SURFACE; }
        enc_ = encWindow;
        if (enc_) encSurf_ = eglCreateWindowSurface(egl_, cfg_, enc_, nullptr);
        __android_log_print(ANDROID_LOG_DEBUG, "GLFanoutDbg", "setEncoderWindow win=%p encSurf=%p err=0x%x",
                            (void*) enc_, (void*) encSurf_, enc_ ? eglGetError() : 0);
    }

    void setRecordOsd(bool on) { recordOsd_.store(on); }

    // DVR: create the surface-input encoder, point the fan-out's encoder pass at its input
    // surface, and route its NALUs to cb (the mp4 writer). The GL pass then composites the
    // video (+ OSD when recordOsd) into the recording. Call on the GL thread (eglCreateWindowSurface).
    bool startDvr(int w, int h, int fps, int bitrate, GLFanoutEncoder::OnEncodedNalu cb, bool h265 = false)
    {
        if (!encoder_.start(w, h, fps, bitrate, std::move(cb), h265)) return false;
        setEncoderWindow(encoder_.inputWindow());
        return true;
    }
    void stopDvr()
    {
        setEncoderWindow(nullptr);
        encoder_.stop();
    }
    // Upload the OSD overlay (RGBA pixels captured from the OSD View) into the 2D texture the
    // DVR pass alpha-blends. Lazy-creates the texture. MUST run on the GL-context thread.
    void updateOsd(const void* rgba, int w, int h)
    {
        if (osdTex_ == 0) {
            glGenTextures(1, &osdTex_);
            glBindTexture(GL_TEXTURE_2D, osdTex_);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        } else {
            glBindTexture(GL_TEXTURE_2D, osdTex_);
        }
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    }

    // Per decoded frame (after SurfaceTexture.updateTexImage on the JNI side fills oesTex_).
    // MUST run on the thread owning ctx_. texMatrix = the SurfaceTexture transform.
    void renderFrame(const float texMatrix[16])
    {
        if (!ready_.load()) return;
        eglMakeCurrent(egl_, dispSurf_, dispSurf_, ctx_);
        eglQuerySurface(egl_, dispSurf_, EGL_WIDTH, &vpW_);
        eglQuerySurface(egl_, dispSurf_, EGL_HEIGHT, &vpH_);
        drawOes(texMatrix, /*withOsd=*/false);
        eglSwapBuffers(egl_, dispSurf_);
        if (encSurf_ != EGL_NO_SURFACE) {
            eglMakeCurrent(egl_, encSurf_, encSurf_, ctx_);
            eglQuerySurface(egl_, encSurf_, EGL_WIDTH, &vpW_);
            eglQuerySurface(egl_, encSurf_, EGL_HEIGHT, &vpH_);
            drawOes(texMatrix, /*withOsd=*/recordOsd_.load());
            // MediaCodec surface-input encoders need a presentation timestamp per frame or they
            // emit NO output. Stamp from the monotonic clock before the swap.
            static auto eglPresTime = (EGLBoolean (*)(EGLDisplay, EGLSurface, EGLnsecsANDROID))
                                      eglGetProcAddress("eglPresentationTimeANDROID");
            struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
            if (eglPresTime) eglPresTime(egl_, encSurf_, (EGLnsecsANDROID) ts.tv_sec * 1000000000LL + ts.tv_nsec);
            EGLBoolean sw = eglSwapBuffers(egl_, encSurf_);
            static int rc = 0;
            if (rc < 4) __android_log_print(ANDROID_LOG_DEBUG, "GLFanoutDbg", "renderFrame->enc #%d vp=%dx%d swap=%d pres=%d",
                                            rc, vpW_, vpH_, (int) sw, (int) (eglPresTime != nullptr));
            rc++;
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
        static const char* VS =
            "attribute vec4 aPos; attribute vec2 aUV; uniform mat4 uTex;"
            "varying vec2 vUV; void main(){ vUV=(uTex*vec4(aUV,0.,1.)).xy; gl_Position=aPos; }";
        static const char* FS =
            "#extension GL_OES_EGL_image_external : require\n"
            "precision mediump float; varying vec2 vUV; uniform samplerExternalOES uTexOes;"
            "void main(){ gl_FragColor = texture2D(uTexOes, vUV); }";
        prog_ = link(VS, FS);
        if (!prog_) return fail("link program");
        aPos_    = glGetAttribLocation(prog_, "aPos");
        aUV_     = glGetAttribLocation(prog_, "aUV");
        uTex_    = glGetUniformLocation(prog_, "uTex");
        uTexOes_ = glGetUniformLocation(prog_, "uTexOes");
        // Fullscreen quad (triangle strip): interleaved pos.xy, uv.xy.
        static const float quad[] = {
            -1.f, -1.f, 0.f, 0.f,   1.f, -1.f, 1.f, 0.f,
            -1.f,  1.f, 0.f, 1.f,   1.f,  1.f, 1.f, 1.f};
        glGenBuffers(1, &vbo_);
        glBindBuffer(GL_ARRAY_BUFFER, vbo_);
        glBufferData(GL_ARRAY_BUFFER, sizeof(quad), quad, GL_STATIC_DRAW);

        // OSD overlay program: a plain 2D RGBA texture (the captured OSD layer), alpha-blended
        // over the video in the DVR/encoder pass when recordOsd is on. Reuses the same quad VBO.
        static const char* OVS =
            "attribute vec4 aPos; attribute vec2 aUV; varying vec2 vUV;"
            // Flip V: the captured OSD Bitmap is top-down, the quad UV is bottom-up -> the overlay
            // would otherwise composite upside-down (along the bottom instead of the top).
            "void main(){ vUV=vec2(aUV.x, 1.0-aUV.y); gl_Position=aPos; }";
        static const char* OFS =
            "precision mediump float; varying vec2 vUV; uniform sampler2D uOsd;"
            "void main(){ gl_FragColor = texture2D(uOsd, vUV); }";
        osdProg_ = link(OVS, OFS);
        if (osdProg_) {
            osdPos_    = glGetAttribLocation(osdProg_, "aPos");
            osdUV_     = glGetAttribLocation(osdProg_, "aUV");
            osdTexLoc_ = glGetUniformLocation(osdProg_, "uOsd");
        }
        return true;
    }
    GLuint link(const char* vs, const char* fs)
    {
        auto sh = [](GLenum t, const char* s){ GLuint x=glCreateShader(t); glShaderSource(x,1,&s,nullptr); glCompileShader(x); return x; };
        GLuint v=sh(GL_VERTEX_SHADER,vs), f=sh(GL_FRAGMENT_SHADER,fs), p=glCreateProgram();
        glAttachShader(p,v); glAttachShader(p,f); glLinkProgram(p);
        GLint ok=0; glGetProgramiv(p,GL_LINK_STATUS,&ok);
        glDeleteShader(v); glDeleteShader(f);
        return ok ? p : 0;
    }
    void drawOes(const float texMatrix[16], bool withOsd)
    {
        glViewport(0, 0, vpW_, vpH_);
        glClearColor(0.f, 0.f, 0.f, 1.f);
        glClear(GL_COLOR_BUFFER_BIT);
        glUseProgram(prog_);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, oesTex_);
        glUniform1i(uTexOes_, 0);
        glUniformMatrix4fv(uTex_, 1, GL_FALSE, texMatrix);
        glBindBuffer(GL_ARRAY_BUFFER, vbo_);
        glEnableVertexAttribArray(aPos_);
        glVertexAttribPointer(aPos_, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*) 0);
        glEnableVertexAttribArray(aUV_);
        glVertexAttribPointer(aUV_, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*) (2 * sizeof(float)));
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glDisableVertexAttribArray(aPos_);
        glDisableVertexAttribArray(aUV_);

        // OSD layer (DVR pass only, when recordOsd is on): alpha-blend the captured OSD texture
        // over the video so the recorded .mp4 carries the overlay. The display pass passes
        // withOsd=false, so the live view stays clean video — one decode, two composites.
        if (withOsd && osdTex_ != 0 && osdProg_ != 0)
        {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glUseProgram(osdProg_);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, osdTex_);
            glUniform1i(osdTexLoc_, 0);
            glBindBuffer(GL_ARRAY_BUFFER, vbo_);
            glEnableVertexAttribArray(osdPos_);
            glVertexAttribPointer(osdPos_, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*) 0);
            glEnableVertexAttribArray(osdUV_);
            glVertexAttribPointer(osdUV_, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*) (2 * sizeof(float)));
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            glDisableVertexAttribArray(osdPos_);
            glDisableVertexAttribArray(osdUV_);
            glDisable(GL_BLEND);
        }
    }

    ANativeWindow* display_ = nullptr;
    ANativeWindow* enc_     = nullptr;
    EGLDisplay egl_      = EGL_NO_DISPLAY;
    EGLContext ctx_      = EGL_NO_CONTEXT;
    EGLConfig  cfg_      = nullptr;
    EGLSurface dispSurf_ = EGL_NO_SURFACE;
    EGLSurface encSurf_  = EGL_NO_SURFACE;
    GLuint     prog_ = 0, osdProg_ = 0, oesTex_ = 0, osdTex_ = 0, vbo_ = 0;
    GLint      aPos_ = -1, aUV_ = -1, uTex_ = -1, uTexOes_ = -1;
    GLint      osdPos_ = -1, osdUV_ = -1, osdTexLoc_ = -1;
    EGLint     vpW_ = 0, vpH_ = 0;
    std::atomic<bool> ready_{false};
    std::atomic<bool> recordOsd_{false};
    GLFanoutEncoder   encoder_;
};

#endif  // PIXELPILOT_GLFANOUTRENDERER_H
