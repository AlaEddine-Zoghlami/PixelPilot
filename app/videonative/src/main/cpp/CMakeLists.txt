
cmake_minimum_required(VERSION 3.6)

project("VideoNative")

include_directories(libs/include)

add_library(${CMAKE_PROJECT_NAME} SHARED
        parser/H26XParser.cpp
        parser/ParseRTP.cpp
        AudioDecoder.cpp
        UdpReceiver.cpp
        UdsReceiver.cpp
        VideoDecoder.cpp
        VideoPlayer.cpp
        GLFanoutJni.cpp)


target_link_libraries(${CMAKE_PROJECT_NAME}
        # List libraries link to the target library
        android
        mediandk
        aaudio
        EGL
        GLESv2
        jnigraphics
        ${CMAKE_SOURCE_DIR}/libs/${ANDROID_ABI}/libopus.so
        log)

set_property(TARGET ${CMAKE_PROJECT_NAME} PROPERTY CXX_STANDARD 20)
target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE -fno-omit-frame-pointer)

# Strip assert() from this library. It parses adversarial network input (RTP/NALU) on a single
# thread; upstream's assert()s (ParseRTP, NALU.hpp, RTP.hpp) fire on torn/lossy packets — routine
# on the dongle link — and abort() the whole app. Release builds define NDEBUG to strip them; a
# debug build does not, so force it here. The explicit bounds/null guards in the code stay active.
target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE NDEBUG)