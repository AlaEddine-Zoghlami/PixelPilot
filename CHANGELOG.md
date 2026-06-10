# Changelog

All notable changes to the APFPV fork of PixelPilot.

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
