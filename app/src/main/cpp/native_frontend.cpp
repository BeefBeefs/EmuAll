#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>

#include <algorithm>
#include <atomic>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <deque>
#include <fstream>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

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
    unsigned width{};
    unsigned height{};
    unsigned pixelFormat{RETRO_PIXEL_FORMAT_RGB565};
    double fps{59.7275};
    double sampleRate{32768.0};
    std::string savePath;
    std::string systemDirectory;
    std::string saveDirectory;
    std::string lastError;
    std::unordered_map<std::string, std::string> variables;
    bool initialized{};
    bool gameLoaded{};
} g;

void core_log(enum retro_log_level level, const char* format, ...) {
    int priority = level == RETRO_LOG_ERROR ? ANDROID_LOG_ERROR :
        level == RETRO_LOG_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
    va_list args;
    va_start(args, format);
    __android_log_vprint(priority, "EmuAllNative", format, args);
    va_end(args);
}

bool environment(unsigned command, void* data) {
    switch (command) {
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            auto format = *static_cast<const retro_pixel_format*>(data);
            if (format != RETRO_PIXEL_FORMAT_RGB565 && format != RETRO_PIXEL_FORMAT_XRGB8888) return false;
            g.pixelFormat = format;
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
                g.variables[item->key] = choices.substr(0, choices.find('|'));
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
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
        case RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS:
        case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
        case RETRO_ENVIRONMENT_SET_MINIMUM_AUDIO_LATENCY:
        case RETRO_ENVIRONMENT_SET_MESSAGE:
            return true;
        default: return false;
    }
}

void video(const void* data, unsigned width, unsigned height, size_t pitch) {
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
int16_t input_state(unsigned port, unsigned device, unsigned, unsigned id) {
    if (port || device != RETRO_DEVICE_JOYPAD) return 0;
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
        g.lastError = std::string("Could not load mGBA: ") + (error ? error : "unknown error");
        return false;
    }
#define LOAD(symbol, field) if (!get_symbol(symbol, g.api.field)) return false
    LOAD("retro_api_version", apiVersion); LOAD("retro_set_environment", setEnvironment);
    LOAD("retro_set_video_refresh", setVideoRefresh); LOAD("retro_set_audio_sample", setAudioSample);
    LOAD("retro_set_audio_sample_batch", setAudioBatch); LOAD("retro_set_input_poll", setInputPoll);
    LOAD("retro_set_input_state", setInputState); LOAD("retro_get_system_info", getSystemInfo);
    LOAD("retro_get_system_av_info", getSystemAvInfo); LOAD("retro_init", init);
    LOAD("retro_deinit", deinit); LOAD("retro_load_game", loadGame);
    LOAD("retro_unload_game", unloadGame); LOAD("retro_run", run); LOAD("retro_reset", reset);
    LOAD("retro_serialize_size", serializeSize); LOAD("retro_serialize", serialize);
    LOAD("retro_unserialize", unserialize);
    LOAD("retro_get_memory_data", memoryData); LOAD("retro_get_memory_size", memorySize);
#undef LOAD
    if (g.api.apiVersion() != RETRO_API_VERSION) { g.lastError = "Unsupported libretro API"; return false; }
    return true;
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
    size_t size = g.api.memorySize(RETRO_MEMORY_SAVE_RAM);
    void* data = g.api.memoryData(RETRO_MEMORY_SAVE_RAM);
    if (!size || !data) return;
    std::ofstream output(g.savePath, std::ios::binary | std::ios::trunc);
    output.write(static_cast<char*>(data), static_cast<std::streamsize>(size));
}

bool quick_save(const std::string& path) {
    if (!g.gameLoaded) { g.lastError = "No game is running"; return false; }
    size_t size = g.api.serializeSize();
    if (!size) { g.lastError = "This core does not support save states"; return false; }
    std::vector<uint8_t> state(size);
    if (!g.api.serialize(state.data(), state.size())) {
        g.lastError = "mGBA could not create a save state";
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
    if (!g.api.unserialize(state.data(), state.size())) {
        g.lastError = "This quick save is not compatible with the current core";
        return false;
    }
    std::lock_guard lock(g.audioMutex);
    g.audio.clear();
    return true;
}

void stop_session() {
    if (g.gameLoaded) { save_battery(); g.api.unloadGame(); g.gameLoaded = false; }
    if (g.initialized) { g.api.deinit(); g.initialized = false; }
    if (g.api.handle) dlclose(g.api.handle);
    g.api = {}; g.rom.clear(); g.frame.clear(); g.audio.clear();
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
    return env->NewStringUTF("Libretro v1 · Vulkan preferred");
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_start(
        JNIEnv* env, jobject, jstring corePath, jstring romPath, jstring savePath, jstring systemDirectory) {
    stop_session(); g.lastError.clear();
    g.savePath = from_java(env, savePath); g.systemDirectory = from_java(env, systemDirectory);
    size_t slash = g.savePath.find_last_of('/');
    g.saveDirectory = slash == std::string::npos ? g.systemDirectory : g.savePath.substr(0, slash);
    std::string core = from_java(env, corePath), romPathValue = from_java(env, romPath);
    if (!load_api(core.c_str())) return false;
    if (!read_file(romPathValue, g.rom)) { g.lastError = "Could not read game"; stop_session(); return false; }
    g.api.setEnvironment(environment); g.api.setVideoRefresh(video);
    g.api.setAudioSample(audio_sample); g.api.setAudioBatch(audio_batch);
    g.api.setInputPoll(input_poll); g.api.setInputState(input_state);
    g.api.init(); g.initialized = true;
    retro_system_info systemInfo{}; g.api.getSystemInfo(&systemInfo);
    retro_game_info game{}; game.path = romPathValue.c_str();
    game.data = systemInfo.need_fullpath ? nullptr : g.rom.data();
    game.size = systemInfo.need_fullpath ? 0 : g.rom.size();
    if (!g.api.loadGame(&game)) { g.lastError = "mGBA rejected this file"; stop_session(); return false; }
    g.gameLoaded = true;
    retro_system_av_info av{}; g.api.getSystemAvInfo(&av); g.fps = av.timing.fps; g.sampleRate = av.timing.sample_rate;
    std::vector<uint8_t> save;
    if (read_file(g.savePath, save)) {
        size_t size = g.api.memorySize(RETRO_MEMORY_SAVE_RAM); void* memory = g.api.memoryData(RETRO_MEMORY_SAVE_RAM);
        if (size && memory) std::memcpy(memory, save.data(), std::min(size, save.size()));
    }
    return true;
}

extern "C" JNIEXPORT void JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_runFrame(JNIEnv*, jobject) { if (g.gameLoaded) g.api.run(); }
extern "C" JNIEXPORT jint JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_copyFrame(JNIEnv* env, jobject, jobject output) {
    void* target = env->GetDirectBufferAddress(output); jlong capacity = env->GetDirectBufferCapacity(output);
    if (!target || capacity <= 0) return 0;
    std::lock_guard lock(g.frameMutex); size_t count = std::min(static_cast<size_t>(capacity), g.frame.size());
    std::memcpy(target, g.frame.data(), count); return static_cast<jint>(count);
}
extern "C" JNIEXPORT jint JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_frameWidth(JNIEnv*, jobject) { return g.width; }
extern "C" JNIEXPORT jint JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_frameHeight(JNIEnv*, jobject) { return g.height; }
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
extern "C" JNIEXPORT void JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_reset(JNIEnv*, jobject) { if (g.gameLoaded) g.api.reset(); }
extern "C" JNIEXPORT jboolean JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_quickSave(JNIEnv* env, jobject, jstring path) {
    return quick_save(from_java(env, path));
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_quickLoad(JNIEnv* env, jobject, jstring path) {
    return quick_load(from_java(env, path));
}
extern "C" JNIEXPORT void JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_saveBattery(JNIEnv*, jobject) { save_battery(); }
extern "C" JNIEXPORT void JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_stop(JNIEnv*, jobject) { stop_session(); }
extern "C" JNIEXPORT jstring JNICALL Java_com_beefbeefs_emuall_NativeCoreBridge_lastError(JNIEnv* env, jobject) { return env->NewStringUTF(g.lastError.c_str()); }
