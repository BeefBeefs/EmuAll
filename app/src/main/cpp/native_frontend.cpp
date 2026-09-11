#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cxxabi.h>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <deque>
#include <exception>
#include <fstream>
#include <fcntl.h>
#include <cstdlib>
#include <mutex>
#include <sstream>
#include <string>
#include <typeinfo>
#include <unordered_map>
#include <vector>
#include <unistd.h>

#include "cores/mgba/src/platform/libretro/libretro.h"

namespace {
struct CoreApi {
    void* handle{};
    unsigned (*apiVersion)(){};
    void (*setEnvironment)(retro_environment_t){};
    void (*setVideoRefresh)(retro_video_refresh_t){};
    void (*setAudioSample)(retro_audio_sample_t){};
    void (*setAudioBatch)(retro_audio_sample_batch_t){};
    void (*setInputPoll)(retro_input_poll_t){};
    void (*setInputState)(retro_input_state_t){};
    void (*setControllerPortDevice)(unsigned, unsigned){};
    void (*getSystemInfo)(retro_system_info*){};
    void (*getSystemAvInfo)(retro_system_av_info*){};
    void (*init)(){};
    void (*deinit)(){};
    bool (*loadGame)(const retro_game_info*){};
    void (*unloadGame)(){};
    void (*run)(){};
    void (*reset)(){};
    size_t (*serializeSize)(){};
    bool (*serialize)(void*, size_t){};
    bool (*unserialize)(const void*, size_t){};
    void* (*memoryData)(unsigned){};
    size_t (*memorySize)(unsigned){};
};

struct Session {
    CoreApi api;
    std::vector<uint8_t> rom;
    std::vector<uint8_t> frame;
    std::deque<int16_t> audio;
    std::mutex frameMutex;
    std::mutex audioMutex;
    std::atomic<uint32_t> inputMask{0};
    // Libretro exposes analogue sticks as a separate device from the
    // standard RetroPad buttons. Keep both sticks in the session so a
    // hardware core (N64, PSP, Dreamcast, GameCube or PS2) can receive the
    // same normalized values that the virtual controls produce.
    std::atomic<int16_t> analogX[2]{{0}, {0}};
    std::atomic<int16_t> analogY[2]{{0}, {0}};
    unsigned width{};
    unsigned height{};
    double videoAspectRatio{};
    unsigned pixelFormat{RETRO_PIXEL_FORMAT_RGB565};
    double fps{59.7275};
    double sampleRate{32768.0};
    uintptr_t hardwareFramebuffer{};
    std::string savePath;
    std::string systemDirectory;
    std::string saveDirectory;
    std::string corePath;
    std::string lastError;
    std::string lastCoreError;
    std::string graphicsNegotiationError;
    std::mutex logMutex;
    std::deque<std::string> diagnostics;
    std::string diagnosticsPath;
    size_t diagnosticBytesWritten{};
    std::unordered_map<std::string, std::string> variables;
    unsigned glesMajor{3};
    unsigned glesMinor{0};
    bool hardwareRendering{};
    bool hardwareContextConfigured{};
    retro_hw_render_callback hardwareCallback{};
    retro_frame_time_callback frameTimeCallback{};
    std::atomic<uint64_t> runCalls{0};
    std::atomic<uint64_t> videoCallbacks{0};
    bool initialized{};
    bool gameLoaded{};
} g;

int diagnosticFd = -1;

void open_diagnostic_file(const std::string& path) {
    if (diagnosticFd >= 0) close(diagnosticFd);
    diagnosticFd = -1;
    g.diagnosticBytesWritten = 0;
    g.diagnosticsPath = path;
    if (path.empty()) return;
    diagnosticFd = open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_APPEND, 0600);
}

void persist_diagnostic_locked(const std::string& line) {
    const int fd = diagnosticFd;
    if (fd < 0 || g.diagnosticBytesWritten >= 256 * 1024) return;
    std::string output = line;
    output += '\n';
    const size_t remaining = 256 * 1024 - g.diagnosticBytesWritten;
    const size_t length = std::min(output.size(), remaining);
    const ssize_t written = write(fd, output.data(), length);
    if (written > 0) g.diagnosticBytesWritten += static_cast<size_t>(written);
}

void record_diagnostic(const std::string& message) {
    if (message.empty()) return;
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
    std::lock_guard lock(g.logMutex);
    std::ostringstream line;
    line << '[' << elapsed << "] " << message;
    g.diagnostics.push_back(line.str());
    persist_diagnostic_locked(line.str());
    while (g.diagnostics.size() > 240) g.diagnostics.pop_front();
}

bool is_core(const char* name) {
    return g.corePath.find(name) != std::string::npos;
}

std::string current_exception_name() {
    // Some native cores throw a non-std exception object.  The old frontend
    // reduced those failures to the unhelpful string "unknown native
    // exception", which made it impossible to tell whether a ROM, BIOS, or
    // renderer setup step was at fault.  Only call this helper while handling
    // an active exception.
    std::string name = "unknown native exception";
#if defined(__ANDROID__) || defined(__linux__)
    const std::type_info* type = abi::__cxa_current_exception_type();
    if (type && type->name()) {
        int status = 0;
        char* demangled = abi::__cxa_demangle(type->name(), nullptr, nullptr, &status);
        name += " (" + std::string(status == 0 && demangled ? demangled : type->name()) + ")";
        std::free(demangled);
    }
#endif
    return name;
}

void core_failure(const char* operation, const char* detail) {
    g.lastError = std::string(operation) + " failed";
    if (detail && *detail) {
        g.lastError += ": ";
        g.lastError += detail;
    }
    __android_log_print(ANDROID_LOG_ERROR, "EmuAllNative", "%s", g.lastError.c_str());
    record_diagnostic(g.lastError);
}

template <typename Function>
bool call_core_bool(const char* operation, Function&& function) {
    try {
        return function();
    } catch (const std::exception& error) {
        core_failure(operation, error.what());
    } catch (...) {
        const std::string detail = current_exception_name();
        core_failure(operation, detail.c_str());
    }
    return false;
}

template <typename Function>
bool call_core_void(const char* operation, Function&& function) {
    try {
        function();
        return true;
    } catch (const std::exception& error) {
        core_failure(operation, error.what());
    } catch (...) {
        const std::string detail = current_exception_name();
        core_failure(operation, detail.c_str());
    }
    return false;
}

void core_log(enum retro_log_level level, const char* format, ...) {
    int priority = level == RETRO_LOG_ERROR ? ANDROID_LOG_ERROR :
        level == RETRO_LOG_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
    va_list args;
    va_start(args, format);
    va_list copy;
    va_copy(copy, args);
    char message[2048]{};
    std::vsnprintf(message, sizeof(message), format, copy);
    va_end(copy);
    __android_log_vprint(priority, "EmuAllNative", format, args);
    va_end(args);
    std::string clean(message);
    while (!clean.empty() && (clean.back() == '\n' || clean.back() == '\r')) clean.pop_back();
    if (!clean.empty()) {
        std::lock_guard lock(g.logMutex);
        const char* label = level == RETRO_LOG_ERROR ? "ERROR" :
            level == RETRO_LOG_WARN ? "WARN" : level == RETRO_LOG_DEBUG ? "DEBUG" : "INFO";
        g.diagnostics.push_back(std::string("[core ") + label + "] " + clean);
        persist_diagnostic_locked(std::string("[core ") + label + "] " + clean);
        while (g.diagnostics.size() > 240) g.diagnostics.pop_front();
        if (level == RETRO_LOG_ERROR)
            g.lastCoreError = clean;
    }
}

uintptr_t hardware_framebuffer() { return g.hardwareFramebuffer; }
// Desktop OpenGL exposes glDrawBuffer(GLenum), while GLES3 only exposes
// glDrawBuffers(GLsizei, const GLenum*). Dolphin's OpenGL backend still asks
// for the desktop entry point even when it is running in its GLES mode. A
// small adapter keeps that required symbol non-null and selects the color
// attachment for our frontend-owned FBO.
void gles_draw_buffer(GLenum mode) {
    (void)mode;
    GLint framebuffer = 0;
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &framebuffer);
    GLenum target = framebuffer == 0 ? GL_BACK : GL_COLOR_ATTACHMENT0;
    // GL_BACK_LEFT/GL_BACK_RIGHT are desktop-only names. They are both the
    // single Android window backbuffer in this frontend, so all default-FBO
    // selections map to GL_BACK.
    // Do not link this symbol directly: some Android GLES drivers expose it
    // only through eglGetProcAddress even though GLES 3 headers declare it.
    using gl_draw_buffers_proc = void (*)(GLsizei, const GLenum*);
    auto drawBuffers = reinterpret_cast<gl_draw_buffers_proc>(eglGetProcAddress("glDrawBuffers"));
    if (drawBuffers) drawBuffers(1, &target);
}

retro_proc_address_t hardware_proc_address(const char* symbol) {
    if (!symbol) return nullptr;
    if (std::strcmp(symbol, "glDrawBuffer") == 0)
        return reinterpret_cast<retro_proc_address_t>(&gles_draw_buffer);
    // Android exposes most GLES entry points through eglGetProcAddress, but
    // a few drivers only publish core symbols through the process namespace.
    // Returning both makes the callback usable by cores with large generated
    // GL symbol tables (notably Flycast and Dolphin).
    void* address = reinterpret_cast<void*>(eglGetProcAddress(symbol));
    if (!address) address = dlsym(RTLD_DEFAULT, symbol);
    return reinterpret_cast<retro_proc_address_t>(address);
}

retro_time_t perf_time_usec() {
    const auto now = std::chrono::steady_clock::now().time_since_epoch();
    return std::chrono::duration_cast<std::chrono::microseconds>(now).count();
}

retro_perf_tick_t perf_counter() {
    const auto now = std::chrono::steady_clock::now().time_since_epoch();
    return static_cast<retro_perf_tick_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(now).count());
}

uint64_t perf_cpu_features() {
    // EmuAll packages arm64-v8a only. Advanced SIMD/FP is mandatory in
    // AArch64, so advertising no CPU features needlessly disables optimized
    // and JIT-adjacent paths in the three most demanding cores.
    return RETRO_SIMD_NEON | RETRO_SIMD_ASIMD | RETRO_SIMD_VFPV3 | RETRO_SIMD_VFPV4;
}
void perf_register(retro_perf_counter* counter) { if (counter) counter->registered = true; }
void perf_start(retro_perf_counter* counter) {
    if (counter) { counter->start = perf_counter(); ++counter->call_cnt; }
}
void perf_stop(retro_perf_counter* counter) {
    if (counter) counter->total += perf_counter() - counter->start;
}
void perf_log() {}

bool rumble_state(unsigned, retro_rumble_effect, uint16_t) { return false; }
bool sensor_set_state(unsigned, retro_sensor_action, unsigned) { return false; }
float sensor_get_input(unsigned, unsigned) { return 0.0f; }

bool environment(unsigned command, void* data) {
    switch (command) {
        case RETRO_ENVIRONMENT_SET_HW_RENDER: {
            if (!g.hardwareRendering || !data) return false;
            auto* callback = static_cast<retro_hw_render_callback*>(data);
            // A libretro frontend must either provide the version the core
            // requested or reject it so the core can try a lower fallback.
            // The previous frontend accepted Play!'s GLES 3.2 request and
            // then rewrote it to 3.0, leaving the core to compile 3.2 shaders
            // against a context that did not provide the required features.
            const bool desktopGl = callback->context_type == RETRO_HW_CONTEXT_OPENGL ||
                callback->context_type == RETRO_HW_CONTEXT_OPENGL_CORE;
            if (desktopGl && !is_core("ppsspp")) {
                g.graphicsNegotiationError = "The Android core requested desktop OpenGL instead of OpenGL ES";
                __android_log_print(ANDROID_LOG_WARN, "EmuAllNative", "%s", g.graphicsNegotiationError.c_str());
                return false;
            }
            if (!desktopGl && callback->context_type != RETRO_HW_CONTEXT_OPENGLES2 &&
                callback->context_type != RETRO_HW_CONTEXT_OPENGLES3 &&
                callback->context_type != RETRO_HW_CONTEXT_OPENGLES_VERSION) return false;

            unsigned requiredMajor = callback->context_type == RETRO_HW_CONTEXT_OPENGLES2 ? 2u : 3u;
            unsigned requiredMinor = 0;
            if (callback->version_major >= requiredMajor) {
                requiredMajor = callback->version_major;
                requiredMinor = callback->version_minor;
            }
            const bool versionAvailable = g.glesMajor > requiredMajor ||
                (g.glesMajor == requiredMajor && g.glesMinor >= requiredMinor);
            if (!versionAvailable) {
                g.graphicsNegotiationError = "Core requires OpenGL ES " +
                    std::to_string(requiredMajor) + "." + std::to_string(requiredMinor) +
                    ", but Android created " + std::to_string(g.glesMajor) + "." +
                    std::to_string(g.glesMinor);
                __android_log_print(ANDROID_LOG_WARN, "EmuAllNative", "%s", g.graphicsNegotiationError.c_str());
                record_diagnostic(g.graphicsNegotiationError);
                return false;
            }
            __android_log_print(ANDROID_LOG_INFO, "EmuAllNative",
                "Hardware render request accepted: context=%d depth=%d stencil=%d requested=%u.%u actual=%u.%u",
                static_cast<int>(callback->context_type), callback->depth, callback->stencil,
                callback->version_major, callback->version_minor, g.glesMajor, g.glesMinor);
            // Some older PPSSPP Android builds report their GLES renderer as
            // desktop OPENGL. Keep that already-working compatibility path
            // isolated to PPSSPP instead of changing every core's request.
            if (desktopGl) {
                callback->context_type = RETRO_HW_CONTEXT_OPENGLES3;
                callback->version_major = 3;
                callback->version_minor = 0;
            }
            // context_reset/context_destroy belong to the core. The frontend
            // invokes those callbacks after creating or losing its GL context;
            // replacing them with no-ops prevents cores from initializing GL
            // resources and can crash on the first ROM frame.
            callback->get_current_framebuffer = hardware_framebuffer;
            callback->get_proc_address = hardware_proc_address;
            // Preserve the core's requested context lifetime semantics.  In
            // particular, Flycast's glsm layer asks for a non-cached context
            // and handles its own resource rebuild on context_reset().
            g.hardwareCallback = *callback;
            g.hardwareContextConfigured = true;
            g.graphicsNegotiationError.clear();
            record_diagnostic("Accepted hardware context request: type=" +
                std::to_string(static_cast<int>(callback->context_type)) + " requested=" +
                std::to_string(callback->version_major) + "." + std::to_string(callback->version_minor) +
                " actual GLES=" + std::to_string(g.glesMajor) + "." + std::to_string(g.glesMinor));
            return true;
        }
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            auto format = *static_cast<const retro_pixel_format*>(data);
            if (format != RETRO_PIXEL_FORMAT_RGB565 && format != RETRO_PIXEL_FORMAT_XRGB8888) return false;
            g.pixelFormat = format;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_GEOMETRY: {
            if (!data) return false;
            const auto* geometry = static_cast<const retro_game_geometry*>(data);
            if (geometry->aspect_ratio > 0.0)
                g.videoAspectRatio = geometry->aspect_ratio;
            else if (geometry->base_height)
                g.videoAspectRatio = static_cast<double>(geometry->base_width) / geometry->base_height;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: {
            if (!data) return false;
            const auto* av = static_cast<const retro_system_av_info*>(data);
            if (av->geometry.aspect_ratio > 0.0)
                g.videoAspectRatio = av->geometry.aspect_ratio;
            else if (av->geometry.base_height)
                g.videoAspectRatio = static_cast<double>(av->geometry.base_width) / av->geometry.base_height;
            if (av->timing.fps > 0.0) g.fps = av->timing.fps;
            if (av->timing.sample_rate > 0.0) g.sampleRate = av->timing.sample_rate;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY:
            *static_cast<const char**>(data) = g.systemDirectory.c_str(); return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            *static_cast<const char**>(data) = g.saveDirectory.c_str(); return true;
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
            static_cast<retro_log_callback*>(data)->log = core_log; return true;
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            *static_cast<bool*>(data) = true; return true;
        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS: return true;
        case RETRO_ENVIRONMENT_GET_LANGUAGE:
            *static_cast<unsigned*>(data) = RETRO_LANGUAGE_ENGLISH; return true;
        case RETRO_ENVIRONMENT_GET_MESSAGE_INTERFACE_VERSION:
            if (data) *static_cast<unsigned*>(data) = 1;
            return true;
        case RETRO_ENVIRONMENT_GET_TARGET_REFRESH_RATE:
            if (data) *static_cast<float*>(data) = 60.0f;
            return true;
        case RETRO_ENVIRONMENT_GET_TARGET_SAMPLE_RATE:
            if (data) *static_cast<unsigned*>(data) = 48000;
            return true;
        case RETRO_ENVIRONMENT_GET_FASTFORWARDING:
            if (data) *static_cast<bool*>(data) = false;
            return true;
        case RETRO_ENVIRONMENT_GET_JIT_CAPABLE:
            // Android permits executable JIT mappings for native emulator
            // code. Reporting false forces interpreter-only or failed boot
            // paths in Play!, Flycast and Dolphin.
            if (data) *static_cast<bool*>(data) = true;
            return true;
        case RETRO_ENVIRONMENT_GET_PERF_INTERFACE:
            if (!data) return false;
            *static_cast<retro_perf_callback*>(data) = {
                perf_time_usec, perf_cpu_features, perf_counter,
                perf_register, perf_start, perf_stop, perf_log
            };
            return true;
        case RETRO_ENVIRONMENT_GET_VFS_INTERFACE:
            // The cores have a complete local-file fallback. Explicitly clear
            // the output instead of leaving a stale pointer in a reused struct.
            if (data) static_cast<retro_vfs_interface_info*>(data)->iface = nullptr;
            return false;
        case RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE:
            if (!data) return false;
            static_cast<retro_rumble_interface*>(data)->set_rumble_state = rumble_state;
            return true;
        case RETRO_ENVIRONMENT_GET_SENSOR_INTERFACE:
            if (!data) return false;
            static_cast<retro_sensor_interface*>(data)->set_sensor_state = sensor_set_state;
            static_cast<retro_sensor_interface*>(data)->get_sensor_input = sensor_get_input;
            return true;
        case RETRO_ENVIRONMENT_GET_MICROPHONE_INTERFACE:
            return false;
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
            *static_cast<unsigned*>(data) = 0; return true;
        case RETRO_ENVIRONMENT_SET_VARIABLES: {
            auto* item = static_cast<const retro_variable*>(data);
            while (item && item->key) {
                std::string choices = item->value ? item->value : "";
                size_t split = choices.find(';');
                if (split != std::string::npos) choices = choices.substr(split + 1);
                size_t first = choices.find_first_not_of(' ');
                if (first != std::string::npos) choices.erase(0, first);
                // Flycast's threaded renderer uses a GL context from a
                // background thread. This frontend intentionally runs the
                // core on the GLSurfaceView thread, so force its safe
                // single-threaded mode even though the upstream default is
                // enabled. This also prevents a context-ownership crash on
                // Android devices.
                const std::string key = item->key;
                std::string value = choices.substr(0, choices.find('|'));
                if (key.find("_threaded_rendering") != std::string::npos)
                    value = "disabled";
                // Dolphin's default fastmem arena reserves 12 GiB of virtual
                // address space.  That is a good desktop default but is not
                // reliable on Android and can terminate the process before a
                // GameCube frame is produced.  Keep the safer single-threaded
                // path until a device-specific tuning screen exists.
                if (is_core("dolphin")) {
                    if (key == "dolphin_fastmem_arena" || key == "dolphin_fastmem" ||
                        key == "dolphin_main_cpu_thread" || key == "dolphin_main_load_game_into_memory")
                        value = "disabled";
                }
                g.variables[key] = value;
                ++item;
            }
            return true;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            auto* item = static_cast<retro_variable*>(data);
            auto found = g.variables.find(item->key ? item->key : "");
            item->value = found == g.variables.end() ? nullptr : found->second.c_str();
            return found != g.variables.end();
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            *static_cast<bool*>(data) = false; return true;
        case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER:
            // This frontend currently creates an ES 3 context for hardware
            // cores.  Returning an initialized value is important: cores
            // query this before SET_HW_RENDER and may otherwise interpret an
            // uninitialized enum as Vulkan/D3D and take an incompatible path.
            if (!data) return false;
            *static_cast<retro_hw_context_type*>(data) = is_core("dolphin")
                ? RETRO_HW_CONTEXT_OPENGLES_VERSION
                : RETRO_HW_CONTEXT_OPENGLES3;
            return true;
        case RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE:
            // Vulkan has no libretro interface object in this first GLES
            // hardware path.  Explicitly report it as unavailable rather
            // than leaving the caller's output pointer untouched.
            if (data) *static_cast<const retro_hw_render_interface**>(data) = nullptr;
            return false;
        case RETRO_ENVIRONMENT_GET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_SUPPORT:
            return false;
        case RETRO_ENVIRONMENT_SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE:
            return false;
        case RETRO_ENVIRONMENT_GET_CURRENT_SOFTWARE_FRAMEBUFFER:
            return false;
        case RETRO_ENVIRONMENT_SET_MESSAGE: {
            const auto* message = static_cast<const retro_message*>(data);
            if (message && message->msg) record_diagnostic(std::string("Core message: ") + message->msg);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_MESSAGE_EXT: {
            const auto* message = static_cast<const retro_message_ext*>(data);
            if (message && message->msg) record_diagnostic(std::string("Core message: ") + message->msg);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_FRAME_TIME_CALLBACK:
            if (!data) return false;
            g.frameTimeCallback = *static_cast<const retro_frame_time_callback*>(data);
            record_diagnostic("Registered frame-time callback");
            return true;
        case RETRO_ENVIRONMENT_SET_AUDIO_CALLBACK:
        case RETRO_ENVIRONMENT_SET_AUDIO_BUFFER_STATUS_CALLBACK:
            // Returning true without driving these callbacks makes a core
            // wait forever for frontend-owned asynchronous audio work.
            return false;
        case RETRO_ENVIRONMENT_GET_DISK_CONTROL_INTERFACE_VERSION:
            if (data) *static_cast<unsigned*>(data) = 0;
            return true;
        case RETRO_ENVIRONMENT_SET_DISK_CONTROL_INTERFACE:
            return true;
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
        case RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS:
        case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
        case RETRO_ENVIRONMENT_SET_MINIMUM_AUDIO_LATENCY:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
            return true;
        case RETRO_ENVIRONMENT_SET_HW_SHARED_CONTEXT:
            // The frontend and core intentionally use the same current EGL
            // context on the GLSurfaceView thread. This is stronger than two
            // contexts sharing an object namespace and satisfies Dolphin's
            // requirement that its output be visible to the frontend.
            record_diagnostic("Core requested a shared GL context; using the common frontend/core context");
            return true;
        default:
            __android_log_print(ANDROID_LOG_DEBUG, "EmuAllNative", "Unsupported libretro environment command: %u", command);
            return false;
    }
}

void video(const void* data, unsigned width, unsigned height, size_t pitch) {
    const uint64_t count = g.videoCallbacks.fetch_add(1) + 1;
    if (count == 1) {
        record_diagnostic(std::string("First video callback: ") +
            (data == RETRO_HW_FRAME_BUFFER_VALID ? "hardware" : data ? "software" : "duplicate") +
            " " + std::to_string(width) + "x" + std::to_string(height));
    }
    if (!data || data == RETRO_HW_FRAME_BUFFER_VALID || !width || !height) return;
    size_t bpp = g.pixelFormat == RETRO_PIXEL_FORMAT_XRGB8888 ? 4 : 2;
    size_t rowBytes = width * bpp;
    std::lock_guard lock(g.frameMutex);
    g.frame.resize(rowBytes * height);
    auto* source = static_cast<const uint8_t*>(data);
    for (unsigned row = 0; row < height; ++row)
        std::memcpy(g.frame.data() + row * rowBytes, source + row * pitch, rowBytes);
    g.width = width;
    g.height = height;
}

void audio_sample(int16_t left, int16_t right) {
    std::lock_guard lock(g.audioMutex);
    g.audio.push_back(left); g.audio.push_back(right);
}

size_t audio_batch(const int16_t* data, size_t frames) {
    if (!data) return frames;
    std::lock_guard lock(g.audioMutex);
    g.audio.insert(g.audio.end(), data, data + frames * 2);
    while (g.audio.size() > 65536) g.audio.pop_front();
    return frames;
}

void input_poll() {}
int16_t input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port) return 0;
    if (device == RETRO_DEVICE_ANALOG) {
        if (index > RETRO_DEVICE_INDEX_ANALOG_RIGHT) return 0;
        if (id == RETRO_DEVICE_ID_ANALOG_X) return g.analogX[index].load(std::memory_order_relaxed);
        if (id == RETRO_DEVICE_ID_ANALOG_Y) return g.analogY[index].load(std::memory_order_relaxed);
        return 0;
    }
    if (device != RETRO_DEVICE_JOYPAD) return 0;
    uint32_t mask = g.inputMask.load(std::memory_order_relaxed);
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return static_cast<int16_t>(mask);
    return id < 32 && (mask & (1u << id)) ? 1 : 0;
}

template <typename T> bool get_symbol(const char* name, T& target) {
    target = reinterpret_cast<T>(dlsym(g.api.handle, name));
    if (target) return true;
    g.lastError = std::string("Core is missing ") + name;
    return false;
}

bool load_api(const char* path) {
    g.api.handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (!g.api.handle) {
        const char* error = dlerror();
        g.lastError = std::string("Could not load libretro core: ") + (error ? error : "unknown error");
        return false;
    }
#define LOAD(symbol, field) if (!get_symbol(symbol, g.api.field)) return false
    LOAD("retro_api_version", apiVersion); LOAD("retro_set_environment", setEnvironment);
    LOAD("retro_set_video_refresh", setVideoRefresh); LOAD("retro_set_audio_sample", setAudioSample);
    LOAD("retro_set_audio_sample_batch", setAudioBatch); LOAD("retro_set_input_poll", setInputPoll);
    LOAD("retro_set_input_state", setInputState); LOAD("retro_get_system_info", getSystemInfo);
    LOAD("retro_set_controller_port_device", setControllerPortDevice);
    LOAD("retro_get_system_av_info", getSystemAvInfo); LOAD("retro_init", init);
    LOAD("retro_deinit", deinit); LOAD("retro_load_game", loadGame);
    LOAD("retro_unload_game", unloadGame); LOAD("retro_run", run); LOAD("retro_reset", reset);
    LOAD("retro_serialize_size", serializeSize); LOAD("retro_serialize", serialize);
    LOAD("retro_unserialize", unserialize);
    LOAD("retro_get_memory_data", memoryData); LOAD("retro_get_memory_size", memorySize);
#undef LOAD
    unsigned apiVersion = 0;
    if (!call_core_bool("retro_api_version", [&] { apiVersion = g.api.apiVersion(); return true; })) return false;
    if (apiVersion != RETRO_API_VERSION) { g.lastError = "Unsupported libretro API"; return false; }
    return true;
}

bool prefers_filesystem_game_path(const std::string& corePath) {
    // These cores stream disc images from the path supplied in
    // retro_game_info. Never copy a multi-gigabyte image into the frontend's
    // in-memory ROM buffer even if a particular build reports
    // need_fullpath incorrectly.
    return corePath.find("dolphin") != std::string::npos ||
        corePath.find("flycast") != std::string::npos ||
        corePath.find("ppsspp") != std::string::npos ||
        corePath.find("play_") != std::string::npos;
}

bool read_file(const std::string& path, std::vector<uint8_t>& output) {
    std::ifstream stream(path, std::ios::binary | std::ios::ate);
    if (!stream) return false;
    auto length = stream.tellg();
    if (length <= 0) return false;
    output.resize(static_cast<size_t>(length)); stream.seekg(0);
    return static_cast<bool>(stream.read(reinterpret_cast<char*>(output.data()), length));
}

void save_battery() {
    if (!g.gameLoaded || g.savePath.empty()) return;
    size_t size = 0;
    void* data = nullptr;
    if (!call_core_bool("retro_get_memory_size", [&] {
        size = g.api.memorySize(RETRO_MEMORY_SAVE_RAM);
        data = g.api.memoryData(RETRO_MEMORY_SAVE_RAM);
        return true;
    })) return;
    if (!size || !data) return;
    std::ofstream output(g.savePath, std::ios::binary | std::ios::trunc);
    output.write(static_cast<char*>(data), static_cast<std::streamsize>(size));
}

bool quick_save(const std::string& path) {
    if (!g.gameLoaded) { g.lastError = "No game is running"; return false; }
    size_t size = 0;
    if (!call_core_bool("retro_serialize_size", [&] { size = g.api.serializeSize(); return true; })) return false;
    if (!size) { g.lastError = "This core does not support save states"; return false; }
    std::vector<uint8_t> state(size);
    if (!call_core_bool("retro_serialize", [&] { return g.api.serialize(state.data(), state.size()); })) {
        g.lastError = "The core could not create a save state";
        return false;
    }
    std::string temporaryPath = path + ".tmp";
    std::ofstream output(temporaryPath, std::ios::binary | std::ios::trunc);
    output.write(reinterpret_cast<const char*>(state.data()), static_cast<std::streamsize>(state.size()));
    output.close();
    if (!output || std::rename(temporaryPath.c_str(), path.c_str()) != 0) {
        std::remove(temporaryPath.c_str());
        g.lastError = "Could not write the quick save";
        return false;
    }
    return true;
}

bool quick_load(const std::string& path) {
    if (!g.gameLoaded) { g.lastError = "No game is running"; return false; }
    std::vector<uint8_t> state;
    if (!read_file(path, state)) { g.lastError = "No quick save exists for this game"; return false; }
    if (!call_core_bool("retro_unserialize", [&] { return g.api.unserialize(state.data(), state.size()); })) {
        g.lastError = "This quick save is not compatible with the current core";
        return false;
    }
    std::lock_guard lock(g.audioMutex);
    g.audio.clear();
    return true;
}

void stop_session() {
    const std::string failure = g.lastError;
    if (g.hardwareContextConfigured && g.hardwareCallback.context_destroy)
        call_core_void("hardware context destroy", [&] { g.hardwareCallback.context_destroy(); });
    if (g.gameLoaded) {
        save_battery();
        call_core_void("retro_unload_game", [&] { g.api.unloadGame(); });
        g.gameLoaded = false;
    }
    if (g.initialized) {
        call_core_void("retro_deinit", [&] { g.api.deinit(); });
        g.initialized = false;
    }
    if (g.api.handle) dlclose(g.api.handle);
    g.api = {}; g.rom.clear(); g.frame.clear(); g.audio.clear(); g.variables.clear();
    g.corePath.clear();
    g.inputMask = 0;
    g.analogX[0] = 0; g.analogX[1] = 0;
    g.analogY[0] = 0; g.analogY[1] = 0;
    g.width = 0; g.height = 0; g.videoAspectRatio = 0.0; g.hardwareFramebuffer = 0; g.hardwareRendering = false;
    g.hardwareContextConfigured = false; g.hardwareCallback = {};
    g.frameTimeCallback = {};
    g.runCalls = 0;
    g.videoCallbacks = 0;
    // Cleanup can itself call into a partially initialized core.  Do not let
    // a secondary teardown error hide the startup/run failure the user needs.
    if (!failure.empty()) g.lastError = failure;
}

std::string from_java(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_frontendVersion(JNIEnv* env, jobject) {
    return env->NewStringUTF("Libretro v1 · Vulkan preferred · GLES3 hardware path");
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_start(
        JNIEnv* env, jobject, jstring corePath, jstring romPath, jstring savePath, jstring systemDirectory,
        jboolean hardwareRendering) {
    // The GL view binds its frontend FBO before starting the core. Preserve
    // that ID across the session cleanup below; otherwise stop_session()
    // would erase it just before retro_load_game() negotiates hardware
    // rendering and the core would silently fall back to framebuffer 0.
    const uintptr_t requestedFramebuffer = g.hardwareFramebuffer;
    const unsigned availableGlesMajor = g.glesMajor;
    const unsigned availableGlesMinor = g.glesMinor;
    stop_session();
    g.hardwareFramebuffer = requestedFramebuffer;
    g.glesMajor = availableGlesMajor;
    g.glesMinor = availableGlesMinor;
    g.lastError.clear();
    g.graphicsNegotiationError.clear();
    {
        std::lock_guard lock(g.logMutex);
        g.lastCoreError.clear();
        g.diagnostics.clear();
    }
    g.hardwareRendering = hardwareRendering == JNI_TRUE;
    g.savePath = from_java(env, savePath); g.systemDirectory = from_java(env, systemDirectory);
    size_t slash = g.savePath.find_last_of('/');
    g.saveDirectory = slash == std::string::npos ? g.systemDirectory : g.savePath.substr(0, slash);
    open_diagnostic_file(g.saveDirectory + "/last_core_diagnostics.txt");
    std::string core = from_java(env, corePath), romPathValue = from_java(env, romPath);
    g.corePath = core;
    // Play!'s Android adapter reads EXTERNAL_STORAGE during retro_init().
    // Modern app processes are not guaranteed to define it, and passing a
    // null value into its path setup throws before the ROM is inspected.
    if (!g.systemDirectory.empty()) setenv("EXTERNAL_STORAGE", g.systemDirectory.c_str(), 1);
    __android_log_print(ANDROID_LOG_INFO, "EmuAllNative", "Starting core=%s rom=%s hardware=%d system=%s",
        core.c_str(), romPathValue.c_str(), g.hardwareRendering ? 1 : 0, g.systemDirectory.c_str());
    record_diagnostic("Start requested: core=" + core + " content=" + romPathValue +
        " system=" + g.systemDirectory + " hardware=" + (g.hardwareRendering ? "yes" : "no"));
    record_diagnostic("Frontend framebuffer=" + std::to_string(g.hardwareFramebuffer) +
        " GLES=" + std::to_string(g.glesMajor) + "." + std::to_string(g.glesMinor));
    if (!load_api(core.c_str())) {
        stop_session();
        return false;
    }
    record_diagnostic("Loaded libretro API symbols");
    g.api.setEnvironment(environment); g.api.setVideoRefresh(video);
    g.api.setAudioSample(audio_sample); g.api.setAudioBatch(audio_batch);
    g.api.setInputPoll(input_poll); g.api.setInputState(input_state);
    if (!call_core_void("retro_init", [&] { g.api.init(); })) {
        stop_session();
        return false;
    }
    g.initialized = true;
    record_diagnostic("retro_init completed");
    // Some cores request hardware rendering from retro_init(), while others
    // (including Mupen64Plus-Next) do it from retro_load_game().  Reset any
    // context negotiated during init, then repeat the handshake after
    // retro_load_game() so the core's callbacks are always invoked only after
    // the game has requested them and the Android GL context is current.
    if (g.hardwareContextConfigured && g.hardwareCallback.context_reset &&
        !call_core_void("hardware context reset", [&] { g.hardwareCallback.context_reset(); })) {
        stop_session();
        return false;
    }
    retro_system_info systemInfo{};
    if (!call_core_void("retro_get_system_info", [&] { g.api.getSystemInfo(&systemInfo); })) {
        stop_session();
        return false;
    }
    record_diagnostic("System info: library=" + std::string(systemInfo.library_name ? systemInfo.library_name : "unknown") +
        " version=" + std::string(systemInfo.library_version ? systemInfo.library_version : "unknown") +
        " need_fullpath=" + (systemInfo.need_fullpath ? "yes" : "no") +
        " extensions=" + std::string(systemInfo.valid_extensions ? systemInfo.valid_extensions : ""));
    // Disc-based cores (Dolphin, Flycast and several PSP builds) set
    // need_fullpath because they stream large images from disk. Reading a
    // 1.3 GB GameCube ISO into g.rom first can exhaust a phone's heap and
    // terminate the app before the core even gets a chance to reject or boot
    // the file. Only in-memory cores need the frontend-owned ROM buffer.
    const bool useFilesystemPath = systemInfo.need_fullpath || prefers_filesystem_game_path(core);
    __android_log_print(ANDROID_LOG_INFO, "EmuAllNative",
        "Core game path mode: need_fullpath=%d filesystem=%d",
        systemInfo.need_fullpath ? 1 : 0, useFilesystemPath ? 1 : 0);
    if (!useFilesystemPath && !read_file(romPathValue, g.rom)) {
        g.lastError = "Could not read game";
        stop_session();
        return false;
    }
    retro_game_info game{}; game.path = romPathValue.c_str();
    game.data = useFilesystemPath ? nullptr : g.rom.data();
    game.size = useFilesystemPath ? 0 : g.rom.size();
    record_diagnostic("Calling retro_load_game with " + std::string(useFilesystemPath ? "filesystem path" : "memory buffer"));
    if (!call_core_bool("retro_load_game", [&] { return g.api.loadGame(&game); })) {
        if (g.lastError.empty()) {
            std::lock_guard lock(g.logMutex);
            g.lastError = g.lastCoreError.empty()
                ? "The selected core rejected this file"
                : g.lastCoreError;
        }
        stop_session();
        return false;
    }
    record_diagnostic("retro_load_game accepted content");
    call_core_void("retro_set_controller_port_device", [&] {
        g.api.setControllerPortDevice(0, RETRO_DEVICE_JOYPAD);
    });
    if (g.hardwareRendering) {
        if (!g.hardwareContextConfigured || !g.hardwareCallback.context_reset) {
            g.lastError = g.graphicsNegotiationError.empty()
                ? "The core did not negotiate a supported OpenGL ES graphics context"
                : g.graphicsNegotiationError;
            stop_session();
            return false;
        }
        if (!call_core_void("hardware context reset", [&] { g.hardwareCallback.context_reset(); })) {
            stop_session();
            return false;
        }
        record_diagnostic("Hardware context reset completed");
    }
    g.gameLoaded = true;
    retro_system_av_info av{};
    if (!call_core_void("retro_get_system_av_info", [&] { g.api.getSystemAvInfo(&av); })) {
        stop_session();
        return false;
    }
    g.fps = av.timing.fps; g.sampleRate = av.timing.sample_rate;
    g.videoAspectRatio = av.geometry.aspect_ratio > 0.0
        ? av.geometry.aspect_ratio
        : (av.geometry.base_height ? static_cast<double>(av.geometry.base_width) / av.geometry.base_height : 0.0);
    record_diagnostic("AV info: " + std::to_string(av.geometry.base_width) + "x" +
        std::to_string(av.geometry.base_height) + " max=" + std::to_string(av.geometry.max_width) + "x" +
        std::to_string(av.geometry.max_height) + " fps=" + std::to_string(g.fps) +
        " sample_rate=" + std::to_string(g.sampleRate));
    std::vector<uint8_t> save;
    if (read_file(g.savePath, save)) {
        call_core_void("restore battery save", [&] {
            size_t size = g.api.memorySize(RETRO_MEMORY_SAVE_RAM);
            void* memory = g.api.memoryData(RETRO_MEMORY_SAVE_RAM);
            if (size && memory) std::memcpy(memory, save.data(), std::min(size, save.size()));
        });
    }
    return true;
}

extern "C" JNIEXPORT void JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_hardwareContextReset(JNIEnv*, jobject) {
    if (g.hardwareContextConfigured && g.hardwareCallback.context_reset)
        call_core_void("hardware context reset", [&] { g.hardwareCallback.context_reset(); });
}

extern "C" JNIEXPORT void JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_hardwareContextDestroy(JNIEnv*, jobject) {
    if (g.hardwareContextConfigured && g.hardwareCallback.context_destroy)
        call_core_void("hardware context destroy", [&] { g.hardwareCallback.context_destroy(); });
}

extern "C" JNIEXPORT void JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_setHardwareFramebuffer(JNIEnv*, jobject, jint framebuffer) {
    g.hardwareFramebuffer = framebuffer > 0 ? static_cast<uintptr_t>(framebuffer) : 0;
}

extern "C" JNIEXPORT void JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_setGraphicsContextVersion(
        JNIEnv*, jobject, jint major, jint minor) {
    if (major < 2) return;
    g.glesMajor = static_cast<unsigned>(major);
    g.glesMinor = static_cast<unsigned>(std::max(0, static_cast<int>(minor)));
    __android_log_print(ANDROID_LOG_INFO, "EmuAllNative", "Android OpenGL ES context: %u.%u",
        g.glesMajor, g.glesMinor);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_runFrame(JNIEnv*, jobject) {
    if (!g.gameLoaded) return JNI_FALSE;
    const uint64_t runNumber = g.runCalls.fetch_add(1) + 1;
    if (runNumber == 1) record_diagnostic("Entering first retro_run");
    if (g.frameTimeCallback.callback && !call_core_void("frame time callback", [&] {
        g.frameTimeCallback.callback(g.frameTimeCallback.reference > 0
            ? g.frameTimeCallback.reference
            : static_cast<retro_usec_t>(1000000.0 / std::max(1.0, g.fps)));
    })) {
        stop_session();
        return JNI_FALSE;
    }
    if (call_core_void("retro_run", [&] { g.api.run(); })) {
        if (runNumber == 1) record_diagnostic("First retro_run completed");
        return JNI_TRUE;
    }
    // A C++ exception escaping a core callback must not leave the frontend
    // driving a half-torn-down core on the next frame. Keep this cleanup on
    // the same GL/emulation thread that invoked retro_run().
    stop_session();
    return JNI_FALSE;
}
extern "C" JNIEXPORT jint JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_copyFrame(JNIEnv* env, jobject, jobject output) {
    void* target = env->GetDirectBufferAddress(output); jlong capacity = env->GetDirectBufferCapacity(output);
    if (!target || capacity <= 0) return 0;
    std::lock_guard lock(g.frameMutex); size_t count = std::min(static_cast<size_t>(capacity), g.frame.size());
    std::memcpy(target, g.frame.data(), count); return static_cast<jint>(count);
}
extern "C" JNIEXPORT jint JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_frameWidth(JNIEnv*, jobject) { return g.width; }
extern "C" JNIEXPORT jint JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_frameHeight(JNIEnv*, jobject) { return g.height; }
extern "C" JNIEXPORT jfloat JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_videoAspectRatio(JNIEnv*, jobject) {
    return static_cast<jfloat>(g.videoAspectRatio);
}
extern "C" JNIEXPORT jint JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_pixelFormat(JNIEnv*, jobject) { return g.pixelFormat; }
extern "C" JNIEXPORT jint JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_drainAudio(JNIEnv* env, jobject, jshortArray output) {
    jsize capacity = env->GetArrayLength(output);
    jshort* target = env->GetShortArrayElements(output, nullptr);
    if (!target) return 0;
    size_t count;
    {
        std::lock_guard lock(g.audioMutex);
        count = std::min(static_cast<size_t>(capacity), g.audio.size());
        for (size_t index = 0; index < count; ++index) {
            target[index] = g.audio.front();
            g.audio.pop_front();
        }
    }
    env->ReleaseShortArrayElements(output, target, 0);
    return static_cast<jint>(count);
}
extern "C" JNIEXPORT jint JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_sampleRate(JNIEnv*, jobject) { return static_cast<jint>(g.sampleRate + .5); }
extern "C" JNIEXPORT jdouble JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_framesPerSecond(JNIEnv*, jobject) { return g.fps; }
extern "C" JNIEXPORT void JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_setInputMask(JNIEnv*, jobject, jint mask) { g.inputMask = static_cast<uint32_t>(mask); }
extern "C" JNIEXPORT void JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_setAnalog(JNIEnv*, jobject, jint stick, jint x, jint y) {
    if (stick < 0 || stick > 1) return;
    const auto clamp = [](jint value) {
        return static_cast<int16_t>(std::max(-32767, std::min(32767, static_cast<int>(value))));
    };
    g.analogX[stick] = clamp(x);
    g.analogY[stick] = clamp(y);
}
extern "C" JNIEXPORT void JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_reset(JNIEnv*, jobject) {
    if (g.gameLoaded) call_core_void("retro_reset", [&] { g.api.reset(); });
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_quickSave(JNIEnv* env, jobject, jstring path) {
    return quick_save(from_java(env, path));
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_quickLoad(JNIEnv* env, jobject, jstring path) {
    return quick_load(from_java(env, path));
}
extern "C" JNIEXPORT void JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_saveBattery(JNIEnv*, jobject) { save_battery(); }
extern "C" JNIEXPORT void JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_stop(JNIEnv*, jobject) { stop_session(); }
extern "C" JNIEXPORT jstring JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_lastError(JNIEnv* env, jobject) {
    if (!g.lastError.empty()) return env->NewStringUTF(g.lastError.c_str());
    std::lock_guard lock(g.logMutex);
    return env->NewStringUTF(g.lastCoreError.c_str());
}
extern "C" JNIEXPORT jstring JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_diagnostics(JNIEnv* env, jobject) {
    std::lock_guard lock(g.logMutex);
    std::string report;
    for (const auto& line : g.diagnostics) {
        if (!report.empty()) report += '\n';
        report += line;
    }
    if (!g.lastError.empty()) {
        if (!report.empty()) report += "\n\n";
        report += "Last frontend error: " + g.lastError;
    }
    return env->NewStringUTF(report.c_str());
}
