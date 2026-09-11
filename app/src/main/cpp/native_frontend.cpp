#include <jni.h>
#include <dlfcn.h>
#include <string>

namespace {
constexpr unsigned kExpectedLibretroApiVersion = 1;

using RetroApiVersion = unsigned (*)();

std::string inspect_core(const char* path) {
    void* handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) {
        const char* error = dlerror();
        return std::string("Core load failed: ") + (error == nullptr ? "unknown error" : error);
    }

    auto api_version = reinterpret_cast<RetroApiVersion>(dlsym(handle, "retro_api_version"));
    if (api_version == nullptr) {
        dlclose(handle);
        return "Not a libretro core: retro_api_version is missing";
    }

    const unsigned version = api_version();
    dlclose(handle);
    if (version != kExpectedLibretroApiVersion) {
        return "Unsupported libretro API version " + std::to_string(version);
    }
    return "Compatible libretro API v" + std::to_string(version);
}
}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_beefbeefs_emuall_NativeCoreBridge_frontendVersion(JNIEnv* env, jobject) {
    return env->NewStringUTF("Libretro frontend API v1");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_beefbeefs_emuall_NativeCoreBridge_inspectCore(JNIEnv* env, jobject, jstring path) {
    const char* native_path = env->GetStringUTFChars(path, nullptr);
    const std::string result = inspect_core(native_path);
    env->ReleaseStringUTFChars(path, native_path);
    return env->NewStringUTF(result.c_str());
}
