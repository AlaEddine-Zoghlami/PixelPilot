//
// Created by gaeta on 2024-04-01.
//

#ifndef FPVUE_VIDEODECODER_H
#define FPVUE_VIDEODECODER_H

#include <android/log.h>
#include <android/native_window.h>
#include <jni.h>
#include <media/NdkMediaCodec.h>
#include <atomic>
#include <condition_variable>
#include <deque>
#include <iostream>
#include <mutex>
#include <thread>
#include "NALU/KeyFrameFinder.hpp"
#include "NALU/NALU.hpp"
#include "helper/TimeHelper.hpp"

struct DecodingInfo
{
    std::chrono::steady_clock::time_point lastCalculation          = std::chrono::steady_clock::now();
    long                                  nNALU                    = 0;
    long                                  nNALUSFeeded             = 0;
    long                                  nDecodedFrames           = 0;
    long                                  nCodec                   = 0;
    float                                 currentFPS               = 0;
    float                                 currentKiloBitsPerSecond = 0;
    float                                 avgParsingTime_ms        = 0;
    float                                 avgWaitForInputBTime_ms  = 0;
    float                                 avgDecodingTime_ms       = 0;

    bool operator==(const DecodingInfo& d2) const
    {
        return nNALU == d2.nNALU && nNALUSFeeded == d2.nNALUSFeeded && currentFPS == d2.currentFPS &&
               currentKiloBitsPerSecond == d2.currentKiloBitsPerSecond && avgParsingTime_ms == d2.avgParsingTime_ms &&
               avgWaitForInputBTime_ms == d2.avgWaitForInputBTime_ms && avgDecodingTime_ms == d2.avgDecodingTime_ms;
    }

    bool operator!=(const DecodingInfo& d2) const { return !(*this == d2); }
};

struct VideoRatio
{
    int width  = 0;
    int height = 0;

    bool operator==(const VideoRatio& b) const { return width == b.width && height == b.height; }

    bool operator!=(const VideoRatio& b) const { return !(*this == b); }
};

// Handles decoding of .h264 and .h265 video
// with low latency. Uses the AMediaCodec api
class VideoDecoder
{
  private:
    struct Decoder
    {
        bool           configured[2] = {false, false};
        AMediaCodec*   codec[2]      = {nullptr, nullptr};
        ANativeWindow* window[2]     = {nullptr, nullptr};
    };

  public:
    // Make sure to do no heavy lifting on this callback, since it is called from the low-latency mCheckOutputThread
    // thread (best to copy values and leave processing to another thread) The decoding info callback is called every
    // DECODING_INFO_RECALCULATION_INTERVAL_MS
    typedef std::function<void(const DecodingInfo)> DECODING_INFO_CHANGED_CALLBACK;
    // The decoder ratio callback is called every time the output format changes
    typedef std::function<void(const VideoRatio)> DECODER_RATIO_CHANGED;

  public:
    // We cannot initialize the Decoder until we have SPS and PPS data -
    // when streaming this data will be available at some point in future
    // Therefore we don't allocate the MediaCodec resources here
    VideoDecoder(JNIEnv* env);

    // This call acquires or releases the output surface
    // After acquiring the surface, the decoder will be started as soon as enough configuration data was passed to it
    // When releasing the surface, the decoder will be stopped if running and any resources will be freed
    // After releasing the surface it is safe for the android os to delete it
    void setOutputSurface(JNIEnv* env, jobject surface, jint idx);

    // register the specified callbacks. Only one can be registered at a time
    void registerOnDecoderRatioChangedCallback(DECODER_RATIO_CHANGED decoderRatioChangedC);

    void registerOnDecodingInfoChangedCallback(DECODING_INFO_CHANGED_CALLBACK decodingInfoChangedCallback);

    // If the decoder has been configured, feed NALU. Else search for configuration data and
    // configure as soon as possible
    //  If the input pipe was closed (surface has been removed or is not set yet), only buffer key frames
    void interpretNALU(const NALU& nalu);

    // Set the MTK stale-frame flush threshold in ms (0 = off). Called from JNI when the menu changes.
    // If the decoder force-disabled flush (MTK SW-HEVC, whose inherent >60ms latency makes the
    // flush-to-keyframe fire and collapse renderFps to 0), keep it OFF — the UI default (60ms) from
    // onResume must NOT re-arm it. drop-to-freshest still caps latency without freezing.
    void setFlushThresholdMs(int ms) { if (mFlushForceDisabled) { mFlushThresholdMs = 0; return; } mFlushThresholdMs = ms < 0 ? 0 : ms; }

  private:
    // Initialize decoder with SPS / PPS data from KeyFrameFinder
    // Set Decoder.configured to true on success
    void configureStartDecoder(int idx);

    // Wait for input buffer to become available before feeding NALU
    void feedDecoder(const NALU& nalu, int idx);

    // Drains mFeedQueue and calls feedDecoder(). Runs on its own thread so that a slow
    // AMediaCodec_dequeueInputBuffer/decode (SW HEVC on this SoC measured ~11-24ms/frame,
    // exceeding the 90-120fps budget) can NEVER stall the RTP-receive/parse thread that calls
    // interpretNALU(). Before this, feedDecoder() ran INLINE in interpretNALU() — meaning once
    // decode fell behind, the same thread that drains incoming RTP/UDP data was blocked on it,
    // so network processing backed up too (measured as the "Parsing" latency stat, which is
    // mostly this compounding backlog, not actual bitstream parsing cost). Bounded queue with
    // drop-oldest keeps added latency capped instead of growing without limit.
    void feedLoop();

    // Runs until EOS arrives at output buffer or decoder is stopped
    void checkOutputLoop(int idx);

    // Debug log
    void printAvgLog();

    void resetStatistics();

    std::unique_ptr<std::thread> mCheckOutputThread[2]  = {nullptr, nullptr};
    // Decode-feed queue: interpretNALU() copies+enqueues (fast, no AMediaCodec calls) instead of
    // calling feedDecoder() directly. mFeedThread drains it. Bounded so a persistent decode
    // backlog is capped at FEED_QUEUE_MAX frames of extra latency (oldest dropped on overflow)
    // instead of growing unbounded — mirrors the UDPReceiver recv/dispatch decouple pattern.
    std::deque<std::unique_ptr<NALUBuffer>> mFeedQueue;
    std::mutex                              mFeedQueueMtx;
    std::condition_variable                 mFeedQueueCv;
    std::atomic<bool>                       mFeedThreadRunning{false};
    std::unique_ptr<std::thread>             mFeedThread;
    // MUST be larger than the worst-case single-NALU stall in feedDecoder, or an overflow (and a
    // reference-breaking drop) is arithmetically GUARANTEED whenever the codec input pool empties.
    // feedDecoder spins on dequeueInputBuffer for up to 100 ms before giving up on one NALU, while
    // 8 NALUs is only ~89 ms at 90 fps — so one stuck NALU always overflowed the queue.
    //
    // That is the artifact on an aalink MCS change: a bitrate step up (e.g. 12740 -> 25480 kbps)
    // instantly enlarges every frame, the codec input pool exhausts, feedDecoder stalls, and the
    // queue then dropped its OLDEST entries — which are exactly the NALUs the decoder needs next,
    // so the reference chain breaks and corruption persists until the following keyframe. Measured
    // before this change: "mFeedQueue overflow: dropped 122 NALU(s) in last 9698ms".
    //
    // 32 frames ~= 355 ms at 90 fps of transient absorption, comfortably past the 100 ms stall.
    // This costs NO steady-state latency: the queue sits empty when the decoder keeps up (it is a
    // backlog absorber, not a jitter buffer), and drop-oldest still bounds the worst case.
    static constexpr size_t                  FEED_QUEUE_MAX = 32;
    bool                         USE_SW_DECODER_INSTEAD = false;
    // FPV latency-priority decode (APFPV). Set in configureStartDecoder from the SoC + the runtime
    // gate `persist.pixelpilot.mtkopt` (default ON; `setprop ... 0` reverts to legacy for A/B).
    // When on, checkOutputLoop drops a stale backlog instead of rendering late frames.
    bool                         mLowLatencyOpt = false;
    bool                         mDropStale = false;  // drop-to-freshest frame skipping (default OFF — froze the dongle)
    // MediaTek SoC? The stale-frame flush is MTK-only (the MTK HW decoder is the one that backs up
    // on a lossy link). Set from ro.board.platform in configureStartDecoder.
    bool                         mIsMtk = false;
    // Stale-frame flush threshold (ms). 0 = off (DEFAULT). MTK-only; configurable from the settings
    // menu (shown only on MTK). When a decoded frame is later than this, flush to the next keyframe.
    // Default off so the baseline never drops frames; opt-in via the menu. Set from JNI via
    // setFlushThresholdMs(); plain int (benign 1-frame race on retune).
    int                          mFlushThresholdMs = 60;  // MTK-optimized: flush stale frames at 60ms
    bool                         mFlushForceDisabled = false;  // sticky: MTK SW-HEVC disables flush-to-keyframe permanently (setFlushThresholdMs can't re-arm it)
    // Stall-recovery watchdog (checkOutputLoop, idx 0 only): counts consecutive ~1s windows where
    // NALUs are still arriving (currentKiloBitsPerSecond > 0) but the decoder has produced ~0
    // frames. A HW decoder ASIC can wedge permanently on a single malformed/corrupted NALU
    // (CCMP decrypt-fail / torn frame under RF loss, which grows with throughput) — unlike a SW
    // decoder it won't self-recover, so without this it stays stuck forever. See VideoDecoder.cpp.
    int                          mStallTicks = 0;
    // Holds the AMediaCodec instance, as well as the state (configured or not configured)
    Decoder      decoder{};
    DecodingInfo decodingInfo;
    // The input pipe is closed until we set a valid surface
    bool                           inputPipeClosed = true;
    std::mutex                     mMutexInputPipe;
    // Serializes AMediaCodec_flush (checkOutputLoop / output thread) against
    // AMediaCodec_dequeueInputBuffer (feedDecoder / parse thread). Concurrent flush + input-dequeue
    // trips the framework CHECK "MediaCodec.cpp:4310 findSize(index)" and abort()s — seen under
    // heavy loss (torn NALUs → decoder errors → flush fires while feed is dequeuing). One per idx.
    std::mutex                     mCodecFlushMtx[2];
    DECODER_RATIO_CHANGED          onDecoderRatioChangedCallback = nullptr;
    DECODING_INFO_CHANGED_CALLBACK onDecodingInfoChangedCallback = nullptr;
    // So we can temporarily attach the output thread to the vm and make ndk calls
    JavaVM*                               javaVm  = nullptr;
    std::chrono::steady_clock::time_point lastLog = std::chrono::steady_clock::now();
    RelativeCalculator                    nDecodedFrames;
    RelativeCalculator                    nNALUBytesFed;
    AvgCalculator                         parsingTime;
    AvgCalculator                         waitForInputB;
    AvgCalculator                         decodingTime;
    // Every n ms re-calculate the Decoding info
    static const constexpr auto DECODING_INFO_RECALCULATION_INTERVAL = std::chrono::milliseconds(1000);
    static constexpr const bool PRINT_DEBUG_INFO                     = true;
    static constexpr auto       TIME_BETWEEN_LOGS                    = std::chrono::seconds(5);
    static constexpr int64_t    BUFFER_TIMEOUT_US = 5 * 1000;  // 5ms — 120fps budget is 8.3ms (was 17ms)
  private:
    KeyFrameFinder mKeyFrameFinder;
    bool           IS_H265 = false;
};

#endif  // FPVUE_VIDEODECODER_H
