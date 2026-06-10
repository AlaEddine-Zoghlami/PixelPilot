#include "VideoPlayer.h"
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <fstream>
#include "AndroidThreadPrioValues.hpp"
#include "helper/NDKHelper.hpp"
#include "helper/NDKThreadHelper.hpp"

#define TAG "pixelpilot"

VideoPlayer::VideoPlayer(JNIEnv* env, jobject context)
    : mParser{std::bind(&VideoPlayer::onNewNALU, this, std::placeholders::_1)}, videoDecoder(env)
{
    env->GetJavaVM(&javaVm);
    videoDecoder.registerOnDecoderRatioChangedCallback(
        [this](const VideoRatio ratio)
        {
            const bool changed      = ratio != this->latestVideoRatio;
            this->latestVideoRatio  = ratio;
            latestVideoRatioChanged = changed;
        });
    videoDecoder.registerOnDecodingInfoChangedCallback(
        [this](const DecodingInfo info)
        {
            const bool changed        = info != this->latestDecodingInfo;
            this->latestDecodingInfo  = info;
            latestDecodingInfoChanged = changed;
        });
}

static int write_callback(int64_t offset, const void* buffer, size_t size, void* token)
{
    FILE* f = (FILE*) token;
    fseek(f, offset, SEEK_SET);
    return fwrite(buffer, 1, size, f) != size;
}

// --- GL fan-out DVR mp4 writer. minimp4's MINIMP4_IMPLEMENTATION lives in this TU only (its guard
// is disabled), so the GL-fan-out JNI calls these via extern "C" rather than re-including minimp4.h.
// The fan-out encoder's NALUs (video, + the OSD when recordOsd) are muxed straight to the .mp4 fd.
namespace {
struct GlDvrW
{
    FILE*             fout     = nullptr;
    MP4E_mux_t*       mux      = nullptr;
    mp4_h26x_writer_t wr{};
    int               fps      = 30;
    bool              wrInited = false;
};
}  // namespace
extern "C" void* glfanout_dvr_start(int fd, int w, int h, int fps, int fmp4, int h265)
{
    auto* d = new GlDvrW();
    d->fps  = fps > 0 ? fps : 30;
    // dup the fd: the caller's fd is owned by the Java ParcelFileDescriptor (fdsan), so fdopen
    // taking ownership SIGABRTs. Same as the legacy DVR (dvr_fd = dup(fd)). fclose closes our dup.
    int dupfd = dup(fd);
    if (dupfd < 0) { delete d; return nullptr; }
    d->fout = fdopen(dupfd, "wb");
    if (!d->fout) { close(dupfd); delete d; return nullptr; }
    d->mux = MP4E_open(0 /*sequential*/, fmp4 ? 1 : 0, d->fout, write_callback);
    if (!d->mux) { fclose(d->fout); delete d; return nullptr; }
    if (mp4_h26x_write_init(&d->wr, d->mux, w, h, h265 != 0) != MP4E_STATUS_OK)
    {
        MP4E_close(d->mux); fclose(d->fout); delete d; return nullptr;
    }
    d->wrInited = true;
    return d;
}
extern "C" void glfanout_dvr_write(void* dvr, const uint8_t* data, size_t size)
{
    auto* d = reinterpret_cast<GlDvrW*>(dvr);
    static int cnt = 0;
    if (cnt < 6) __android_log_print(ANDROID_LOG_DEBUG, "GLFanoutDbg", "dvr_write #%d sz=%zu d=%p", cnt, size, (void*) d);
    cnt++;
    if (d && d->wrInited)
    {
        int r = mp4_h26x_write_nal(&d->wr, data, size, 90000 / (d->fps > 0 ? d->fps : 30));
        if (cnt < 6) __android_log_print(ANDROID_LOG_DEBUG, "GLFanoutDbg", "  write_nal r=%d", r);
    }
}
extern "C" void glfanout_dvr_stop(void* dvr)
{
    auto* d = reinterpret_cast<GlDvrW*>(dvr);
    if (!d) return;
    if (d->wrInited) mp4_h26x_write_close(&d->wr);
    if (d->mux)  MP4E_close(d->mux);
    if (d->fout) fclose(d->fout);
    delete d;
}

void VideoPlayer::processQueue()
{
    ::FILE*           fout = fdopen(dvr_fd, "wb");
    MP4E_mux_t*       mux  = MP4E_open(0 /*sequential_mode*/, dvr_mp4_fragmentation, fout, write_callback);
    mp4_h26x_writer_t mp4wr;
    float             framerate = 0;
    if (mux == nullptr)
    {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "dvr open failed");
        return;
    }
    audioTrackId = -1;  // fresh recording: the Opus audio track is added lazily on the first packet

    while (true)
    {
        last_dvr_write = get_time_ms();
        std::unique_lock<std::mutex> lock(mtx);
        cv.wait(lock, [this] { return !naluQueue.empty() || !audioRecQueue.empty() || stopFlag; });
        if (stopFlag)
        {
            break;
        }
        if (!naluQueue.empty())
        {
            NALU nalu = naluQueue.front();
            if (framerate == 0)
            {
                if (latestDecodingInfo.currentFPS <= 0)
                {
                    continue;
                }
                if (MP4E_STATUS_OK !=
                    mp4_h26x_write_init(
                        &mp4wr, mux, latestVideoRatio.width, latestVideoRatio.height, nalu.IS_H265_PACKET))
                {
                    __android_log_print(ANDROID_LOG_DEBUG, TAG, "error: mp4_h26x_write_init failed");
                }
                framerate = latestDecodingInfo.currentFPS;
                __android_log_print(
                    ANDROID_LOG_DEBUG,
                    TAG,
                    "mp4 init with fps=%.2f, res=%dx%d, hevc=%d",
                    framerate,
                    latestVideoRatio.width,
                    latestVideoRatio.height,
                    nalu.IS_H265_PACKET);
            }
            naluQueue.pop();
            lock.unlock();
            // Process the NALU
            auto res = mp4_h26x_write_nal(&mp4wr, nalu.getData(), nalu.getSize(), 90000 / framerate);
            if (MP4E_STATUS_OK != res)
            {
                __android_log_print(ANDROID_LOG_DEBUG, TAG, "mp4_h26x_write_nal failed with %d", res);
            }
        }
        // Mux any queued Opus audio into the same MP4 (audio track added lazily on first packet).
        if (!lock.owns_lock())
        {
            lock.lock();
        }
        while (!audioRecQueue.empty())
        {
            AudioRecPacket ap = std::move(audioRecQueue.front());
            audioRecQueue.pop();
            lock.unlock();
            if (audioTrackId < 0)
            {
                MP4E_track_t at           = {};
                at.object_type_indication = MP4_OBJECT_TYPE_AUDIO_OPUS;
                at.track_media_kind       = e_audio;
                at.time_scale             = 48000;
                at.u.a.channelcount       = 1;
                at.language[0] = 'u'; at.language[1] = 'n'; at.language[2] = 'd'; at.language[3] = 0;
                audioTrackId = MP4E_add_track(mux, &at);
                __android_log_print(ANDROID_LOG_DEBUG, TAG, "dvr: Opus audio track id=%d", audioTrackId);
            }
            if (audioTrackId >= 0)
            {
                MP4E_put_sample(mux, audioTrackId, ap.data.data(), (int) ap.data.size(),
                                ap.durationSamples, MP4E_SAMPLE_RANDOM_ACCESS);
            }
            lock.lock();
        }
    }

    MP4E_close(mux);
    mp4_h26x_write_close(&mp4wr);
    if (fout)
    {
        fclose(fout);
        fout = NULL;
    }
    dvr_fd = -1;
    __android_log_print(ANDROID_LOG_DEBUG, TAG, "dvr thread done");
}

// Not yet parsed bit stream (e.g. raw h264 or rtp data)
void VideoPlayer::onNewRTPData(const uint8_t* data, const std::size_t data_length)
{
    // Parse the RTP packet
    const RTP::RTPPacket rtpPacket(data, data_length);
    uint16_t             idx = rtpPacket.header.getSequence();

    // Define the callback based on payload type
    auto callback = [&](const uint8_t* packet_data, std::size_t packet_length)
    {
        if (rtpPacket.header.payload == RTP_PAYLOAD_TYPE_AUDIO)
        {
            audioDecoder.enqueueAudio(packet_data, packet_length);
            // Also capture the raw Opus payload for the DVR recording when one is active.
            if (isRecording() && packet_length > 12)
            {
                const uint8_t* op    = packet_data + 12;  // strip RTP header (matches AudioDecoder)
                int            oplen = (int) packet_length - 12;
                int            fs    = opus_packet_get_samples_per_frame(op, 48000);
                int            nf    = opus_packet_get_nb_frames(op, oplen);
                enqueueAudioForRecording(op, oplen, (fs > 0 && nf > 0) ? fs * nf : 960);
            }
        }
        else
        {
            mParser.parse_rtp_stream(packet_data, packet_length);
        }
    };

    // Process the packet using the queue
    if (rtpPacket.header.payload == RTP_PAYLOAD_TYPE_AUDIO)
    {
        mBufferedPacketQueueAudio.processPacket(idx, data, data_length, callback);
    }
    else
    {
        mBufferedPacketQueueVideo.processPacket(idx, data, data_length, callback);
    }
}

void VideoPlayer::onNewNALU(const NALU& nalu)
{
    videoDecoder.interpretNALU(nalu);
    if (dvr_fd <= 0 || latestDecodingInfo.currentFPS <= 0)
    {
        return;
    }
    // Decoded-stream DVR: re-encode the DECODED frames (always complete) instead of
    // muxing the raw, possibly-torn wire NALUs. The transcoder decodes -> re-encodes;
    // its callback enqueues clean NALUs to the SAME minimp4 writer (processQueue).
    if (!dvrTranscoder.isRunning() && latestVideoRatio.width > 0)
    {
        dvrTranscoder.start(latestVideoRatio.width, latestVideoRatio.height, nalu.IS_H265_PACKET,
                            (int) latestDecodingInfo.currentFPS, 8000000, nullptr, 0,
                            [this](const uint8_t* d, size_t s, bool h265)
                            {
                                uint8_t* cpy = new uint8_t[s];
                                memcpy(cpy, d, s);
                                NALU n(cpy, s, h265);
                                enqueueNALU(n);
                            },
                            nalu.IS_H265_PACKET);
    }
    dvrTranscoder.feedNALU(nalu);  // no-op until the transcoder is up; never touches the live decode
}

void VideoPlayer::setVideoSurface(JNIEnv* env, jobject surface, jint i)
{
    // reset the parser so the statistics start again from 0
    //  mParser.reset();
    // set the jni object for settings
    videoDecoder.setOutputSurface(env, surface, i);
}

void VideoPlayer::start(JNIEnv* env, jobject androidContext)
{
    AAssetManager* assetManager = NDKHelper::getAssetManagerFromContext2(env, androidContext);
    // mParser.setLimitFPS(-1); //Default: Real time !
    const int VS_PORT = 5600;
    mUDPReceiver.release();
    mUDPReceiver = std::make_unique<UDPReceiver>(
        javaVm,
        VS_PORT,
        "UdpReceiver",
        -16,
        [this](const uint8_t* data, size_t data_length) { onNewRTPData(data, data_length); },
        WANTED_UDP_RCVBUF_SIZE);
    mUDPReceiver->startReceiving();

    mUDSReceiver.release();
    // build the abstract socket name ("\0my_socket")
    auto udsName = std::string("\0my_socket", sizeof("\0my_socket") - 1);

    // now construct your receiver with that
    mUDSReceiver = std::make_unique<UDSReceiver>(
        javaVm,
        udsName,   // abstract socket name
        "UDS‑Rx",  // thread name
        -16,       // Android priority
        [this](const uint8_t* data, size_t data_length) { onNewRTPData(data, data_length); },
        WANTED_UDP_RCVBUF_SIZE  // your desired recv‑buffer size
    );

    mUDSReceiver->startReceiving();
}

void VideoPlayer::stop(JNIEnv* env, jobject androidContext)
{
    if (mUDPReceiver)
    {
        mUDPReceiver->stopReceiving();
        mUDPReceiver.reset();
    }
    if (mUDSReceiver)
    {
        mUDSReceiver->stopReceiving();
        mUDSReceiver.reset();
    }

    audioDecoder.stopAudio();
}

std::string VideoPlayer::getInfoString() const
{
    std::stringstream ss;
    if (mUDPReceiver)
    {
        ss << "Listening for video on port " << mUDPReceiver->getPort();
        ss << "\nReceived: " << mUDPReceiver->getNReceivedBytes() << "B"
           << " | parsed frames: ";
        // << mParser.nParsedNALUs << " | key frames: " << mParser.nParsedKonfigurationFrames;
    }
    else if (mUDSReceiver)
    {
        ss << "Listening for video on socket " << mUDSReceiver->getSourcePath();
        ss << "\nReceived: " << mUDSReceiver->getNReceivedBytes() << "B"
           << " | parsed frames: ";
        // << mParser.nParsedNALUs << " | key frames: " << mParser.nParsedKonfigurationFrames;
    }
    else
    {
        ss << "Not receiving udp raw / rtp / rtsp";
    }
    return ss.str();
}

void VideoPlayer::startDvr(JNIEnv* env, jint fd, jint dvr_fmp4_enabled)
{
    dvr_fd                = dup(fd);
    dvr_mp4_fragmentation = dvr_fmp4_enabled;
    __android_log_print(ANDROID_LOG_DEBUG, TAG, "dvr_fd=%d", dvr_fd);
    if (dvr_fd == -1)
    {
        __android_log_print(ANDROID_LOG_DEBUG, TAG, "Failed to duplicate dvr file descriptor");
        return;
    }
    startProcessing();
}

void VideoPlayer::stopDvr()
{
    __android_log_print(ANDROID_LOG_DEBUG, TAG, "Stop dvr");
    dvrTranscoder.stop();
    stopProcessing();
}

//----------------------------------------------------JAVA
// bindings---------------------------------------------------------------
#define JNI_METHOD(return_type, method_name) \
    JNIEXPORT return_type JNICALL Java_com_openipc_videonative_VideoPlayer_##method_name

inline jlong jptr(VideoPlayer* videoPlayerN)
{
    return reinterpret_cast<intptr_t>(videoPlayerN);
}

inline VideoPlayer* native(jlong ptr)
{
    return reinterpret_cast<VideoPlayer*>(ptr);
}

extern "C"
{
    extern "C" JNIEXPORT jlong JNICALL
    Java_com_openipc_videonative_VideoPlayer_nativeInitialize(JNIEnv* env, jclass clazz, jobject context)
    {
        auto* p = new VideoPlayer(env, context);
        return jptr(p);
    }

    JNI_METHOD(void, nativeFinalize)
    (JNIEnv* env, jclass jclass1, jlong videoPlayerN)
    {
        VideoPlayer* p = native(videoPlayerN);
        delete (p);
    }

    JNI_METHOD(void, nativeStart)
    (JNIEnv* env, jclass jclass1, jlong videoPlayerN, jobject androidContext)
    {
        native(videoPlayerN)->start(env, androidContext);
    }

    JNI_METHOD(void, nativeStop)
    (JNIEnv* env, jclass jclass1, jlong videoPlayerN, jobject androidContext)
    {
        native(videoPlayerN)->stop(env, androidContext);
    }

    JNI_METHOD(void, nativeSetVideoSurface)
    (JNIEnv* env, jclass jclass1, jlong videoPlayerN, jobject surface, jint index)
    {
        native(videoPlayerN)->setVideoSurface(env, surface, index);
    }

    JNI_METHOD(jstring, getVideoInfoString)
    (JNIEnv* env, jclass jclass1, jlong testReceiverN)
    {
        VideoPlayer* p   = native(testReceiverN);
        jstring      ret = env->NewStringUTF(p->getInfoString().c_str());
        return ret;
    }

    JNI_METHOD(jboolean, anyVideoDataReceived)
    (JNIEnv* env, jclass jclass1, jlong testReceiverN)
    {
        VideoPlayer* p = native(testReceiverN);

        bool ret{false};

        if (p->mUDPReceiver != nullptr)
        {
            ret |= (p->mUDPReceiver->getNReceivedBytes() > 0);
        }
        if (p->mUDSReceiver != nullptr)
        {
            ret |= (p->mUDSReceiver->getNReceivedBytes() > 0);
        }

        return (jboolean) ret;
    }

    JNI_METHOD(jboolean, receivingVideoButCannotParse)
    (JNIEnv* env, jclass jclass1, jlong testReceiverN)
    {
        VideoPlayer* p = native(testReceiverN);
        //    if(p->mUDPReceiver){
        //        return (jboolean) (p->mUDPReceiver->getNReceivedBytes() > 1024 * 1024 && p->mParser.nParsedNALUs ==
        //        0);
        //    }
        return (jboolean) false;
    }

    JNI_METHOD(jboolean, anyVideoBytesParsedSinceLastCall)
    (JNIEnv* env, jclass jclass1, jlong testReceiverN)
    {
        VideoPlayer* p              = native(testReceiverN);
        long         nalusSinceLast = 0;  // p->mParser.nParsedNALUs - p->nNALUsAtLastCall;
        p->nNALUsAtLastCall += nalusSinceLast;
        return (jboolean) (nalusSinceLast > 0);
    }

    JNI_METHOD(void, nativeCallBack)
    (JNIEnv* env, jclass jclass1, jobject videoParamsChangedI, jlong testReceiverN)
    {
        VideoPlayer* p = native(testReceiverN);
        // Update all java stuff
        if (p->latestDecodingInfoChanged || p->latestVideoRatioChanged)
        {
            jclass jClassExtendsIVideoParamsChanged = env->GetObjectClass(videoParamsChangedI);
            if (p->latestVideoRatioChanged)
            {
                jmethodID onVideoRatioChangedJAVA =
                    env->GetMethodID(jClassExtendsIVideoParamsChanged, "onVideoRatioChanged", "(II)V");
                env->CallVoidMethod(
                    videoParamsChangedI,
                    onVideoRatioChangedJAVA,
                    (jint) p->latestVideoRatio.width,
                    (jint) p->latestVideoRatio.height);
                p->latestVideoRatioChanged = false;
            }
            if (p->latestDecodingInfoChanged)
            {
                jclass jcDecodingInfo = env->FindClass("com/openipc/videonative/DecodingInfo");
                assert(jcDecodingInfo != nullptr);
                jmethodID jcDecodingInfoConstructor = env->GetMethodID(jcDecodingInfo, "<init>", "(FFFFFIIII)V");
                assert(jcDecodingInfoConstructor != nullptr);
                const auto info         = p->latestDecodingInfo;
                auto       decodingInfo = env->NewObject(
                    jcDecodingInfo,
                    jcDecodingInfoConstructor,
                    (jfloat) info.currentFPS,
                    (jfloat) info.currentKiloBitsPerSecond,
                    (jfloat) info.avgParsingTime_ms,
                    (jfloat) info.avgWaitForInputBTime_ms,
                    (jfloat) info.avgDecodingTime_ms,
                    (jint) info.nNALU,
                    (jint) info.nNALUSFeeded,
                    (jint) info.nDecodedFrames,
                    (jint) info.nCodec);
                assert(decodingInfo != nullptr);
                jmethodID onDecodingInfoChangedJAVA = env->GetMethodID(
                    jClassExtendsIVideoParamsChanged,
                    "onDecodingInfoChanged",
                    "(Lcom/openipc/videonative/DecodingInfo;)V");
                assert(onDecodingInfoChangedJAVA != nullptr);
                env->CallVoidMethod(videoParamsChangedI, onDecodingInfoChangedJAVA, decodingInfo);
                p->latestDecodingInfoChanged = false;
            }
        }
    }
}

extern "C" JNIEXPORT void JNICALL Java_com_openipc_videonative_VideoPlayer_nativeStartDvr(
    JNIEnv* env, jclass clazz, jlong native_instance, jint fd, jint fmp4_enabled)
{
    native(native_instance)->startDvr(env, fd, fmp4_enabled);
}

extern "C" JNIEXPORT void JNICALL
Java_com_openipc_videonative_VideoPlayer_nativeStopDvr(JNIEnv* env, jclass clazz, jlong native_instance)
{
    native(native_instance)->stopDvr();
}

// Reliable video params straight from the decoder. The IVideoParamsChanged Java callbacks don't
// reach VideoActivity (its onVideoRatioChanged never fires), so the GL fan-out DVR reads here.
extern "C" JNIEXPORT jint JNICALL
Java_com_openipc_videonative_VideoPlayer_nativeGetVideoWidth(JNIEnv*, jclass, jlong ni)
{ return (jint) native(ni)->latestVideoRatio.width; }
extern "C" JNIEXPORT jint JNICALL
Java_com_openipc_videonative_VideoPlayer_nativeGetVideoHeight(JNIEnv*, jclass, jlong ni)
{ return (jint) native(ni)->latestVideoRatio.height; }
extern "C" JNIEXPORT jint JNICALL
Java_com_openipc_videonative_VideoPlayer_nativeGetVideoFps(JNIEnv*, jclass, jlong ni)
{ return (jint) native(ni)->latestDecodingInfo.currentFPS; }
extern "C" JNIEXPORT jint JNICALL
Java_com_openipc_videonative_VideoPlayer_nativeGetVideoCodec(JNIEnv*, jclass, jlong ni)
{ return (jint) native(ni)->latestDecodingInfo.nCodec; }

extern "C" JNIEXPORT jboolean JNICALL
Java_com_openipc_videonative_VideoPlayer_nativeIsRecording(JNIEnv* env, jclass clazz, jlong native_instance)
{
    return native(native_instance)->isRecording();
}
extern "C" JNIEXPORT void JNICALL
Java_com_openipc_videonative_VideoPlayer_nativeStartAudio(JNIEnv* env, jclass clazz, jlong native_instance)
{
    if (!native(native_instance)->audioDecoder.isInit)
    {
        native(native_instance)->audioDecoder.initAudio();
    }
    native(native_instance)->audioDecoder.stopAudioProcessing();
    native(native_instance)->audioDecoder.startAudioProcessing();
}
extern "C" JNIEXPORT void JNICALL
Java_com_openipc_videonative_VideoPlayer_nativeStopAudio(JNIEnv* env, jclass clazz, jlong native_instance)
{
    native(native_instance)->audioDecoder.stopAudioProcessing();
}
