# EmuAll

EmuAll is an Android-native multi-system emulator frontend inspired by the emulator portion of PixelPalette/PixelPlayer. It does not use a WebView or JavaScript emulator runtime.

## Build 6

- Native Kotlin Android UI using the PixelPlayer dark green/orange visual language
- Extensible system catalog: the original GBA-through-Saturn lineup plus Dreamcast/NAOMI, PS2, GameCube/Wii, and a guarded future Xbox entry
- Android Storage Access Framework picker with persistent file permission
- ZIP and 7z recognized across the native system catalog
- Six recent games per system, stored locally with persistent Android document permissions
- OpenGL ES `GLSurfaceView` ready for native video frames
- C++/JNI frontend library with a safe libretro API compatibility probe
- ARM64-only APK for modern physical Android phones, avoiding unused desktop-emulator binaries

No ROMs, BIOS files, firmware, or copyrighted game assets are included. The APK builds the open-source mGBA core from its pinned source submodule.

Build 2 adds the first playable native core: mGBA. Raw `.gba` games launch with native video/audio, touch controls, physical gamepad input, pause, reset, 3× fast-forward, and automatic battery-save persistence. Archive extraction remains a later shared-core milestone.

Build 3 corrects Android native-library loading by packaging mGBA as `libmgba_libretro.so`, loading it through Android's native library namespace, and attaching the frontend by soname instead of assuming the APK extracted the core to a filesystem path.

Build 4 restores full-speed emulation in the installable debug APK by explicitly optimizing the native mGBA target. Audio output now runs independently from the emulation clock, video textures are reused between frames, and the session header reports measured FPS for device testing.

Build 5 adds persistent per-game Quick Save and Quick Load controls to the session toolbar. Save-state commands are executed safely between emulated frames, including while paused, and the Android package now contains only the `arm64-v8a` binaries needed by the target phone. In landscape, the virtual controls move beside the game so the shorter screen dimension enlarges the viewport instead of collapsing it. The launcher now mirrors PixelPlayer's compact system dropdown and system-specific workspace, with separate recent-game and screenshot-backed quick-state cards.

Build 6 adds three screenshot-backed save-state slots per game, per-game/system state pages, ZIP and 7z extraction, and Game Boy/Game Boy Color launching through the already-compiled mGBA core. CI now uses a stable development signing key so test APKs can be installed as updates. This key is for local development builds only and is not a Play Store release credential.

The session toolbar saves and loads Slot 1 with a tap; long-press either button to choose Slots 1–3.

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
