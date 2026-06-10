//
// GLFanoutEncoder.h — the DVR half of the GL fan-out.
//
// The GL pass (GLFanoutRenderer) renders the decoded frame — optionally with the OSD layer
// alpha-composited on top (the recordOsd toggle) — into THIS encoder's input Surface. The
// encoder produces complete Annex-B NALUs which the drain thread hands to onEncoded() for the
// existing mp4 writer (mp4_h26x_write_nal), exactly as the legacy/transcoder DVR paths do.
//
// vs DvrTranscoder: NO parallel decoder. The GL already produced the frame, so the DVR records
// precisely what was composited (clean video, or video+OSD) — one decode shared with the live
// display. This is what lets the recorded .mp4 carry the overlay without a second decode.
//
#ifndef PIXELPILOT_GLFANOUTENCODER_H
#define PIXELPILOT_GLFANOUTENCODER_H

#include <android/log.h>
#include <android/native_window.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <atomic>
#include <functional>
#include <thread>

class GLFanoutEncoder
{
  public:
    using OnEncodedNalu = std::function<void(const uint8_t* data, size_t size, bool isH265)>;

    GLFanoutEncoder() = default;
    ~GLFanoutEncoder() { stop(); }

    // Create the surface-input encoder. inputWindow() is then the ANativeWindow the GL pass
    // renders into (hand it to GLFanoutRenderer::setEncoderWindow). Encoder emits H.264 by
    // default (broadest .mp4 compatibility); set h265 for HEVC.
    bool start(int width, int height, int fps, int bitrate, OnEncodedNalu cb, bool h265 = false)
    {
        if (running_.load()) return true;
        if (width <= 0 || height <= 0) return false;
        onEncoded_ = std::move(cb);
        h265_ = h265;
        const char* mime = h265 ? "video/hevc" : "video/avc";
        enc_ = AMediaCodec_createEncoderByType(mime);
        if (!enc_) return false;
        AMediaFormat* f = AMediaFormat_new();
        AMediaFormat_setString(f, AMEDIAFORMAT_KEY_MIME, mime);
        AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_WIDTH, width);
        AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_HEIGHT, height);
        AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_COLOR_FORMAT, 0x7F000789);  // COLOR_FormatSurface
        AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_BIT_RATE, bitrate > 0 ? bitrate : 8'000'000);
        AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_FRAME_RATE, fps > 0 ? fps : 30);
        AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, 1);
        if (AMediaCodec_configure(enc_, f, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE) != AMEDIA_OK)
        { AMediaFormat_delete(f); cleanup(); return false; }
        AMediaFormat_delete(f);
        if (AMediaCodec_createInputSurface(enc_, &inputWin_) != AMEDIA_OK) { cleanup(); return false; }
        if (AMediaCodec_start(enc_) != AMEDIA_OK) { cleanup(); return false; }
        running_.store(true);
        drain_ = std::thread(&GLFanoutEncoder::drainLoop, this);
        return true;
    }

    ANativeWindow* inputWindow() const { return inputWin_; }   // the GL pass renders here
    bool isRunning() const { return running_.load(); }

    void stop()
    {
        if (!running_.exchange(false)) { cleanup(); return; }
        if (enc_) AMediaCodec_signalEndOfInputStream(enc_);   // flush -> drain emits EOS
        if (drain_.joinable()) drain_.join();
        cleanup();
    }

  private:
    void drainLoop()
    {
        AMediaCodecBufferInfo info;
        int dbg = 0;
        while (running_.load())
        {
            ssize_t eo = AMediaCodec_dequeueOutputBuffer(enc_, &info, 5000);
            if (dbg < 8) { __android_log_print(ANDROID_LOG_DEBUG, "GLFanoutDbg", "drain eo=%zd size=%d", eo, (int) (eo >= 0 ? info.size : -1)); dbg++; }
            if (eo < 0) continue;
            size_t   sz  = 0;
            uint8_t* out = AMediaCodec_getOutputBuffer(enc_, eo, &sz);
            if (out && info.size > 0 && onEncoded_) onEncoded_(out + info.offset, info.size, h265_);
            bool eos = (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
            AMediaCodec_releaseOutputBuffer(enc_, eo, false);
            if (eos) break;
        }
    }
    void cleanup()
    {
        if (enc_)      { AMediaCodec_stop(enc_); AMediaCodec_delete(enc_); enc_ = nullptr; }
        if (inputWin_) { ANativeWindow_release(inputWin_); inputWin_ = nullptr; }
    }

    AMediaCodec*      enc_      = nullptr;
    ANativeWindow*    inputWin_ = nullptr;
    std::atomic<bool> running_{false};
    std::thread       drain_;
    OnEncodedNalu     onEncoded_;
    bool              h265_ = false;
};

#endif  // PIXELPILOT_GLFANOUTENCODER_H
