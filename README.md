# EmuAll

EmuAll is an Android-native multi-system emulator frontend inspired by the emulator portion of PixelPalette/PixelPlayer. It does not use a WebView or JavaScript emulator runtime.

## Build 3

- Native Kotlin Android UI using the PixelPlayer dark green/orange visual language
- Extensible system catalog: the original GBA-through-Saturn lineup plus Dreamcast/NAOMI, PS2, GameCube/Wii, and a guarded future Xbox entry
- Android Storage Access Framework picker with persistent file permission
- ZIP and 7z recognized across the native system catalog
- Six recent games per system, stored locally with persistent Android document permissions
- OpenGL ES `GLSurfaceView` ready for native video frames
- C++/JNI frontend library with a safe libretro API compatibility probe
- Only `arm64-v8a` and `x86_64` ABIs, keeping modern phones and Android emulators covered without legacy APK bloat

No ROMs, BIOS files, firmware, or copyrighted game assets are included. The APK builds the open-source mGBA core from its pinned source submodule.

Build 2 adds the first playable native core: mGBA. Raw `.gba` games launch with native video/audio, touch controls, physical gamepad input, pause, reset, 3× fast-forward, and automatic battery-save persistence. Archive extraction remains a later shared-core milestone.

Build 3 corrects Android native-library loading by packaging mGBA as `libmgba_libretro.so`, loading it through Android's native library namespace, and attaching the frontend by soname instead of assuming the APK extracted the core to a filesystem path.

Vulkan is the preferred backend for hardware-rendered cores. OpenGL ES is retained as an automatic compatibility fallback and for cores that do not offer Vulkan. mGBA produces software-rendered frames, so its first implementation uploads those frames through the lightweight OpenGL ES fallback path; it does not expose its own Vulkan renderer.

## Architecture

The emulator core and renderer are separate pieces:

1. A compiled libretro core emulates a console and produces video/audio/input callbacks.
2. `emuall_frontend` owns one core at a time and bridges it to Kotlin through JNI.
3. OpenGL ES uploads software-rendered frames or supplies a hardware-rendering context when a core requests one.
4. Android `AudioTrack` handles audio and Android input APIs feed touch/controller state.

This single-session design carries forward PixelPlayer's rule that launching one game shuts down the previous emulator before loading another.

## Planned native milestones

1. Complete the frontend callback loop and ship the first working core (mGBA).
2. Add audio, touch controls, physical controller mapping, pause/reset, fast-forward, and screenshots.
3. Add battery saves plus three per-game save-state slots and thumbnails.
4. Add archive extraction and folder-library scanning.
5. Add remaining cores in compatibility tiers, testing one system at a time.

The catalog is intentionally capability-driven rather than capped to a hard-coded console generation. Software-video cores use the common OpenGL texture path. Dreamcast, PS2, and GameCube/Wii are tagged as hardware-rendered cores and receive runtime GPU checks. A system is exposed as playable only when its Android core and required graphics API have both been validated. Original Xbox therefore remains a guarded future entry instead of claiming support that Android cannot currently provide.
