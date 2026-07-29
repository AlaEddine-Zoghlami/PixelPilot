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
#include <cstdlib>
#include <sys/system_properties.h>
#include "GLFanoutEncoder.h"
#include "colortrans.h"

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
        // Pace display swaps to vsync. Works together with the frame gate in
        // GLFanoutManager.onFrameAvailable (one swap per refresh interval, minus a quarter-interval
        // of slack) — the gate decides WHICH frames to present, this makes each one land on its own
        // refresh.
        //
        // All four combinations were measured on device (90 fps stream, 90 Hz panel, MTK HW HEVC
        // reporting renderFps=90 discardFps=0), counting what actually reached the video
        // SurfaceView's BufferQueue:
        //   swapInterval 0 + exact gate  -> 57-59 fps, gaps 10/22/33 ms   (the original judder)
        //   swapInterval 1 + exact gate  -> 57-60 fps, gaps 10/22/33 ms
        //   swapInterval 0 + slack gate  -> 77-90 fps but UNSTABLE: a 121 ms spike and BLAST
        //                                   "didn't commit buffer" warnings — the free-running
        //                                   swap refills the queue faster than it drains
        //   swapInterval 1 + slack gate  -> 81-83 fps steady, max gap 22.5 ms, no BLAST warnings
        // Hence this pairing. It does cost at most one refresh of latency (11 ms at 90 Hz).
        eglSwapInterval(egl_, 1);
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

    // --- Display frame pacing: presentation timestamps on a Choreographer timebase ------------
    // Legs 1+2 of what Android Frame Pacing (Swappy) does, without leg 3 (sync fences) and
    // crucially WITHOUT Swappy's blocking swap. Swappy paces by blocking the render thread; ours
    // is the SurfaceTexture consumer thread, so blocking it backpressures the decoder and exhausts
    // the BLAST queue (measured: "NO_BUFFER_AVAILABLE", 3-9 s freezes). eglPresentationTimeANDROID
    // only ATTACHES a target time to the buffer and returns immediately, so it paces presentation
    // without ever stalling decode.
    //
    // What it fixes: with no timestamp, SurfaceFlinger latches whatever buffer is newest at each
    // vsync, so when two frames land in one interval the older is dropped and when none lands the
    // previous is held — the documented "frames skipping display cycles" case, and why the video
    // SurfaceView only received 57-60 of 90 decoded fps.

    /** Latest Choreographer vsync time (CLOCK_MONOTONIC ns, same timebase as System.nanoTime and
     *  as eglPresentationTimeANDROID). Fed ~once per refresh from GLFanoutManager. */
    void setVsyncNs(int64_t frameTimeNs) { vsyncNs_.store(frameTimeNs); }
    /** Display refresh period (ns) for the mode the app negotiated. */
    void setRefreshNs(uint64_t ns) { if (ns > 0) refreshNs_.store(ns); }

    void setEncoderWindow(ANativeWindow* encWindow)
    {
        if (encSurf_ != EGL_NO_SURFACE) { eglDestroySurface(egl_, encSurf_); encSurf_ = EGL_NO_SURFACE; }
        enc_ = encWindow;
        if (enc_) encSurf_ = eglCreateWindowSurface(egl_, cfg_, enc_, nullptr);
        __android_log_print(ANDROID_LOG_DEBUG, "GLFanoutDbg", "setEncoderWindow win=%p encSurf=%p err=0x%x",
                            (void*) enc_, (void*) encSurf_, enc_ ? eglGetError() : 0);
    }

    void setRecordOsd(bool on) { recordOsd_.store(on); }

    // VR: a second display window (the other eye). The single decoded frame is rendered to BOTH
    // eye surfaces in renderFrame -> one decode, two displays (no second HEVC decoder needed).
    // Call on the GL-context thread. Pass nullptr to detach the second eye.
    void setDisplayWindow2(ANativeWindow* win2)
    {
        if (dispSurf2_ != EGL_NO_SURFACE) { eglDestroySurface(egl_, dispSurf2_); dispSurf2_ = EGL_NO_SURFACE; }
        display2_ = win2;
        if (win2) dispSurf2_ = eglCreateWindowSurface(egl_, cfg_, win2, nullptr);
        __android_log_print(ANDROID_LOG_DEBUG, "GLFanoutDbg", "setDisplayWindow2 win=%p surf=%p err=0x%x",
                            (void*) win2, (void*) dispSurf2_, win2 ? eglGetError() : 0);
    }

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
    // Secondary RAW stream (Raw+OSD mode): a parallel clean-video encoder, rendered withOsd=false.
    bool startDvrRaw(int w, int h, int fps, int bitrate, GLFanoutEncoder::OnEncodedNalu cb, bool h265 = false)
    {
        if (!encoderRaw_.start(w, h, fps, bitrate, std::move(cb), h265)) return false;
        if (encSurfRaw_ != EGL_NO_SURFACE) { eglDestroySurface(egl_, encSurfRaw_); encSurfRaw_ = EGL_NO_SURFACE; }
        if (encoderRaw_.inputWindow()) encSurfRaw_ = eglCreateWindowSurface(egl_, cfg_, encoderRaw_.inputWindow(), nullptr);
        return encSurfRaw_ != EGL_NO_SURFACE;
    }
    void stopDvrRaw()
    {
        if (encSurfRaw_ != EGL_NO_SURFACE) { eglDestroySurface(egl_, encSurfRaw_); encSurfRaw_ = EGL_NO_SURFACE; }
        encoderRaw_.stop();
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
        osdTexW_ = w; osdTexH_ = h;   // for aspect-preserving composite
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
        stampDisplayPresentationTime();
        eglSwapBuffers(egl_, dispSurf_);
        // VR: render the SAME decoded frame to the second eye's display surface. One decode,
        // two displays — avoids a second HEVC decoder (which on MTK is SW-only and can't sustain
        // two 720p120 streams, and whose HW path renders black even via GL).
        if (dispSurf2_ != EGL_NO_SURFACE) {
            eglMakeCurrent(egl_, dispSurf2_, dispSurf2_, ctx_);
            eglQuerySurface(egl_, dispSurf2_, EGL_WIDTH,  &vpW_);
            eglQuerySurface(egl_, dispSurf2_, EGL_HEIGHT, &vpH_);
            drawOes(texMatrix, /*withOsd=*/false);
            eglSwapBuffers(egl_, dispSurf2_);
        }
        encodePass(encSurf_,    recordOsd_.load(), texMatrix);  // primary stream: Raw (clean) OR OSD
        encodePass(encSurfRaw_, false,             texMatrix);  // secondary stream: Raw+OSD's clean file
    }

    // Encode-only: feeds the DVR encoder at the full decode FPS (90/120) but SKIPS the display
    // eglSwapBuffers. The display render is throttled so BLAST doesn't exhaust; this keeps the
    // encoder receiving every frame so its timestamps stay in sync with the real cadence (no
    // progressive slowdown).
    void renderFrameEncodeOnly(const float texMatrix[16])
    {
        if (!ready_.load()) return;
        encodePass(encSurf_,    recordOsd_.load(), texMatrix);
        encodePass(encSurfRaw_, false,             texMatrix);
    }

    // Resolve eglPresentationTimeANDROID once. Already used for the encoder surfaces (a
    // surface-input MediaCodec needs a PTS or it emits nothing); now also for the display.
    static auto presentationTimeFn()
    {
        static auto fn = (EGLBoolean (*)(EGLDisplay, EGLSurface, EGLnsecsANDROID))
                         eglGetProcAddress("eglPresentationTimeANDROID");
        return fn;
    }

    // Tell SurfaceFlinger the vsync this frame is FOR, so it is not presented early and does not
    // skip a display cycle. Target = the first vsync boundary at least a fraction of a period away
    // (a frame handed in right at a boundary can miss that composition, so aim at the next one).
    // No-ops until the first Choreographer tick has arrived, or if the extension is missing.
    void stampDisplayPresentationTime()
    {
        auto fn = presentationTimeFn();
        const int64_t base = vsyncNs_.load();
        if (!fn || base == 0) return;
        const int64_t period = (int64_t) refreshNs_.load();
        if (period <= 0) return;
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);   // same timebase as Choreographer's frameTimeNanos
        const int64_t now = (int64_t) ts.tv_sec * 1000000000LL + ts.tv_nsec;
        // Number of whole periods from the last known vsync to just past "now + 20% of a period".
        const int64_t ahead = now + period / 5 - base;
        int64_t k = ahead > 0 ? (ahead + period - 1) / period : 0;
        int64_t target = base + k * period;
        // Force targets to be STRICTLY one period apart. `base` is only refreshed by the
        // Choreographer callback, which lands on this same (render-busy) thread and so can be a
        // tick stale; two consecutive frames then round to the SAME vsync, the second replaces the
        // first in the queue, and half the frames vanish — measured as a rock-steady 60-65 fps out
        // of 90 with max exactly 33 ms (3 vsyncs). Chaining from the previous target instead makes
        // one frame map to one vsync. If we drift more than a few frames behind real time (a stall,
        // a link gap), abandon the chain and resync to the computed target.
        const int64_t prev = lastTargetNs_;
        if (prev != 0 && target <= prev) target = prev + period;   // strictly increasing
        // Never aim more than ~2 refreshes ahead. A BufferQueue only holds a couple of buffers, so
        // queueing far into the future starves it and we start dropping instead of pacing — the
        // earlier 4-period allowance pinned presentation at a steady 60 of 90 fps. If the chain has
        // run ahead (we render marginally faster than the panel, or a gap moved real time), resync.
        if (target > now + 2 * period) target = now + period;
        lastTargetNs_ = target;
        fn(egl_, dispSurf_, (EGLnsecsANDROID) target);
    }

    // One encoder pass: render the frame (+ OSD when withOsd) into a MediaCodec input EGL surface.
    void encodePass(EGLSurface surf, bool withOsd, const float texMatrix[16])
    {
        if (surf == EGL_NO_SURFACE) return;
        eglMakeCurrent(egl_, surf, surf, ctx_);
        eglQuerySurface(egl_, surf, EGL_WIDTH, &vpW_);
        eglQuerySurface(egl_, surf, EGL_HEIGHT, &vpH_);
        drawOes(texMatrix, withOsd);
        // Surface-input encoders need a per-frame presentation timestamp or they emit no output.
        // "now" (not a future vsync) — the recording wants real capture times, not display timing.
        auto eglPresTime = presentationTimeFn();
        struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
        if (eglPresTime) eglPresTime(egl_, surf, (EGLnsecsANDROID) ts.tv_sec * 1000000000LL + ts.tv_nsec);
        eglSwapBuffers(egl_, surf);
    }

    void release()
    {
        ready_.store(false);
        if (egl_ != EGL_NO_DISPLAY) {
            eglMakeCurrent(egl_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (encSurf_  != EGL_NO_SURFACE) eglDestroySurface(egl_, encSurf_);
            if (encSurfRaw_ != EGL_NO_SURFACE) eglDestroySurface(egl_, encSurfRaw_);
            if (dispSurf_  != EGL_NO_SURFACE) eglDestroySurface(egl_, dispSurf_);
            if (dispSurf2_ != EGL_NO_SURFACE) eglDestroySurface(egl_, dispSurf2_);
            if (ctx_      != EGL_NO_CONTEXT) eglDestroyContext(egl_, ctx_);
            eglTerminate(egl_);
        }
        egl_ = EGL_NO_DISPLAY; ctx_ = EGL_NO_CONTEXT; dispSurf_ = dispSurf2_ = encSurf_ = EGL_NO_SURFACE;
    }

  private:
    bool fail(const char* w) { __android_log_print(ANDROID_LOG_ERROR, "GLFanout", "%s failed", w); return false; }

    bool buildProgram()
    {
        static const char* VS =
            "attribute vec4 aPos; attribute vec2 aUV; uniform mat4 uTex;"
            "varying vec2 vUV; void main(){ vUV=(uTex*vec4(aUV,0.,1.)).xy; gl_Position=aPos; }";
        // OpenIPC "Overshoot Fix" colortrans reversal (ported from OpenIPC/PixelPilot_rk
        // osd_gl.cpp). The VTX's _colortrans.bin sensor calibration pre-flattens contrast
        // (video looks washed-out/gray) to cut H.26x edge overshoot; the VRX must expand it
        // back. Inverse transform per channel: out = clamp((in + offset) * gain, 0, 1)
        // (gain/offset default 2.5 / -0.15, matching PixelPilot_rk). uCtEnable mixes it in so
        // OFF (default) is a pure passthrough.
        static const char* FS =
            "#extension GL_OES_EGL_image_external : require\n"
            "precision mediump float; varying vec2 vUV; uniform samplerExternalOES uTexOes;"
            "uniform float uCtEnable; uniform float uCtGain; uniform float uCtOffset;"
            "void main(){"
            "  vec4 c = texture2D(uTexOes, vUV);"
            "  vec3 rev = clamp((c.rgb + uCtOffset) * uCtGain, 0.0, 1.0);"
            "  gl_FragColor = vec4(mix(c.rgb, rev, uCtEnable), c.a);"
            "}";
        prog_ = link(VS, FS);
        if (!prog_) return fail("link program");
        aPos_    = glGetAttribLocation(prog_, "aPos");
        aUV_     = glGetAttribLocation(prog_, "aUV");
        uTex_    = glGetUniformLocation(prog_, "uTex");
        uTexOes_ = glGetUniformLocation(prog_, "uTexOes");
        uCtEnable_ = glGetUniformLocation(prog_, "uCtEnable");
        uCtGain_   = glGetUniformLocation(prog_, "uCtGain");
        uCtOffset_ = glGetUniformLocation(prog_, "uCtOffset");
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
            "attribute vec4 aPos; attribute vec2 aUV; varying vec2 vUV; uniform vec2 uOsdScale;"
            // Flip V: the captured OSD Bitmap is top-down, the quad UV is bottom-up -> the overlay
            // would otherwise composite upside-down. uOsdScale preserves the OSD's aspect ratio in
            // the (differently-shaped) video frame so circles stay circles (no vertical stretch).
            "void main(){ vUV=vec2(aUV.x, 1.0-aUV.y); gl_Position=vec4(aPos.xy*uOsdScale, 0.0, 1.0); }";
        static const char* OFS =
            "precision mediump float; varying vec2 vUV; uniform sampler2D uOsd;"
            "void main(){ gl_FragColor = texture2D(uOsd, vUV); }";
        osdProg_ = link(OVS, OFS);
        if (osdProg_) {
            osdPos_      = glGetAttribLocation(osdProg_, "aPos");
            osdUV_       = glGetAttribLocation(osdProg_, "aUV");
            osdTexLoc_   = glGetUniformLocation(osdProg_, "uOsd");
            osdScaleLoc_ = glGetUniformLocation(osdProg_, "uOsdScale");
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
        // colortrans reversal params from props (live-tunable, no rebuild):
        //   debug.pixelpilot.colortrans = 1  -> enable the un-wash
        //   debug.pixelpilot.ct_gain   (default 2.5)   debug.pixelpilot.ct_offset (default -0.15)
        {
            // enable + gain/offset come from the settings menu (globals set via
            // VideoPlayer.nativeSetColortrans). A debug.pixelpilot.colortrans=1 prop can also
            // force-enable, and ct_gain/ct_offset props override for live tuning without a rebuild.
            char cp[PROP_VALUE_MAX] = {0}, gp[PROP_VALUE_MAX] = {0}, op[PROP_VALUE_MAX] = {0};
            __system_property_get("debug.pixelpilot.colortrans", cp);
            float ctEnable = (cp[0] == '1' || g_ct_enable.load() > 0.5f) ? 1.0f : 0.0f;
            float ctGain   = (__system_property_get("debug.pixelpilot.ct_gain", gp)   > 0) ? (float) atof(gp) : g_ct_gain.load();
            float ctOffset = (__system_property_get("debug.pixelpilot.ct_offset", op) > 0) ? (float) atof(op) : g_ct_offset.load();
            glUniform1f(uCtEnable_, ctEnable);
            glUniform1f(uCtGain_,   ctGain);
            glUniform1f(uCtOffset_, ctOffset);
        }
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
            // Preserve the OSD's aspect in the video frame: the OSD is captured at the phone screen
            // aspect (e.g. 20:9) but the recording is 16:9, so stretch-to-fill turns circles into
            // ovals. Fit the OSD into the frame (letterbox) keeping its own aspect.
            float sx = 1.f, sy = 1.f;
            if (osdTexW_ > 0 && osdTexH_ > 0 && vpW_ > 0 && vpH_ > 0) {
                float osdA = (float) osdTexW_ / (float) osdTexH_, vpA = (float) vpW_ / (float) vpH_;
                if (osdA > vpA) sy = vpA / osdA; else sx = osdA / vpA;
            }
            glUniform2f(osdScaleLoc_, sx, sy);
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

    ANativeWindow* display_  = nullptr;
    ANativeWindow* display2_ = nullptr;   // VR: second eye window
    ANativeWindow* enc_     = nullptr;
    EGLDisplay egl_      = EGL_NO_DISPLAY;
    EGLContext ctx_      = EGL_NO_CONTEXT;
    EGLConfig  cfg_      = nullptr;
    EGLSurface dispSurf_  = EGL_NO_SURFACE;
    EGLSurface dispSurf2_ = EGL_NO_SURFACE;   // VR: second eye EGL surface
    EGLSurface encSurf_    = EGL_NO_SURFACE;
    EGLSurface encSurfRaw_ = EGL_NO_SURFACE;
    GLuint     prog_ = 0, osdProg_ = 0, oesTex_ = 0, osdTex_ = 0, vbo_ = 0;
    GLint      aPos_ = -1, aUV_ = -1, uTex_ = -1, uTexOes_ = -1;
    GLint      uCtEnable_ = -1, uCtGain_ = -1, uCtOffset_ = -1;
    GLint      osdPos_ = -1, osdUV_ = -1, osdTexLoc_ = -1, osdScaleLoc_ = -1;
    EGLint     vpW_ = 0, vpH_ = 0;
    int        osdTexW_ = 0, osdTexH_ = 0;
    std::atomic<bool> ready_{false};
    // Vsync timebase for display presentation timestamps (see stampDisplayPresentationTime).
    // 0 = no Choreographer tick yet, so don't stamp. Default period = 90 Hz, the usual APFPV
    // stream rate; corrected by setRefreshNs() once the app knows the negotiated display mode.
    std::atomic<int64_t>  vsyncNs_{0};
    std::atomic<uint64_t> refreshNs_{11111111ULL};
    // Last presentation target handed to EGL, so the next one can be chained exactly one period
    // later. GL-thread only (stampDisplayPresentationTime), hence plain.
    int64_t               lastTargetNs_ = 0;
    std::atomic<bool> recordOsd_{false};
    GLFanoutEncoder   encoder_;
    GLFanoutEncoder   encoderRaw_;
};

#endif  // PIXELPILOT_GLFANOUTRENDERER_H
