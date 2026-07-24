cmake_minimum_required(VERSION 3.22.1)

project("WfbngRtl8812")

# ---------------------------------------------------------------------------
#  Layered sources:
#    devourer/src (+ hal, jaguar1)  — the rebuilt-on-upstream driver + the APFPV
#                                     IP/FPV/session layer (both now live here;
#                                     see devourer's own CMakeLists.txt for the
#                                     canonical `devourer`/`apfpv`/`apfpv_station`
#                                     target source lists this mirrors)
#    devourer/extern/WiFiDriver/wfb — WFB-ng protocol layer ONLY (RxFrame/
#                                     TxFrame/SignalQualityCalculator + the
#                                     wfb-ng FEC/radiotap/rx sources): this is
#                                     APFPV-fork-specific glue with no upstream
#                                     equivalent, kept as-is; TxFrame.{h,cpp}
#                                     were repointed at the new jaguar1/
#                                     RtlJaguarDevice.h (its send_packet/
#                                     should_stop contract is unchanged)
#    devourer/jni                   — the JNI bridges (apfpv_jni + WfbngLink)
#
#  devourer/extern/WiFiDriver/hal and .../src (the pre-rebuild driver tree)
#  are NOT on the include path here — they duplicate types now defined in
#  devourer/hal + devourer/src (enum TxSele, RtlJaguarDevice, ...) and mixing
#  both trees on one include path resolves bare #includes inconsistently
#  depending on order, which is exactly the redefinition/mismatched-API errors
#  this rewrite fixes. Only devourer/extern/WiFiDriver/wfb (WFB-ng, no driver
#  dependency of its own beyond the one repointed include) remains in use.
#
#  ⚠️ EVERYTHING is compiled DIRECTLY into the .so — NOT into an intermediate static driver
#  lib. Splitting the driver into its own static lib drops static-init .o and SILENTLY breaks
#  TX (auth frames stop radiating). The host build works around it with --whole-archive; the
#  Android .so simply compiles all driver/apfpv/jni .o in. See the apfpv-3repo-refactor note.
#  (Also why devourer's own CMakeLists.txt -- which needs pkg-config for libusb, unavailable
#  under the NDK cross toolchain -- isn't add_subdirectory'd here: this file takes the same
#  individual source files instead and links the prebuilt libs/${ANDROID_ABI}/libusb1.0.so.)
# ---------------------------------------------------------------------------
set(WD ${CMAKE_SOURCE_DIR}/devourer/extern/WiFiDriver)
set(DV ${CMAKE_SOURCE_DIR}/devourer)

include_directories(include ${WD} ${WD}/wfb ${WD}/wfb/wfb-ng
        ${DV}/hal ${DV}/hal/phydm/rtl8814a ${DV}/src ${DV}/src/apfpv ${DV}/src/apfpv/crypto ${DV}/jni)

# --- wfb-ng (wifibroadcast FEC), verbatim from WiFiDriver/wfb ---------------
add_library(wfb-ng STATIC
        ${WD}/wfb/wfb-ng/src/zfex.c
        ${WD}/wfb/wfb-ng/src/radiotap.c
        ${WD}/wfb/wfb-ng/src/rx.cpp
        ${WD}/wfb/wfb-ng/src/wifibroadcast.cpp)
target_include_directories(wfb-ng PUBLIC ${WD}/wfb/wfb-ng)
target_compile_definitions(wfb-ng PRIVATE
        __WFB_RX_SHARED_LIBRARY__
        PREINCLUDE_FILE=<${WD}/wfb/wfb_log.h>
        ZFEX_UNROLL_ADDMUL_SIMD=8
        ZFEX_USE_INTEL_SSSE3
        ZFEX_USE_ARM_NEON
        ZFEX_INLINE_ADDMUL
        ZFEX_INLINE_ADDMUL_SIMD)

# --- devourer core (generation-agnostic) + Jaguar1 (8812/8811/8821AU) -------
# Mirrors devourer/CMakeLists.txt's `devourer` target core list + its
# DEVOURER_JAGUAR1 block, minus the demo/PCIe/Jaguar2/3/Kestrel-only pieces
# PixelPilot's Android target (8812AU dongles) never uses. 8814AU (4T4R) is
# also dropped here -- not a supported PixelPilot dongle -- so
# DEVOURER_HAVE_8814 stays undefined and the Iqk8814a-only code paths in
# HalModule/RadioManagementModule compile out; DEVOURER_HAVE_JAGUAR1 is
# load-bearing (WiFiDriver::CreateRtlDevice's device-family dispatch is
# gated on it) and must stay defined below.
set(DV_CORE
        ${DV}/src/HalmacCfgParam.cpp
        ${DV}/src/ParsedRadioPacket.cpp
        ${DV}/src/Radiotap.c
        ${DV}/src/RadiotapBuilder.cpp
        ${DV}/src/RtlAdapter.cpp
        ${DV}/src/UsbTransport.cpp
        ${DV}/src/UsbDeviceLock.cpp
        ${DV}/src/UsbOpen.cpp
        ${DV}/src/TxMode.cpp
        ${DV}/src/TxPower.cpp
        ${DV}/src/LinkHealth.cpp
        ${DV}/src/chanmig/ChannelScore.cpp
        ${DV}/src/chanmig/MigGate.cpp
        ${DV}/src/SignalStop.cpp
        ${DV}/src/WiFiDriver.cpp
        ${DV}/src/LaCapture.cpp
        ${DV}/src/PhyTableLoader.cpp)

set(DV_JAGUAR1
        ${DV}/hal/hal8812a_fw.c
        ${DV}/hal/hal8821a_fw.c
        ${DV}/hal/Hal8812PwrSeq.c
        ${DV}/hal/Hal8821APwrSeq.c
        ${DV}/hal/Hal8812a_TxPwrTrack.cpp
        ${DV}/src/jaguar1/RtlJaguarDevice.cpp
        ${DV}/src/jaguar1/HalModule.cpp
        ${DV}/src/jaguar1/RadioManagementModule.cpp
        ${DV}/src/jaguar1/EepromManager.cpp
        ${DV}/src/jaguar1/FirmwareManager.cpp
        ${DV}/src/jaguar1/Iqk8812a.cpp
        ${DV}/src/jaguar1/PhydmWatchdog.cpp
        ${DV}/src/jaguar1/PowerTracking8812a.cpp
        ${DV}/src/jaguar1/FrameParser.cpp
        ${DV}/src/jaguar1/BbDbgportReader.cpp)

# --- the APFPV layer (station-mode WPA2/CCMP + IP/FPV/session), mirroring
#     devourer/CMakeLists.txt's `apfpv` + `apfpv_station` target sources -----
set(DV_APFPV
        ${DV}/src/apfpv/Wpa2Supplicant.cpp
        ${DV}/src/apfpv/Dot11Frames.cpp
        ${DV}/src/apfpv/Wpa2Crypto.cpp
        ${DV}/src/apfpv/crypto/AesCcm.cpp
        ${DV}/src/apfpv/crypto/AesCmac.cpp
        ${DV}/src/apfpv/crypto/Sha1Hmac.cpp
        ${DV}/src/apfpv/ScanProbe.cpp
        ${DV}/src/ApfpvDhcp.cpp
        ${DV}/src/LqFeedback.cpp
        ${DV}/src/Wpa2Authenticator.cpp
        ${DV}/src/ApfpvStation.cpp
        ${DV}/src/RxDeframe.cpp
        ${DV}/src/apfpv/StationMode.cpp
        ${DV}/src/apfpv/StationTxDesc.cpp)

# --- the .so: driver + APFPV layer + WFB-ng protocol layer + JNI ------------
add_library(${CMAKE_PROJECT_NAME} SHARED
        ${DV_CORE}
        ${DV_JAGUAR1}
        ${DV_APFPV}
        ${WD}/wfb/RxFrame.cpp
        ${WD}/wfb/TxFrame.cpp
        ${WD}/wfb/SignalQualityCalculator.cpp
        ${DV}/jni/apfpv_jni.cpp
        ${DV}/jni/WfbngLink.cpp)

set_property(TARGET ${CMAKE_PROJECT_NAME} PROPERTY CXX_STANDARD 20)
target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE -fno-omit-frame-pointer)
target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE DEVOURER_HAVE_JAGUAR1=1)
target_link_libraries(${CMAKE_PROJECT_NAME}
        wfb-ng
        android
        log
        ${CMAKE_SOURCE_DIR}/libs/${ANDROID_ABI}/libusb1.0.so
        ${CMAKE_SOURCE_DIR}/libs/${ANDROID_ABI}/libsodium.so
        ${CMAKE_SOURCE_DIR}/libs/${ANDROID_ABI}/libpcap.a)
