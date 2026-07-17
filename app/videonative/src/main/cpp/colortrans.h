#pragma once
#include <atomic>
// OpenIPC "Overshoot Fix" colortrans reversal parameters (see GLFanoutRenderer.h).
// Set from the settings menu via JNI (VideoPlayer.nativeSetColortrans); read per-frame by
// the GL fan-out video fragment shader. enable: 0.0 = off (passthrough), 1.0 = un-wash on.
// Defaults match OpenIPC/PixelPilot_rk (gain 2.5, offset -0.15).
extern std::atomic<float> g_ct_enable;
extern std::atomic<float> g_ct_gain;
extern std::atomic<float> g_ct_offset;
