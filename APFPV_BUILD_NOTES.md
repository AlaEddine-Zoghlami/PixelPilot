# APFPV integration — build notes & the devourer submodule

## IMPORTANT: the devourer submodule now points at the FORK
PixelPilot's `app/wfbngrtl8812/src/main/cpp/devourer` is a git submodule. The
stock PixelPilot points it at openipc/devourer (monitor-only). For APFPV it must
point at the FORK which contains BOTH the devourer base AND the APFPV station
stack (StationMode, Wpa2Supplicant, ScanProbe, ApfpvStation, crypto, etc.).

.gitmodules has been updated to:
    url = https://github.com/AlaEddine-Zoghlami/Devourer.git

After cloning PixelPilot:
    git submodule update --init --recursive
This pulls the fork (base + APFPV) into devourer/. The CMakeLists already lists
all APFPV sources (StationMode/ScanProbe/Wpa2*/ApfpvStation/crypto/...).

## If you prefer vendoring instead of the submodule
The current tree has the full fork source copied into devourer/src + devourer/
hal as plain files (synced from the fork, all current as of fork commit 21).
To vendor permanently: remove the submodule line from .gitmodules and
`git rm --cached app/wfbngrtl8812/src/main/cpp/devourer`, then commit the dir.

## Disparity that was fixed (this commit)
The copied native source in the PixelPilot tree had drifted from the fork:
- 8 APFPV files were STALE (pre reconnect-supervisor / scan-negotiation / real-
  RSSI commits 19-21)
- ScanProbe.cpp/.h (commit 20) was MISSING entirely
- the devourer BASE files + hal/ were absent (submodule not populated with fork)
All now synced identical to fork commit 21, and ScanProbe added to CMakeLists.
