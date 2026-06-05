// ============================================================================
//  apfpv_jni.cpp — JNI bindings: ApfpvStaLink.java  <->  native ApfpvStation
//  This is the REAL connective tissue: PixelPilot's Java calls these; they
//  drive the forked devourer station stack. Mirrors the existing WfbNgLink JNI
//  registration pattern (libusb fd via wrap_sys_device -> RtlUsbAdapter).
//  Goes in PixelPilot app/wfbngrtl8812/src/main/cpp/ alongside the wfb JNI.
// ============================================================================
#include <jni.h>
#include <libusb.h>
#include <memory>
#include <string>
#include <android/log.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <cstring>

#include "ApfpvStation.h"
#include "WiFiDriver.h"
#include "RtlUsbAdapter.h"
#include "RtlJaguarDevice.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "apfpv-jni", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "apfpv-jni", __VA_ARGS__)

using namespace apfpv;

// One station per app instance (APFPV uses a single link).
struct StaCtx {
    libusb_context*               usb = nullptr;
    libusb_device_handle*         handle = nullptr;
    std::unique_ptr<WiFiDriver>   driver;
    std::unique_ptr<RtlJaguarDevice> rtl;   // owns adapter + radio manager
    std::unique_ptr<ApfpvStation> station;
    JavaVM*                       jvm = nullptr;
    jobject                       jlink = nullptr;   // global ref to ApfpvStaLink
    int                           rtpSock = -1;     // UDP -> 127.0.0.1:5600
    sockaddr_in                   rtpDst{};
};

// Forward state to Java (onNativeState). The state callback can fire either on
// an internal worker thread (not attached to the JVM) OR synchronously on the
// thread that called nativeStaConnect (already attached, owned by ART). We must
// only Attach/Detach a thread WE attached — unconditionally detaching the
// caller's thread is what aborted the app (SIGABRT via ART) after libusb init.
static void postState(StaCtx* ctx, ApfpvStation::State s) {
    if (!ctx || !ctx->jvm || !ctx->jlink) return;
    JNIEnv* env = nullptr;
    bool weAttached = false;
    jint r = ctx->jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (r == JNI_EDETACHED) {
        if (ctx->jvm->AttachCurrentThread(&env, nullptr) != JNI_OK || !env) return;
        weAttached = true;
    } else if (r != JNI_OK || !env) {
        return;
    }
    jclass cls = env->GetObjectClass(ctx->jlink);
    if (cls) {
        jmethodID m = env->GetMethodID(cls, "onNativeState", "(I)V");
        if (m) env->CallVoidMethod(ctx->jlink, m, (jint)s);
        env->DeleteLocalRef(cls);
    }
    if (weAttached) ctx->jvm->DetachCurrentThread();  // never detach the caller's thread
}

// Build the device + station ONCE and keep it for the ctx's lifetime. Both the
// scan and the connect reuse it — rebuilding it (the old code did) destroyed a
// device whose RX read thread was still running, so that thread then locked a
// freed mutex -> 'pthread_mutex_lock on a destroyed mutex' SIGABRT.
static bool ensureStation(StaCtx* ctx) {
    if (ctx->station) return true;
    if (!ctx->usb || !ctx->handle) return false;
    if (ctx->rtpSock < 0) {
        ctx->rtpSock = ::socket(AF_INET, SOCK_DGRAM, 0);
        std::memset(&ctx->rtpDst, 0, sizeof(ctx->rtpDst));
        ctx->rtpDst.sin_family = AF_INET;
        ctx->rtpDst.sin_port = htons(5600);
        ::inet_pton(AF_INET, "127.0.0.1", &ctx->rtpDst.sin_addr);
    }
    StaCtx* c = ctx;
    auto onRtp = [c](const uint8_t* rtp, size_t len) {
        if (c->rtpSock >= 0 && rtp && len)
            ::sendto(c->rtpSock, rtp, len, 0, (sockaddr*)&c->rtpDst, sizeof(c->rtpDst));
    };
    auto onState = [c](ApfpvStation::State s){ postState(c, s); };
    try {
        ctx->driver = std::make_unique<WiFiDriver>(nullptr /*Logger_t*/);
        ctx->rtl = ctx->driver->CreateRtlDevice(ctx->handle);
        if (!ctx->rtl) { LOGE("CreateRtlDevice returned null"); return false; }
        ctx->station = std::make_unique<ApfpvStation>(
            &ctx->rtl->adapter(), &ctx->rtl->radioManager(), onRtp, onState);
        ctx->station->setDevice(ctx->rtl.get());
    } catch (...) { LOGE("ensureStation: device setup threw"); return false; }
    return true;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_openipc_wfbngrtl8812_ApfpvStaLink_nativeStaInitialize(JNIEnv* env, jclass, jobject /*context*/) {
    auto* ctx = new StaCtx();
    env->GetJavaVM(&ctx->jvm);

    // Unrooted Android: we adopt a USB fd from the Java UsbManager, so libusb must
    // NOT try to enumerate /dev/bus/usb. This option MUST be set BEFORE libusb_init
    // (exactly as the WFB path does) — otherwise init fails on the device scan and
    // leaves ctx->usb null, and the later libusb_wrap_sys_device dereferences that
    // null context (the SIGSEGV at libusb1.0.so seen when switching to APFPV).
    libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    int r = libusb_init(&ctx->usb);
    if (r < 0 || !ctx->usb) {
        LOGE("libusb_init failed (%d) — APFPV station unavailable", r);
        ctx->usb = nullptr;
    }
    LOGI("ApfpvStation ctx initialized (usb=%p)", (void*)ctx->usb);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_openipc_wfbngrtl8812_ApfpvStaLink_nativeStaConnect(
        JNIEnv* env, jclass, jlong inst, jobject jlink, jint fd,
        jint channel, jint bandwidth, jstring jssid, jstring jpass) {
    auto* ctx = reinterpret_cast<StaCtx*>(inst);
    if (!ctx) return;
    if (!ctx->jlink) ctx->jlink = env->NewGlobalRef(jlink);

    // libusb must have initialized in nativeStaInitialize (option set before init).
    // If it didn't, bail with a FAIL state instead of dereferencing a null context.
    if (!ctx->usb) {
        LOGE("libusb context null — cannot connect"); postState(ctx, ApfpvStation::State::FailNoAp); return;
    }
    // Unrooted USB-host path (same as the wfb monitor path): adopt the fd ONCE.
    // The scan may already have wrapped it — don't re-wrap (that leaks handles).
    if (!ctx->handle) {
        if (libusb_wrap_sys_device(ctx->usb, (intptr_t)fd, &ctx->handle) < 0 || !ctx->handle) {
            LOGE("libusb_wrap_sys_device failed (fd=%d)", fd);
            postState(ctx, ApfpvStation::State::FailNoAp); return;
        }
    }

    // Build (or reuse) the device + station — same instance the scan used, so we
    // never destroy a device whose RX thread is still running.
    if (!ensureStation(ctx)) { postState(ctx, ApfpvStation::State::FailNoAp); return; }

    const char* ssid = env->GetStringUTFChars(jssid, nullptr);
    const char* pass = env->GetStringUTFChars(jpass, nullptr);
    try {
        // Stop any prior scan/supervisor on this device before a fresh connect.
        ctx->station->disconnect();
        ApfpvStation::Params p;
        p.channel = channel; p.bandwidth = bandwidth;
        p.ssid = ssid; p.passphrase = pass; p.lqFeedback = true;
        ctx->station->connect(p);     // runs the gated chain; states -> Java
    } catch (const std::exception& e) {
        LOGE("APFPV connect threw: %s", e.what());
        postState(ctx, ApfpvStation::State::FailNoAp);
    } catch (...) {
        LOGE("APFPV connect threw an unknown exception");
        postState(ctx, ApfpvStation::State::FailNoAp);
    }

    env->ReleaseStringUTFChars(jssid, ssid);
    env->ReleaseStringUTFChars(jpass, pass);
}

JNIEXPORT void JNICALL
Java_com_openipc_wfbngrtl8812_ApfpvStaLink_nativeStaDisconnect(JNIEnv*, jclass, jlong inst, jint /*fd*/) {
    auto* ctx = reinterpret_cast<StaCtx*>(inst);
    if (ctx && ctx->station) ctx->station->disconnect();
}

// All-SSID scan for the picker UI. Adopts the fd + builds the device/station if
// needed (scan happens BEFORE connect), then channel-hops collecting beacons and
// calls Java onNativeScanResult(ssid, channel, rssi) per discovered SSID.
// BLOCKS for the whole sweep — Java must call this on a worker thread.
JNIEXPORT void JNICALL
Java_com_openipc_wfbngrtl8812_ApfpvStaLink_nativeStaScan(
        JNIEnv* env, jclass, jlong inst, jobject jlink, jint fd, jint perChannelMs,
        jboolean includeDfs) {
    auto* ctx = reinterpret_cast<StaCtx*>(inst);
    if (!ctx || !ctx->usb) return;
    if (!ctx->jlink) ctx->jlink = env->NewGlobalRef(jlink);
    if (!ctx->handle) {
        if (libusb_wrap_sys_device(ctx->usb, (intptr_t)fd, &ctx->handle) < 0 || !ctx->handle) {
            LOGE("scan: libusb_wrap_sys_device failed (fd=%d)", fd); return;
        }
    }
    if (!ensureStation(ctx)) { LOGE("scan: ensureStation failed"); return; }
    jobject link = ctx->jlink;
    auto onAp = [ctx, link](const std::string& ssid, const ApInfo& info) {
        if (!ctx->jvm || !link) return;
        JNIEnv* e = nullptr; bool att = false;
        jint r = ctx->jvm->GetEnv(reinterpret_cast<void**>(&e), JNI_VERSION_1_6);
        if (r == JNI_EDETACHED) { if (ctx->jvm->AttachCurrentThread(&e, nullptr) != JNI_OK || !e) return; att = true; }
        else if (r != JNI_OK || !e) return;
        jclass cls = e->GetObjectClass(link);
        if (cls) {
            jmethodID m = e->GetMethodID(cls, "onNativeScanResult", "(Ljava/lang/String;II)V");
            if (m) { jstring js = e->NewStringUTF(ssid.c_str());
                     e->CallVoidMethod(link, m, js, (jint)info.channel, (jint)info.rssi);
                     if (js) e->DeleteLocalRef(js); }
            e->DeleteLocalRef(cls);
        }
        if (att) ctx->jvm->DetachCurrentThread();
    };
    // Stop any running connect/reconnect supervisor first — otherwise the
    // supervisor thread and the scan both drive the same USB device and the
    // register reads collide (rtw_read throws). disconnect() is a no-op if idle.
    ctx->station->disconnect();
    try { ctx->station->scanAll(perChannelMs > 0 ? perChannelMs : 250,
                                includeDfs == JNI_TRUE, onAp); }
    catch (...) { LOGE("scanAll threw"); }
}

JNIEXPORT jint JNICALL
Java_com_openipc_wfbngrtl8812_ApfpvStaLink_nativeStaGetState(JNIEnv*, jclass, jlong inst) {
    auto* ctx = reinterpret_cast<StaCtx*>(inst);
    return (ctx && ctx->station) ? (jint)ctx->station->state() : 0;
}

JNIEXPORT jint JNICALL
Java_com_openipc_wfbngrtl8812_ApfpvStaLink_nativeStaGetRssi(JNIEnv*, jclass, jlong inst) {
    auto* ctx = reinterpret_cast<StaCtx*>(inst);
    return (ctx && ctx->station) ? (jint)ctx->station->rssiDbm() : -99;
}

JNIEXPORT void JNICALL
Java_com_openipc_wfbngrtl8812_ApfpvStaLink_nativeStaSetLqFeedback(JNIEnv*, jclass, jlong /*inst*/, jboolean /*en*/) {
    // toggles ApfpvStation's LqFeedback; stored in Params at connect for now.
}

JNIEXPORT void JNICALL
Java_com_openipc_wfbngrtl8812_ApfpvStaLink_nativeStaSetTxPower(JNIEnv*, jclass, jlong inst, jint power) {
    // GS dongle TX power index (0-63) for the station-mode dongle. Same chip and
    // same scale as the WFB-ng path; routes to the RtlJaguarDevice's SetTxPower.
    // (Phone-Wi-Fi mode never reaches here — it has no dongle and no StaCtx.)
    auto* ctx = reinterpret_cast<StaCtx*>(inst);
    if (ctx && ctx->rtl) {
        ctx->rtl->SetTxPower(static_cast<uint8_t>(power));
    }
}

JNIEXPORT jint JNICALL
Java_com_openipc_wfbngrtl8812_ApfpvStaLink_nativeStaGetTxPower(JNIEnv*, jclass, jlong inst) {
    // Reads the dongle's REAL reference TX power index (0-63) from the driver:
    // EFUSE-calibrated value when the dongle is connected, else the fallback.
    // Returns -1 if no device yet (caller should keep its own default then).
    auto* ctx = reinterpret_cast<StaCtx*>(inst);
    if (ctx && ctx->rtl) {
        return static_cast<jint>(ctx->rtl->GetTxPower());
    }
    return -1;
}

} // extern "C"
