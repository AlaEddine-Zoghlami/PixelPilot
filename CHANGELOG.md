# Changelog

All notable changes to the APFPV fork of PixelPilot.

## v0.4.2 — 2026-07-23

### Fixed
- **MTK HW HEVC decode** — root-caused and fixed the long-standing "MTK HW HEVC freezes"
  workaround: the H.265 csd-0 buffer (VPS+SPS+PPS) was built in reverse order (PPS,SPS,VPS)
  due to an `insert(begin())` bug, which the software decoder tolerated but the hardware
  decoder didn't. HW decode is now the default on MTK and sustains 120fps with 0 discards.
- **Decoder stall-recovery watchdog** — a MediaTek video-firmware throughput ceiling (confirmed
  via kernel-level atrace, transport-independent) could permanently freeze the decoder under
  sustained high bitrate. Added a watchdog that detects "data arriving, 0 frames decoded" and
  forces a recovery flush, turning a permanent freeze into a bounded ~2-4s recoverable stutter.
- **Stale-frame flush storm** — the flush-to-keyframe path could enter a self-sustaining loop
  (flush → still late → flush again, forever) on any decoder that fell behind; previously only
  disabled for the software-decoder fallback, now disabled for MTK regardless of which decoder
  is active.
- **App-resume reconnect (phone-Wi-Fi)** — `onPause()` wasn't stopping `ApfpvWifiManager`, so
  its running-guard never reset and `onResume()`'s reconnect silently no-op'd.
- **Decode-feed thread decouple** — a slow decode step could stall the RTP-receive/parse thread
  (measured as inflated "Parsing" latency); feeding the decoder now runs on its own thread with
  a bounded, drop-oldest queue.
- **Mutex-starvation fix (devourer/WiFiDriver)** — the decoder feed thread's input-buffer retry
  loop could win a mutex re-acquire race indefinitely against the stall watchdog's recovery
  flush; added a yield point so recovery can actually land.
- **UAF-prone RX teardown (devourer/WiFiDriver)** — `stopAsyncRx()` could free USB transfers
  libusb still owned after a sleep-poll timeout, surfacing later as a destroyed-mutex SIGABRT
  on replug. Now actively pumps libusb events itself and leaks (rather than frees) a transfer
  that's still genuinely stuck.
- **Exception-unsafe pause/resume around RF calibration (devourer/WiFiDriver)** — an exception
  during `arm()`/IQK left the async RX pool permanently paused, compounding into the UAF above.
  Guarded with RAII so resume always runs.

### Added
- Diagnostics: feed-queue overflow logging (`VideoDecoder`), URB free-list depth/pool-empty
  events (`rxd-pool`), GL fan-out stage timing (temporary), and live-tunable BA buffer-size /
  LQ-feedback-interval / RTP-reorder-timeout knobs (`debug.pixelpilot.*` props) for A/B testing
  the dongle's accept-vs-decline BlockAck tradeoff without a rebuild per value.

## v0.3.1 — 2026-06-10

### Added
- **GL fan-out DVR** — records the live video to `.mp4` from the *same* decode driving the
  display (no second decoder): decoder → SurfaceTexture → GL pass → display + encoder surface.
- **Record modes** (gear → Recording → Record mode):
  - **Raw** — clean video, no overlay.
  - **OSD** — the OSD overlay composited into the recording.
  - **Raw+OSD** — both at once: two parallel files (`…_raw.mp4` + `….mp4`) from one decode via a
    dual encoder.
- **Auto-DVR** (gear → Recording → Auto DVR) — auto-starts recording when a stream appears and
  auto-stops ~3 s after it drops (device/remote off); a manual Stop is respected.
- **OSD in recordings** — composited at the video's aspect ratio (no stretch), each element at its
  real on-screen position; app chrome (settings gear, record button) excluded; ~2 Hz live refresh
  so telemetry/GPS stay current in the clip.
- **OSD element boxes** (gear → OSD → Element boxes) — optional semi-transparent box behind each
  OSD element for readability over bright video; shows on screen and in recordings.
- **x86_64 ABI** for the emulator (videonative, wfbngrtl8812, mavlink).

### Fixed
- H.264 SPS resolution parsing (was hardcoded 640×480).
- OSD recorded upside-down / vertically stretched / mis-positioned; element boxes now wrap content
  with even margins (icons hug their glyph; video-stats row aligned).

### Notes
- Records H.265 (PT 97) and H.264 (PT 96); use frequent keyframes for clean mid-stream join.
