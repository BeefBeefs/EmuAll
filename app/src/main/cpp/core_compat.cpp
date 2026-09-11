#include <cerrno>
#include <cstddef>
#include <dlfcn.h>

// Flycast's Android build intentionally imports this function as a weak
// symbol without declaring libandroid.so as a dependency. A libretro core
// loaded from another JNI DSO therefore sees a null weak import even though
// the platform function exists. This small globally-loaded bridge gives the
// core a strong symbol while dispatching to Android's real implementation.
extern "C" __attribute__((visibility("default")))
int ASharedMemory_create(const char* name, size_t size) {
    using PlatformFunction = int (*)(const char*, size_t);
    static void* platform = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
    static auto create = platform
        ? reinterpret_cast<PlatformFunction>(dlsym(platform, "ASharedMemory_create"))
        : nullptr;
    if (!create) {
        errno = ENOSYS;
        return -1;
    }
    return create(name, size);
}
