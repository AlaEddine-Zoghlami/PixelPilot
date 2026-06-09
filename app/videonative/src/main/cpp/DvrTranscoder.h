//
// DvrTranscoder.h — clean-DVR re-encoder.
//
// The legacy DVR muxes the RAW received NALU bitstream straight into the MP4
// (VideoPlayer.cpp -> mp4_h26x_write_nal). On a lossy link that file inherits
// every incomplete/torn frame off the wire — even though the on-screen decoder
// hides the damage by dropping/holding incomplete frames before display. So the
// screen looks clean while the recording tears.
//
// This transcoder records the DECODED stream instead: a parallel AMediaCodec
// DECODER renders the wire NALUs into an AMediaCodec ENCODER's input Surface,
// and the encoder's freshly-encoded (always-complete) NALUs are handed back via
// a callback — which the DVR writer mux's exactly as before. Net effect: the MP4
// contains only complete, rendered frames, byte-for-byte what was displayed,
// with predictable codec/level instead of whatever the VTX happened to send.
//
// No GL: AMediaCodec supports decoder-output-Surface == encoder-input-Surface
// directly, so frames flow decoder->encoder in hardware with no copy.
//
// STATUS: core pipeline implemented; wiring into VideoPlayer (tee NALUs here when
// recording, route onEncodedNalu() to the existing minimp4 writer instead of the
// raw queue) is the remaining integration step — see memory plan.
//
#ifndef PIXELPILOT_DVRTRANSCODER_H
#define PIXELPILOT_DVRTRANSCODER_H

#include <android/log.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <atomic>
#include <functional>
#include <thread>
#include <vector>
#include "NALU/NALU.hpp"

class DvrTranscoder
{
  public:
    // Called (on the drain thread) with each re-encoded H.264/HEVC Annex-B NALU.
    // The DVR writer feeds this straight to mp4_h26x_write_nal — same as it does
    // for raw NALUs today, so the muxing path is unchanged.
    using OnEncodedNalu = std::function<void(const uint8_t* data, size_t size, bool isH265)>;

    DvrTranscoder() = default;
    ~DvrTranscoder() { stop(); }

    // csd = the SPS(/PPS/VPS) Annex-B blob needed to configure the parallel decoder
    // (the same config data KeyFrameFinder already collected for the display decoder).
    // Encoder always emits H.264 (broadest MP4 compatibility); set h265Out for HEVC.
    bool start(int width, int height, bool inputIsH265, int fps, int bitrate,
               const uint8_t* csd, size_t csdLen, OnEncodedNalu cb, bool h265Out = false)
    {
        if (running.load()) return true;
        if (width <= 0 || height <= 0) return false;
        onEncoded = std::move(cb);
        outIsH265 = h265Out;

        // ---- Encoder: surface-input, produces Annex-B NALUs -------------------
        const char* outMime = h265Out ? "video/hevc" : "video/avc";
        encoder             = AMediaCodec_createEncoderByType(outMime);
        if (!encoder) return false;
        AMediaFormat* ef = AMediaFormat_new();
        AMediaFormat_setString(ef, AMEDIAFORMAT_KEY_MIME, outMime);
        AMediaFormat_setInt32(ef, AMEDIAFORMAT_KEY_WIDTH, width);
        AMediaFormat_setInt32(ef, AMEDIAFORMAT_KEY_HEIGHT, height);
        // 0x7F000789 == COLOR_FormatSurface (input comes from the decoder's surface)
        AMediaFormat_setInt32(ef, AMEDIAFORMAT_KEY_COLOR_FORMAT, 0x7F000789);
        AMediaFormat_setInt32(ef, AMEDIAFORMAT_KEY_BIT_RATE, bitrate > 0 ? bitrate : 8'000'000);
        AMediaFormat_setInt32(ef, AMEDIAFORMAT_KEY_FRAME_RATE, fps > 0 ? fps : 30);
        AMediaFormat_setInt32(ef, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, 1);
        if (AMediaCodec_configure(encoder, ef, nullptr, nullptr,
                                  AMEDIACODEC_CONFIGURE_FLAG_ENCODE) != AMEDIA_OK)
        { AMediaFormat_delete(ef); cleanup(); return false; }
        AMediaFormat_delete(ef);
        if (AMediaCodec_createInputSurface(encoder, &encInputSurface) != AMEDIA_OK) { cleanup(); return false; }
        if (AMediaCodec_start(encoder) != AMEDIA_OK) { cleanup(); return false; }

        // ---- Decoder: renders the wire NALUs into the encoder's input surface --
        const char* inMime = inputIsH265 ? "video/hevc" : "video/avc";
        decoder            = AMediaCodec_createDecoderByType(inMime);
        if (!decoder) { cleanup(); return false; }
        AMediaFormat* df = AMediaFormat_new();
        AMediaFormat_setString(df, AMEDIAFORMAT_KEY_MIME, inMime);
        AMediaFormat_setInt32(df, AMEDIAFORMAT_KEY_WIDTH, width);
        AMediaFormat_setInt32(df, AMEDIAFORMAT_KEY_HEIGHT, height);
        if (csd && csdLen) AMediaFormat_setBuffer(df, "csd-0", (void*) csd, csdLen);
        if (AMediaCodec_configure(decoder, df, encInputSurface, nullptr, 0) != AMEDIA_OK)
        { AMediaFormat_delete(df); cleanup(); return false; }
        AMediaFormat_delete(df);
        if (AMediaCodec_start(decoder) != AMEDIA_OK) { cleanup(); return false; }

        running.store(true);
        drainThread = std::thread(&DvrTranscoder::drainLoop, this);
        return true;
    }

    // Feed a wire NALU to the parallel decoder (non-blocking-ish; drops if no input
    // buffer free, which only affects the recording, never the live decode).
    void feedNALU(const NALU& nalu)
    {
        if (!running.load() || !decoder) return;
        ssize_t ib = AMediaCodec_dequeueInputBuffer(decoder, 0);
        if (ib < 0) return;  // recorder back-pressure: skip — live path is untouched
        size_t   cap = 0;
        uint8_t* buf = AMediaCodec_getInputBuffer(decoder, ib, &cap);
        if (!buf || cap < nalu.getSize()) { AMediaCodec_queueInputBuffer(decoder, ib, 0, 0, ptsUs(), 0); return; }
        memcpy(buf, nalu.getData(), nalu.getSize());
        AMediaCodec_queueInputBuffer(decoder, ib, 0, nalu.getSize(), ptsUs(), 0);
    }

    void stop()
    {
        if (!running.exchange(false)) { cleanup(); return; }
        if (encoder) AMediaCodec_signalEndOfInputStream(encoder);
        if (drainThread.joinable()) drainThread.join();
        cleanup();
    }

    bool isRunning() const { return running.load(); }

  private:
    int64_t ptsUs()
    {
        // Monotonic PTS; the muxer re-times by framerate anyway.
        return (frameIdx++) * (1'000'000LL / 60);
    }

    // Pump: move decoded frames decoder->encoder surface, drain encoder NALUs.
    void drainLoop()
    {
        AMediaCodecBufferInfo info;
        while (running.load())
        {
            // Decoder output -> render onto the encoder's input surface (render=true).
            ssize_t ob = AMediaCodec_dequeueOutputBuffer(decoder, &info, 2000);
            if (ob >= 0) AMediaCodec_releaseOutputBuffer(decoder, ob, /*render=*/true);

            // Encoder output -> re-encoded Annex-B NALUs -> callback (mux).
            ssize_t eo = AMediaCodec_dequeueOutputBuffer(encoder, &info, 2000);
            if (eo >= 0)
            {
                size_t   sz  = 0;
                uint8_t* out = AMediaCodec_getOutputBuffer(encoder, eo, &sz);
                if (out && info.size > 0 && onEncoded)
                    onEncoded(out + info.offset, info.size, outIsH265);
                bool eos = (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
                AMediaCodec_releaseOutputBuffer(encoder, eo, false);
                if (eos) break;
            }
        }
    }

    void cleanup()
    {
        if (decoder)        { AMediaCodec_stop(decoder); AMediaCodec_delete(decoder); decoder = nullptr; }
        if (encoder)        { AMediaCodec_stop(encoder); AMediaCodec_delete(encoder); encoder = nullptr; }
        if (encInputSurface){ ANativeWindow_release(encInputSurface); encInputSurface = nullptr; }
    }

    AMediaCodec*      decoder         = nullptr;
    AMediaCodec*      encoder         = nullptr;
    ANativeWindow*    encInputSurface = nullptr;
    std::atomic<bool> running{false};
    std::thread       drainThread;
    OnEncodedNalu     onEncoded;
    bool              outIsH265 = false;
    std::atomic<long> frameIdx{0};
};

#endif  // PIXELPILOT_DVRTRANSCODER_H
