//
// Created by gaeta on 2024-04-01.
//

#include "VideoDecoder.h"
#include <unistd.h>
#include <sys/system_properties.h>
#include <sstream>
#include "AndroidThreadPrioValues.hpp"
#include "helper/AndroidMediaFormatHelper.h"
#include "helper/NDKThreadHelper.hpp"

#include <vector>

#include <android/native_window_jni.h>
#include <media/NdkMediaCodec.h>

using namespace std::chrono;

VideoDecoder::VideoDecoder(JNIEnv* env)
{
    env->GetJavaVM(&javaVm);
    resetStatistics();
}

void VideoDecoder::setOutputSurface(JNIEnv* env, jobject surface, jint idx)
{
    if (surface == nullptr)
    {
        MLOGD << "Set output null surface idx: " << idx;
        // assert(decoder.window!=nullptr);
        if (decoder.window[idx] == nullptr && decoder.codec[idx] == nullptr)
        {
            // MLOGD<<"Decoder window is already null";
            return;
        }
        std::lock_guard<std::mutex> lock(mMutexInputPipe);
        inputPipeClosed = true;
        if (decoder.configured[idx])
        {
            AMediaCodec_stop(decoder.codec[idx]);
            AMediaCodec_delete(decoder.codec[idx]);
            decoder.codec[idx] = nullptr;
            MLOGD << "Set decoder.codec null idx: " << idx;
            mKeyFrameFinder.reset();
            decoder.configured[idx] = false;
            if (mCheckOutputThread[idx]->joinable())
            {
                mCheckOutputThread[idx]->join();
                mCheckOutputThread[idx].reset();
            }
        }
        if (decoder.window[idx])
        {
            ANativeWindow_release(decoder.window[idx]);
            decoder.window[idx] = nullptr;
            MLOGD << "Set decoder.window null idx: " << idx;
        }
        resetStatistics();
    }
    else
    {
        MLOGD << "Set output non-null surface idx :" << idx;
        // Throw warning if the surface is set without clearing it first
        assert(decoder.window[idx] == nullptr);
        decoder.window[idx] = ANativeWindow_fromSurface(env, surface);
        // open the input pipe - now the decoder will start as soon as enough data is available
        inputPipeClosed = false;
    }
}

void VideoDecoder::registerOnDecoderRatioChangedCallback(DECODER_RATIO_CHANGED decoderRatioChangedC)
{
    onDecoderRatioChangedCallback = std::move(decoderRatioChangedC);
}

void VideoDecoder::registerOnDecodingInfoChangedCallback(DECODING_INFO_CHANGED_CALLBACK decodingInfoChangedCallback)
{
    onDecodingInfoChangedCallback = std::move(decodingInfoChangedCallback);
}

void VideoDecoder::interpretNALU(const NALU& nalu)
{
    // TODO: RN switching between h264 / h265 requires re-setting the surface
    IS_H265             = nalu.IS_H265_PACKET;
    decodingInfo.nCodec = IS_H265;
    // we need this lock, since the receiving/parsing/feeding does not run on the same thread who sets the input surface
    std::lock_guard<std::mutex> lock(mMutexInputPipe);
    decodingInfo.nNALU++;
    if (nalu.getSize() <= 4)
    {
        // No data in NALU (e.g at the beginning of a stream)
        return;
    }
    nNALUBytesFed.add(nalu.getSize());
    if (inputPipeClosed)
    {
        MLOGD << "inputPipeClosed.";
        // A feedD thread (e.g. file or udp) thread might be running even tough no output surface was set
        // But at least we can buffer the sps/pps data
        mKeyFrameFinder.saveIfKeyFrame(nalu);
        return;
    }
    if (decoder.configured[0] || decoder.configured[1])
    {
        // Feed ONLY decoders that are actually configured. In VR we now run a single decode
        // (idx 0) and fan it to both eyes via GL, so idx 1 has no codec -> feeding it would
        // deref a null AMediaCodec. (When both are configured, e.g. legacy dual-decode, both feed.)
        if (decoder.configured[0]) feedDecoder(nalu, 0);
        if (decoder.configured[1]) feedDecoder(nalu, 1);
        decodingInfo.nNALUSFeeded++;
        // manually feeding AUDs doesn't seem to change anything for high latency streams
        // Only for the x264 sw encoded example stream it might improve latency slightly
        // if(!nalu.IS_H265_PACKET && nalu.get_nal_unit_type()==NAL_UNIT_TYPE_CODED_SLICE_NON_IDR){
        // MLOGD<<"Feeding special AUD";
        // feedDecoder(NALU::createExampleH264_AUD());
        //}
    }
    else
    {
        // Store sps,pps, vps(H265 only)
        // As soon as enough data has been buffered to initialize the decoder,do so.
        mKeyFrameFinder.saveIfKeyFrame(nalu);
        if (mKeyFrameFinder.allKeyFramesAvailable(IS_H265))
        {
            MLOGD << "Configuring decoder...";
            configureStartDecoder(0);
            configureStartDecoder(1);
        }
    }
}

void VideoDecoder::configureStartDecoder(int idx)
{
    if (decoder.window[idx] == nullptr) return;
    const std::string MIME = IS_H265 ? "video/hevc" : "video/avc";
    decoder.codec[idx]     = AMediaCodec_createDecoderByType(MIME.c_str());

    // WORKAROUND (Android Emulator / device with no real hardware video decoder): the emulator's
    // "goldfish" H.264/HEVC decoder is unreliable — it FREEZES on Android 14 (stuck ~frame 1321)
    // and renders GARBAGE on Android 10, even with lossless input (verified: 500 kbps single-slice
    // stream, ~1% loss, whole-frame corruption). Real hardware decoders are fine. So only when the
    // device has no hardware decode (an emulator: ro.boot.qemu=1 / ro.hardware=goldfish|ranchu),
    // force the pure-software Google decoder (c2.android.{avc,hevc}.decoder), which decodes cleanly.
    if (decoder.codec[idx] != nullptr)
    {
        char qemu[PROP_VALUE_MAX] = {0};
        char hw[PROP_VALUE_MAX]   = {0};
        __system_property_get("ro.boot.qemu", qemu);
        __system_property_get("ro.hardware", hw);
        std::string h(hw);
        const bool noHwDecode = (qemu[0] == '1') || h == "goldfish" || h == "ranchu";
        if (noHwDecode)
        {
            const char* swName = IS_H265 ? "c2.android.hevc.decoder" : "c2.android.avc.decoder";
            __android_log_print(ANDROID_LOG_WARN, "VideoDecoder",
                "No HW decoder (ro.hardware='%s') — goldfish decoder is unreliable; forcing SW %s", hw, swName);
            AMediaCodec_delete(decoder.codec[idx]);
            AMediaCodec* sw = AMediaCodec_createCodecByName(swName);
            decoder.codec[idx] = (sw != nullptr) ? sw : AMediaCodec_createDecoderByType(MIME.c_str());
        }
    }

    // WORKAROUND (MediaTek Dimensity, Android 15/16): the MTK HEVC HW decoder configures and
    // reports NO error but renders a BLACK surface (audio fine) — androidx/media #2711/#2765.
    // Detect MediaTek (ro.board.platform starts with "mt") on Android 15+ (api>=35) and force
    // the SOFTWARE HEVC decoder (c2.android.hevc.decoder), which renders. Uses system properties
    // rather than the API-28 AMediaCodec_getName (the NDK native API level doesn't follow gradle
    // minSdk without a CMake reconfigure). api>=35 spares older MediaTek where HEVC HW works.
    if (IS_H265 && decoder.codec[idx] != nullptr)
    {
        char platform[PROP_VALUE_MAX] = {0};
        char sdkStr[PROP_VALUE_MAX]   = {0};
        char hwHevc[PROP_VALUE_MAX]   = {0};
        __system_property_get("ro.board.platform", platform);
        __system_property_get("ro.build.version.sdk", sdkStr);
        __system_property_get("debug.pixelpilot.hwhevc", hwHevc);   // "1" = keep the MTK HW HEVC decoder
        std::string plat(platform);
        bool mtk = (plat.rfind("mt", 0) == 0) && atoi(sdkStr) >= 35;
        // MediaTek (Dimensity, api>=35): the HW HEVC decoder FREEZES after the first keyframe on this
        // stream (VHT-2SS H265 1472x816 @120fps) — verified: HW decoded 2 frames then stuck, SW climbs
        // 0→478→570. (It also renders black to a direct SurfaceView, androidx/media #2711/#2765.) So
        // force the SOFTWARE HEVC decoder (c2.android.hevc.decoder), which sustains via our GL fan-out.
        // debug.pixelpilot.hwhevc=1 forces HW back for testing.
        if (hwHevc[0] != '1' && mtk)
        {
            __android_log_print(ANDROID_LOG_WARN, "VideoDecoder",
                "MediaTek SoC '%s' (api>=35): HW HEVC freezes -> forcing SW c2.android.hevc.decoder", plat.c_str());
            AMediaCodec_delete(decoder.codec[idx]);
            AMediaCodec* sw = AMediaCodec_createCodecByName("c2.android.hevc.decoder");
            decoder.codec[idx] = (sw != nullptr) ? sw : AMediaCodec_createDecoderByType(MIME.c_str());
            // The SW HEVC decoder's output is always >60ms old, so the flush-to-keyframe path would
            // fire every frame -> renderFps=0 (black/freeze) + a flush storm. Disable it; drop-to-
            // freshest still caps latency.
            mFlushThresholdMs = 0;
        }
        else if (hwHevc[0] == '1')
        {
            __android_log_print(ANDROID_LOG_WARN, "VideoDecoder",
                "debug.pixelpilot.hwhevc=1 -> keeping MTK HW HEVC decoder (may freeze on this SoC)");
        }
    }

    AMediaFormat* format = AMediaFormat_new();
    AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, MIME.c_str());
    // Large input buffer prevents thrashing on high-bitrate streams (40Mbps+ 120fps).
    // Default is often too small, causing the decoder to constantly reallocate.
    AMediaFormat_setInt32(format, "max-input-size", 4 * 1024 * 1024);  // 4MB

    // ---- FPV latency-priority decode config (APFPV) -----------------------------------------
    // Adapted from the PixelPilot-MTK-Optimized fork (thamtrung151). For FPV we prioritise glass-
    // to-glass latency over smoothness: real-time priority, low-latency decode, small buffer queues
    // (less queuing delay), and — on MediaTek — the vendor low-latency key + a high operating-rate.
    // DEFAULT ON — for FPV we prioritise lag: render only the freshest decoded frame (drop the
    // backlog, see checkOutputLoop) + low-latency decode keys. `setprop debug.pixelpilot.mtkopt 0`
    // opts OUT (legacy full-render-in-order). (debug.* is shell-settable on non-rooted devices;
    // a custom persist.* is blocked by SELinux.) Generic keys (low-latency/priority) are harmless on
    // devices that ignore them; the vendor.mtk key is SoC-gated.
    {
        char gate[PROP_VALUE_MAX] = {0};
        char plat[PROP_VALUE_MAX] = {0};
        __system_property_get("debug.pixelpilot.mtkopt", gate);
        __system_property_get("ro.board.platform", plat);
        mLowLatencyOpt   = (gate[0] != '0');                       // default ON (lag priority); opt-out: setprop ... 0
        mIsMtk           = std::string(plat).rfind("mt", 0) == 0;
        const bool isMtk = mIsMtk;
        if (mLowLatencyOpt)
        {
            // MTK-optimized: low-latency decode keys from thamtrung151's fork.
            // NOTE: android._num-input/output-buffers removed — the fork's value of 3
            // throttled the mt6991 decoder to ~60fps at 720p120 (too few buffers to
            // sustain the decode pipeline). The other keys are benign/harmless.
            AMediaFormat_setInt32(format, "low-latency", 1);               // PARAMETER_KEY_LOW_LATENCY
            AMediaFormat_setInt32(format, "vendor.low-latency.enable", 1); // generic vendor low-latency
            AMediaFormat_setInt32(format, "priority", 0);                  // 0 = realtime
            AMediaFormat_setInt32(format, "operating-rate", 240);          // run decoder flat-out
            if (isMtk)
            {
                AMediaFormat_setInt32(format, "vendor.mtk-videodec-low-latency", 1);
            }
            __android_log_print(ANDROID_LOG_INFO, "VideoDecoder",
                "FPV low-latency opt-in ON (mtk=%d, flush=%dms)", (int) isMtk, mFlushThresholdMs);
        }
    }
    // -----------------------------------------------------------------------------------------

    if (IS_H265)
    {
        h265_configureAMediaFormat(mKeyFrameFinder, format);
    }
    else
    {
        h264_configureAMediaFormat(mKeyFrameFinder, format);
    }

    // The OUTPUT_FORMAT_CHANGED event carries no W/H for a Surface-output decoder (the GL fan-out),
    // so the ratio callback never fires there and latestVideoRatio stays 0. Fire it here from the
    // SPS-derived format so the DVR (and anything else) gets the real resolution.
    {
        int32_t fw = 0, fh = 0;
        AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_WIDTH, &fw);
        AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_HEIGHT, &fh);
        __android_log_print(ANDROID_LOG_DEBUG, "GLFanoutDbg", "configure idx=%d fw=%d fh=%d cb=%d", idx, fw, fh,
                            (int) (onDecoderRatioChangedCallback != nullptr));
        if (onDecoderRatioChangedCallback != nullptr && fw != 0 && fh != 0) onDecoderRatioChangedCallback({fw, fh});
    }

    MLOGD << "Configuring decoder:" << AMediaFormat_toString(format);

    auto status = AMediaCodec_configure(decoder.codec[idx], format, decoder.window[idx], nullptr, 0);
    AMediaFormat_delete(format);

    switch (status)
    {
        case AMEDIA_OK:
        {
            MLOGD << "AMediaCodec_configure: OK";
            break;
        }
        case AMEDIA_ERROR_UNKNOWN:
        {
            MLOGD << "AMediaCodec_configure: AMEDIA_ERROR_UNKNOWN";
            break;
        }
        case AMEDIA_ERROR_MALFORMED:
        {
            MLOGD << "AMediaCodec_configure: AMEDIA_ERROR_MALFORMED";
            break;
        }
        case AMEDIA_ERROR_UNSUPPORTED:
        {
            MLOGD << "AMediaCodec_configure: AMEDIA_ERROR_UNSUPPORTED";
            break;
        }
        case AMEDIA_ERROR_INVALID_OBJECT:
        {
            MLOGD << "AMediaCodec_configure: AMEDIA_ERROR_INVALID_OBJECT";
            break;
        }
        case AMEDIA_ERROR_INVALID_PARAMETER:
        {
            MLOGD << "AMediaCodec_configure: AMEDIA_ERROR_INVALID_PARAMETER";
            break;
        }
        default:
        {
            break;
        }
    }

    if (decoder.codec[idx] == nullptr)
    {
        MLOGD << "Cannot configure decoder";
        // set csd-0 and csd-1 back to 0, maybe they were just faulty but we have better luck with the next ones
        // mKeyFrameFinder.reset();
        return;
    }
    AMediaCodec_start(decoder.codec[idx]);
    mCheckOutputThread[idx] = std::make_unique<std::thread>(&VideoDecoder::checkOutputLoop, this, idx);
    NDKThreadHelper::setName(mCheckOutputThread[idx]->native_handle(), "LLDCheckOutput");
    decoder.configured[idx] = true;
}

void VideoDecoder::feedDecoder(const NALU& nalu, int idx)
{
    if (!decoder.codec[idx]) return;
    const auto now          = std::chrono::steady_clock::now();
    const auto deltaParsing = now - nalu.creationTime;
    while (true)
    {
        ssize_t index;
        {
            // Hold the flush-mutex across dequeue+queue so checkOutputLoop's AMediaCodec_flush
            // cannot run concurrently (that races the framework's async input-buffer reply and
            // abort()s at MediaCodec.cpp:4310). Lock scope is bounded by the 5ms dequeue timeout;
            // released before the spin/timeout handling below so flush isn't blocked while we wait.
            std::lock_guard<std::mutex> lk(mCodecFlushMtx[idx]);
            if (!decoder.codec[idx]) return;
            index = AMediaCodec_dequeueInputBuffer(decoder.codec[idx], BUFFER_TIMEOUT_US);
            if (index >= 0)
            {
                size_t   inputBufferSize = 0;
                uint8_t* buf = AMediaCodec_getInputBuffer(decoder.codec[idx], (size_t) index, &inputBufferSize);
                // getInputBuffer can return NULL for a valid index if the codec was flushed or
                // reconfigured concurrently. memcpy into NULL => SIGSEGV. Drop this NALU if gone.
                if (buf == nullptr) return;
                // I have not seen any case where the input buffer returned by MediaCodec is too small to hold the NALU
                // But better be safe than crashing with a memory exception
                if (nalu.getSize() > inputBufferSize)
                {
                    MLOGD << "Nalu too big" << nalu.getSize();
                    return;
                }

                int flag =
                    (IS_H265 && (nalu.isSPS() || nalu.isPPS() || nalu.isVPS())) ? AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG : 0;
                // DIAG: keyframe cadence. H265 IDR = type 19/20, VPS = 32. The dongle freeze is the
                // decoder waiting for an IDR after loss corrupts a reference; the freeze length ≈ the
                // IDR interval. Log the gap between keyframes so we can see the practical GOP.
                if (IS_H265)
                {
                    int nt = nalu.get_nal_unit_type();
                    if (nt == 19 || nt == 20 || nt == 32)
                    {
                        static auto     lastKf = steady_clock::now();
                        const auto      n2     = steady_clock::now();
                        const long long ms     = duration_cast<milliseconds>(n2 - lastKf).count();
                        lastKf                 = n2;
                        __android_log_print(ANDROID_LOG_INFO, "kf-cadence",
                            "keyframe NALU type=%d gap=%lldms size=%d", nt, ms, (int) nalu.getSize());
                    }
                }
                std::memcpy(buf, nalu.getData(), (size_t) nalu.getSize());
                const uint64_t presentationTimeUS =
                    (uint64_t) duration_cast<microseconds>(steady_clock::now().time_since_epoch()).count();
                AMediaCodec_queueInputBuffer(
                    decoder.codec[idx], (size_t) index, 0, (size_t) nalu.getSize(), presentationTimeUS, flag);
                waitForInputB.add(steady_clock::now() - now);
                parsingTime.add(deltaParsing);
                return;
            }
        }
        if (index == AMEDIACODEC_INFO_TRY_AGAIN_LATER)
        {
            // Spin-wait: at 120fps the decode pipeline needs every µs.
            // Sleeping here (even 0.5ms) starves the decoder input at high frame rates.
            // BUT this runs on the shared parse/RX thread — if the decoder's input buffers stay
            // full (a real backlog), blocking here also stops NALU parsing and RX draining, so the
            // UDP socket overflows -> packet loss -> more decode errors (a cascade). For lag
            // priority, cap the wait low and DROP this NALU instead of stalling RX; drop-to-freshest
            // on the output side keeps input buffers freeing quickly so this rarely trips.
            const auto elapsedTimeTryingForBuffer = std::chrono::steady_clock::now() - now;
            if (elapsedTimeTryingForBuffer > std::chrono::milliseconds(100))
            {
                MLOGE << "AMEDIACODEC_INFO_TRY_AGAIN_LATER for >100ms "
                      << MyTimeHelper::R(elapsedTimeTryingForBuffer) << " — drop NALU, unblock RX.";
                return;
            }
        }
        else
        {
            // Something went wrong. But we will feed the next NALU soon anyways
            MLOGD << "dequeueInputBuffer idx " << (int) index << "return.";
            return;
        }
    }
}

void VideoDecoder::checkOutputLoop(int idx)
{
    // FPV latency priority gets a higher (more negative) real-time-ish niceness for the drain thread.
    NDKThreadHelper::setProcessThreadPriorityAttachDetach(javaVm, mLowLatencyOpt ? -20 : -16, "DecoderCheckOutput");
    AMediaCodecBufferInfo info;
    bool                  decoderSawEOS          = false;
    bool                  decoderProducedUnknown = false;
    while (!decoderSawEOS && !decoderProducedUnknown)
    {
        if (!decoder.codec[idx]) break;
        ssize_t index = AMediaCodec_dequeueOutputBuffer(decoder.codec[idx], &info, BUFFER_TIMEOUT_US);
        if (index >= 0)
        {
            // FPV latency priority: render ONLY the freshest decoded frame. On a bursty/lossy link
            // MediaCodec queues several decoded frames; rendering them all in order replays an
            // ever-growing backlog of OLD frames (the "rewind / stuck on old frames" the dongle
            // showed). So if newer output buffers are already ready, drop the stale ones WITHOUT
            // rendering and jump to the latest. No keyframe flush => fps does not collapse and the
            // picture stays live. On a clean link (phone-wifi) nothing newer is waiting, so every
            // frame still renders normally.
            if (mLowLatencyOpt && idx == 0)
            {
                AMediaCodecBufferInfo ni;
                ssize_t               nextIdx;
                // CAP the number of stale buffers dropped per tick. Releasing a big backlog in one
                // tight burst (the dongle's bursty/lossy delivery queues many frames at once) storms
                // the codec2 shared buffer pool and corrupts its page accounting →
                // libstagefright_aidl_bufferpool2 "invalid page index" je_free abort. Draining a few
                // per tick still catches up to the freshest frame within ~1-2 ticks but keeps the
                // release rate sane. phone-Wi-Fi rarely has a backlog so it's unaffected.
                int dropped = 0;
                while (decoder.codec[idx] && dropped < 4
                       && (nextIdx = AMediaCodec_dequeueOutputBuffer(decoder.codec[idx], &ni, 0)) >= 0)
                {
                    AMediaCodec_releaseOutputBuffer(decoder.codec[idx], (size_t) index, false);  // drop stale
                    index = nextIdx;
                    info  = ni;
                    ++dropped;
                }
            }
            const auto    now   = steady_clock::now();
            const int64_t nowUS = (int64_t) duration_cast<microseconds>(now.time_since_epoch()).count();
            // the timestamp for releasing the buffer is in NS, just release as fast as possible (e.g. now)
            // https://android.googlesource.com/platform/frameworks/av/+/master/media/ndk/NdkMediaCodec.cpp
            //-> renderOutputBufferAndRelease which is in
            // https://android.googlesource.com/platform/frameworks/av/+/3fdb405/media/libstagefright/MediaCodec.cpp
            //-> Message kWhatReleaseOutputBuffer -> onReleaseOutputBuffer
            //  also https://android.googlesource.com/platform/frameworks/native/+/5c1139f/libs/gui/SurfaceTexture.cpp
            if (!decoder.codec[idx]) break;
            // If a decoded frame is older than the flush threshold (UI setting, default 60ms),
            // the decoder is backlogged — flush to the next keyframe to cap latency.
            if (idx == 0 && mFlushThresholdMs > 0 &&
                (nowUS - info.presentationTimeUs) > (int64_t)mFlushThresholdMs * 1000)
            {
                // Serialize against feedDecoder's input dequeue (see mCodecFlushMtx) — concurrent
                // flush + dequeueInputBuffer abort()s in the MediaCodec framework.
                std::lock_guard<std::mutex> lk(mCodecFlushMtx[idx]);
                if (decoder.codec[idx]) AMediaCodec_flush(decoder.codec[idx]);
                continue;
            }
            AMediaCodec_releaseOutputBuffer(decoder.codec[idx], (size_t) index, true);
            // but the presentationTime is in US
            if (idx == 0)
            {
                decodingTime.add(std::chrono::microseconds(nowUS - info.presentationTimeUs));
                nDecodedFrames.add(1);
            }
            if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM)
            {
                MLOGD << "Decoder saw EOS";
                decoderSawEOS = true;
                continue;
            }
        }
        else if (index == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED)
        {
            auto format = AMediaCodec_getOutputFormat(decoder.codec[idx]);
            int  width = 0, height = 0;
            AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_WIDTH, &width);
            AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_HEIGHT, &height);
            MLOGD << "Actual Width and Height in output " << width << "," << height;
            if (idx == 0 && onDecoderRatioChangedCallback != nullptr && width != 0 && height != 0)
            {
                onDecoderRatioChangedCallback({width, height});
            }
            MLOGD << "AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED " << width << " " << height << " "
                  << AMediaFormat_toString(format);
        }
        else if (index == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED)
        {
            MLOGD << "AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED";
        }
        else if (index == AMEDIACODEC_INFO_TRY_AGAIN_LATER)
        {
            // MLOGD<<"AMEDIACODEC_INFO_TRY_AGAIN_LATER";
        }
        else
        {
            // Most like AMediaCodec_stop() was called
            MLOGD << "dequeueOutputBuffer idx: " << (int) index << " .Exit.";
            decoderProducedUnknown = true;
            continue;
        }
        // every 2 seconds recalculate the current fps and bitrate
        const auto now   = steady_clock::now();
        const auto delta = now - decodingInfo.lastCalculation;
        if (idx == 0 && delta > DECODING_INFO_RECALCULATION_INTERVAL)
        {
            decodingInfo.lastCalculation = steady_clock::now();
            decodingInfo.currentFPS =
                (float) nDecodedFrames.getDeltaSinceLastCall() / (float) duration_cast<seconds>(delta).count();
            decodingInfo.currentKiloBitsPerSecond =
                ((float) nNALUBytesFed.getDeltaSinceLastCall() / duration_cast<seconds>(delta).count()) / 1024.0f *
                8.0f;
            // and recalculate the avg latencies. If needed,also print the log.
            decodingInfo.avgDecodingTime_ms      = decodingTime.getAvg_ms();
            decodingInfo.avgParsingTime_ms       = parsingTime.getAvg_ms();
            decodingInfo.avgWaitForInputBTime_ms = waitForInputB.getAvg_ms();
            decodingInfo.nDecodedFrames          = nDecodedFrames.getAbsolute();
            printAvgLog();
            if (onDecodingInfoChangedCallback != nullptr)
            {
                onDecodingInfoChangedCallback(decodingInfo);
            }
        }
    }
    MLOGD << "Exit CheckOutputLoop";
}

void VideoDecoder::printAvgLog()
{
    if (PRINT_DEBUG_INFO)
    {
        auto now = steady_clock::now();
        if ((now - lastLog) > TIME_BETWEEN_LOGS)
        {
            lastLog = now;
            std::ostringstream frameLog;
            frameLog << std::fixed;
            float avgDecodingLatencySum =
                decodingInfo.avgParsingTime_ms + decodingInfo.avgWaitForInputBTime_ms + decodingInfo.avgDecodingTime_ms;
            frameLog << "......................Decoding Latency Averages......................"
                     << "\nParsing:" << decodingInfo.avgParsingTime_ms
                     << " | WaitInputBuffer:" << decodingInfo.avgWaitForInputBTime_ms
                     << " | Decoding:" << decodingInfo.avgDecodingTime_ms
                     << " | Decoding Latency Sum:" << avgDecodingLatencySum << "\nN NALUS:" << decodingInfo.nNALU
                     << " | N NALUES feeded:" << decodingInfo.nNALUSFeeded
                     << " | N Decoded Frames:" << nDecodedFrames.getAbsolute() << "\nFPS:" << decodingInfo.currentFPS
                     << " | Codec:" << (decodingInfo.nCodec ? "H265" : "H264");
            MLOGD << frameLog.str();
        }
    }
}

void VideoDecoder::resetStatistics()
{
    nDecodedFrames.reset();
    nNALUBytesFed.reset();
    parsingTime.reset();
    waitForInputB.reset();
    decodingTime.reset();
    decodingInfo = {};
}
