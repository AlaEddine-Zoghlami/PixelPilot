cmake_minimum_required(VERSION 3.22.1)

project("WfbngRtl8812")

# ---------------------------------------------------------------------------
#  Layered sources now live in the submodules:
#    devourer/extern/WiFiDriver  — libusb RTL8812AU driver + Station L2 + crypto + wfb logic
#    devourer/src                — the APFPV IP/FPV/session layer (5 files)
#    devourer/jni                — the JNI bridges (apfpv_jni + WfbngLink)
#
#  ⚠️ EVERYTHING is compiled DIRECTLY into the .so — NOT into an intermediate static driver
#  lib. Splitting the driver into its own static lib drops static-init .o and SILENTLY breaks
#  TX (auth frames stop radiating). The host build works around it with --whole-archive; the
#  Android .so simply compiles all driver/apfpv/jni .o in. See the apfpv-3repo-refactor note.
# ---------------------------------------------------------------------------
set(WD ${CMAKE_SOURCE_DIR}/devourer/extern/WiFiDriver)
set(DV ${CMAKE_SOURCE_DIR}/devourer)

# ${WD} (the WiFiDriver root) is on the path so the wfb files' "src/..." / "hal/..." includes
# (relative to the driver root) resolve, alongside the bare-name includes via ${WD}/src etc.
include_directories(include ${WD} ${WD}/hal ${WD}/hal/phydm/rtl8814a ${WD}/src ${WD}/wfb ${WD}/wfb/wfb-ng ${DV}/src ${DV}/jni)

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

# --- explicit HAL sources (phydm/*.c reference tables are NOT compiled) -----
set(WD_HAL
        ${WD}/hal/Hal8812PwrSeq.c
        ${WD}/hal/Hal8812a_TxPwrTrack.cpp
        ${WD}/hal/Hal8814PwrSeq.c
        ${WD}/hal/Hal8821APwrSeq.c
        ${WD}/hal/hal8812a_fw.c
        ${WD}/hal/hal8814a_fw.c
        ${WD}/hal/hal8821a_fw.c
        ${WD}/hal/phydm/rtl8814a/Hal8814_PhyTables.c)
file(GLOB WD_SRC ${WD}/src/*.cpp ${WD}/src/*.c ${WD}/src/crypto/*.cpp)

# ARM-CE not yet (toolchain needs float-abi=hard). Software AES with key cache.

# NOTE: upstream's merged-in RTL8812EU/RTL8822EU adapter support (#104) used
# an entirely different devourer architecture here -- a "Jaguar1/Jaguar3"
# chip-generation abstraction with its own RtlAdapter.cpp/UsbTransport.cpp/
# etc, no ApfpvStation.cpp or WPA2 station-mode stack at all. That's upstream
# OpenIPC/devourer's own (much-diverged, ~160-commits-ahead) fork lineage,
# not compatible with -- and not present in -- our own devourer submodule
# (AlaEddine-Zoghlami/Devourer), which still uses the WD_HAL/WD_SRC layout
# below. Kept ours; porting EU-adapter support would mean adding it to our
# fork's own source layout, a separate task, not something this merge does.

# --- the .so: driver + Station L2 + crypto + APFPV layer + wfb logic + JNI --
add_library(${CMAKE_PROJECT_NAME} SHARED
        ${WD_HAL}
        ${WD_SRC}
        ${DV}/src/ApfpvStation.cpp
        ${DV}/src/ApfpvDhcp.cpp
        ${DV}/src/RxDeframe.cpp
        ${DV}/src/LqFeedback.cpp
        ${DV}/src/Wpa2Authenticator.cpp
        ${WD}/wfb/RxFrame.cpp
        ${WD}/wfb/TxFrame.cpp
        ${WD}/wfb/SignalQualityCalculator.cpp
        ${DV}/jni/apfpv_jni.cpp
        ${DV}/jni/WfbngLink.cpp)

set_property(TARGET ${CMAKE_PROJECT_NAME} PROPERTY CXX_STANDARD 20)
target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE -fno-omit-frame-pointer)
target_link_libraries(${CMAKE_PROJECT_NAME}
        wfb-ng
        android
        log
        ${CMAKE_SOURCE_DIR}/libs/${ANDROID_ABI}/libusb1.0.so
        ${CMAKE_SOURCE_DIR}/libs/${ANDROID_ABI}/libsodium.so
        ${CMAKE_SOURCE_DIR}/libs/${ANDROID_ABI}/libpcap.a)
