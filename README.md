# PixelPilot — APFPV fork

> [!IMPORTANT]
> This is the **APFPV** fork of [OpenIPC/PixelPilot](https://github.com/OpenIPC/PixelPilot).
> It keeps the stock **wfb-ng** (wifibroadcast) ground-station **and** adds a **direct
> air↔phone link** ("APFPV") that drives an RTL8812AU USB adapter in **station mode**
> from userspace — no kernel MLME, no root. So the app now does **both wfb and APFPV** modes.
>
> It is the top of a 3-repo stack:
> **[WiFiDriver](https://github.com/AlaEddine-Zoghlami/WiFiDriver)** — the first userspace
> station-mode driver for the RTL8812AU — → **[devourer](https://github.com/AlaEddine-Zoghlami/Devourer)**
> (IP / FPV / JNI) → **PixelPilot** (this app). Build instructions below.
>
> Performance depends heavily on your device's processing power. Use at your own risk.

## Building this fork

Needs the **Android SDK** + **NDK `26.1.10909125`**, build-tools 35, and a JDK 17+. The native
`wfbngrtl8812` module pulls in the **devourer** and (nested) **WiFiDriver** submodules, so clone
recursively:

```sh
git clone --recursive https://github.com/AlaEddine-Zoghlami/PixelPilot.git
# in an existing clone:  git submodule update --init --recursive

./gradlew :app:assembleDebug                 # debug APK
./gradlew :app:assembleRelease               # signed release APK (needs ./fpv.jks — see app/build.gradle)
```

The native `libWfbngRtl8812.so` is built by `app/wfbngrtl8812/src/main/cpp/CMakeLists.txt`, which
compiles the WiFiDriver driver + the devourer IP/FPV layer + the wfb logic + the JNI into one
library. Prebuilt APKs: the [Releases](https://github.com/AlaEddine-Zoghlami/PixelPilot/releases) page.

## Introduction

```
PixelPilot – the Android FPV app that leaves "loading…" screens in the dust.
Plug in, fly live with sub-atomic latency, flex real-time signal stats, and marvel at the open-source unicorn
where (brace yourself) most things actually work.
```

Built on the work of:

- [FPVue_android](https://github.com/gehee/FPVue_android): basic and unique work to combine all components into a single application by [Gee He](https://github.com/gehee).
- [devourer](https://github.com/AlaEddine-Zoghlami/Devourer) (**APFPV fork**): userspace Realtek 11ac driver (rtl8812au + rtl8812eu + rtl8822eu), originally created by [buldo](https://github.com/buldo), converted to C by [josephnef](https://github.com/josephnef), and based on [openipc/devourer](https://github.com/openipc/devourer). The fork adds the APFPV station-mode stack (WPA2 supplicant, scan/probe, 802.11 framing, DHCP, link-quality feedback).
- [LiveVideo10ms](https://github.com/Consti10/LiveVideo10ms): excellent video decoder from [Consti10](https://github.com/Consti10) converted into a module.
- [wfb-ng](https://github.com/svpcom/wfb-ng): library allowing the broadcast of the video feed over the air.

The wfb-ng [gs.key](https://github.com/OpenIPC/PixelPilot/raw/main/app/src/main/assets/gs.key) is embedded in the app. The settings menu allows selecting a different key from your phone.

Supported rtl8812au, rtl8812eu and rtl8822eu wifi adapters are listed [here](app/src/main/res/xml/usb_device_filter.xml). Feel free to send pull requests to add new supported adapter hardware IDs.

Supports saving a DVR of the video feed to `Files/Internal Storage/Movies/`.

## APFPV integration (what this fork adds)

APFPV gives the phone a **direct WPA2 station link to an air unit** over an
RTL8812AU dongle, alongside (or instead of) the classic wfb-ng broadcast path.
The driver work lives in the [Devourer fork](https://github.com/AlaEddine-Zoghlami/Devourer);
the Android glue lives here:

| Layer | Location |
|-------|----------|
| Native station driver (station mode, WPA2, scan/probe, DHCP, RX deframe) | `app/wfbngrtl8812/src/main/cpp/devourer/` (devourer submodule) |
| JNI bridge + `ApfpvStaLink` Java API | `app/wfbngrtl8812/src/main/cpp/apfpv_jni.cpp`, `app/wfbngrtl8812/src/main/java/com/openipc/wfbngrtl8812/ApfpvStaLink.java` |
| Link orchestration / mode coordination | `app/src/main/java/com/openipc/pixelpilot/ApfpvLinkManager.java` |
| Air-unit control (SSH + REST), presets, camera settings | `app/src/main/java/com/openipc/pixelpilot/apfpv/` (`AirSshClient`, `AirCameraSettings`, `AirPresets`, `ApfpvWifiManager`, `GsMenuWfbng`, `GsSettings`, `WlxAdapters`, `ApfpvSettings`) |

The link can fall back to the standard wfb-ng broadcast pipeline; the two modes
are coordinated by `ApfpvLinkManager` / `LinkModeCoordinator`.

## Ground-station features

> **VTX prerequisite.** The ground-drawn OSD, the MSP/aalink overlays, and the
> raw / dual recording all rely on the air unit running in **ground mode** — the
> VTX streaming *clean* video while forwarding MSP (UDP 14550) and pushing aalink
> (UDP 14551). Set that up with the scripts in
> [**apfpv-vtx**](https://github.com/GingerFluffyCat/apfpv-vtx)
> (`vtx-osd-mode.sh ground` + the `aalink_udp` relay); it installs the
> forward-capable msposd and can be reverted with `vtx-osd-mode.sh stock`. Without
> ground mode the VTX burns its own OSD into the video and the raw/dual recording
> isn't clean.

- **Auto DVR + dual raw/OSD recording.** Recording starts automatically on FC
  **arm** and stops on disarm (auto-DVR default on). Three record modes: Raw
  (clean), OSD (overlay burned in), and **Raw+OSD** (default — writes *two*
  files, an untouched raw clip and a parallel OSD-composited copy). The
  composited OSD includes **all** on-screen layers — the legacy WFB OSD, the
  MSP/Betaflight OSD, and the aalink status line — and stays live throughout the
  recording. Works in VR mode too.
- **Selectable OSD fonts.** A menu picks the Betaflight glyph atlas used for the
  ground-side OSD; several HD atlases are bundled (`assets/fonts/`,
  `BTFL_default` + `BTFL_{BLINDER,CLASH,CONTHRAX,EUROPA,NEXUS,SPHERE}_HD`). Any
  atlas glyph size auto-scales, so a new font is just a PNG.
- **Voice alerts (audio).** OpenTX/EdgeTX-style spoken alerts built from
  concatenated clips (the **en_us-sara** pack in `assets/sounds/`): arm/disarm,
  battery voltage/level, and link-quality warnings, driven by an edge-triggered
  rule table (`assets/apfpv_sounds.conf`) that reads the arm bit and OSD-scraped
  values. Toggle under **Voice alerts**; on by default. Plus the live **Opus
  audio** stream from the camera (see below).
- **aalink status line & MSP OSD over the dongle.** The aalink stats line
  (LQ/MCS/bitrate/channel/txpower) arrives over **UDP 14551** from the VTX
  `aalink_udp` relay, and MSP telemetry (arm state, Betaflight OSD) over **UDP
  14550** — both pushed inbound, so they work on the dongle path where an
  outbound HTTP poll can't.

## Compatibility

- arm64-v8a, armeabi-v7a Android devices (including Meta Quest 2/3, non-VR mode)

## Build

### 1. Clone with submodules

This repo uses two git submodules — the devourer fork and wfb-ng:

```sh
git clone --recurse-submodules https://github.com/AlaEddine-Zoghlami/PixelPilot.git
cd PixelPilot
# or, if you already cloned without --recurse-submodules:
git submodule update --init --recursive
```

`.gitmodules` pins `app/wfbngrtl8812/src/main/cpp/devourer` to the
[Devourer fork](https://github.com/AlaEddine-Zoghlami/Devourer) (`master`) and
`app/wfbngrtl8812/src/main/cpp/wfb-ng` to [svpcom/wfb-ng](https://github.com/svpcom/wfb-ng) (`master`).

### 2. Toolchain

Open in **Android Studio** (recommended) or build from the CLI. Either way you need:

| Component | Version |
|-----------|---------|
| JDK | 17+ (21 works) |
| Android SDK platform | `android-34` (`compileSdk = 34`) |
| Build-tools | `34.0.0` (Gradle auto-installs `35.0.0`) |
| NDK | `26.1.10909125` |
| CMake | `3.22.1` |

Android Studio installs these for you via the SDK Manager. For a headless build,
install them with `sdkmanager`:

```sh
sdkmanager "platforms;android-34" "ndk;26.1.10909125" "cmake;3.22.1"
```

Create `local.properties` pointing at your SDK. **On Windows use forward slashes**
(a Java `.properties` file treats `\` as an escape, so `C:\Android\sdk` is parsed
incorrectly and breaks NDK discovery):

```properties
sdk.dir=C:/Android/sdk
```

### 3. Prebuilt native libraries

The native modules link against vendored prebuilt `.so`/`.a` files, one set per
ABI, all checked into the tree:

- `app/wfbngrtl8812/src/main/cpp/libs/<abi>/` → `libusb1.0.so`, `libsodium.so`, `libpcap.a`
- `app/videonative/src/main/cpp/libs/<abi>/` → `libopus.so`

No action needed — they are committed for both `arm64-v8a` and `armeabi-v7a`.

### 4. Assemble

```sh
./gradlew :app:assembleDebug        # Linux/macOS
.\gradlew.bat :app:assembleDebug    # Windows
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. A debug build
is verified working with the toolchain above (devourer + APFPV station stack +
wfb-ng all cross-compiled and packaged for both ABIs).

## Installation

- Build the APK (above) and install it with `adb install app/build/outputs/apk/debug/app-debug.apk`.
- Audio feature: PixelPilot can play an Opus stream from majestic on the camera.
  Enable it camera-side in `/etc/majestic.yaml`:

```yaml
audio:
  enabled: true
  volume: 30
  srate: 8000
  codec: opus
  outputEnabled: false
  outputVolume: 30
```

## Quick Reference: Auto-FEC Levels 0 – 5

| Level | Denominator | Added Redundancy |
|-------|-------------|------------------|
| 0 | **1.00000** | 0 % |
| 1 | **1.11111** | ≈ 11 % |
| 2 | **1.25000** | 25 % |
| 3 | **1.42000** | 42 % |
| 4 | **1.66667** | 67 % |
| 5 | **2.00000** | 100 % |

> **How the denominator is used:**
> The selected denominator multiplies either **`FEC_k`** (source-packet count) *or* **`FEC_n`** (total packets after redundancy), increasing the actual amount of forward-error-correction data.

### Threshold Fields (packets / second)

| Field | Purpose |
|-------|---------|
| `LostThreshold` | If `lost_pkts ≥ LostThreshold`, jump straight to **FEC-5** |
| `RecThr1 … RecThr4` | Number of *recovered* packets (`rec_pkts`) that triggers **FEC-1 … FEC-4** |

**Ordering constraint — must hold:**
`RecThr1 < RecThr2 < RecThr3 < RecThr4 < LostThreshold`

### Decision Logic (executed once per second)

```text
if lost_pkts >= LostThreshold:
    level = 5
elif rec_pkts >= RecThr4:
    level = 4
elif rec_pkts >= RecThr3:
    level = 3
elif rec_pkts >= RecThr2:
    level = 2
elif rec_pkts >= RecThr1:
    level = 1
else:
    level = 0

apply_fec(level)  # multiply the level's denominator by FEC_k or FEC_n
```

Set thresholds thoughtfully:
Lower values → more aggressive protection (higher bandwidth / latency).
Higher values → leaner bandwidth, less resilience.

## List of potential improvements

 * adaptive link [x]
 * 40 MHz bandwidth [?] - works but buggy
 * support stream over ipv6
 * Save audio stream with the video for recordings
 * Possibility to forward undecoded wfb stream over the network

## Known issues

 * Audio stream is not working

## Tested devices based on real user reviews

* Samsung Galaxy A54 (Exynos 1380 processor)
* Google Pixel 7 Pro
* Poco X6 Pro
* Meta Quest 2
* Meta Quest 3
