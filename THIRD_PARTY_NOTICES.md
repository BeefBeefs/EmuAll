# Third-party notices

## mGBA

EmuAll Build 2 compiles the mGBA libretro core from the pinned `libretro/mgba` source submodule.

- Project: https://github.com/libretro/mgba
- License: Mozilla Public License 2.0
- Source changes: none

The mGBA source tree includes its complete license notices. EmuAll does not include games, BIOS files, or firmware.

## Libretro Android cores

The ARM64 test build also bundles the following official Android libretro core binaries downloaded from the Libretro buildbot on 2026-09-11. Their upstream licenses and notices remain the governing terms; no source changes were made by EmuAll.

- FCEUmm — https://github.com/libretro/libretro-fceumm
- Snes9x — https://github.com/libretro/libretro-snes9x
- Genesis Plus GX — https://github.com/libretro/Genesis-Plus-GX
- PCSX-ReARMed — https://github.com/libretro/pcsx_rearmed
- PPSSPP — https://github.com/hrydgard/ppsspp
- Mupen64Plus-Next — https://github.com/libretro/mupen64plus-libretro-nx
- Flycast — https://github.com/flyinghead/flycast
- Dolphin — https://github.com/libretro/dolphin
- PCSX2 — https://github.com/libretro/pcsx2

Binary source directory: https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/

The five buildbot binaries that shared the generic `libretro.so` ELF soname have that soname made unique during packaging so Android can load multiple cores in one process. No executable code was changed.

The software-rendered cores are launch-enabled in this build. Hardware-rendered cores are packaged and registered, but remain visibly gated until the Vulkan/OpenGL hardware-context frontend is validated on Android. No games, BIOS files, firmware, or copyrighted assets are included.
