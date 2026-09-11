cmake_minimum_required(VERSION 3.22.1)
project(emuall_frontend LANGUAGES C CXX)

set(BUILD_LIBRETRO ON CACHE BOOL "" FORCE)
set(M_CORE_GBA ON CACHE BOOL "" FORCE)
set(M_CORE_GB ON CACHE BOOL "" FORCE)
set(SKIP_LIBRARY ON CACHE BOOL "" FORCE)
set(BUILD_QT OFF CACHE BOOL "" FORCE)
set(BUILD_SDL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(BUILD_STATIC OFF CACHE BOOL "" FORCE)
set(BUILD_GL OFF CACHE BOOL "" FORCE)
set(BUILD_GLES2 OFF CACHE BOOL "" FORCE)
set(BUILD_GLES3 OFF CACHE BOOL "" FORCE)
set(USE_FFMPEG OFF CACHE BOOL "" FORCE)
set(USE_SQLITE3 OFF CACHE BOOL "" FORCE)
set(USE_LUA OFF CACHE BOOL "" FORCE)
set(USE_DISCORD_RPC OFF CACHE BOOL "" FORCE)
set(USE_LIBZIP OFF CACHE BOOL "" FORCE)
set(USE_LZMA OFF CACHE BOOL "" FORCE)
set(M_CORE_GBA ON CACHE BOOL "" FORCE)
set(M_CORE_GB OFF CACHE BOOL "" FORCE)
add_subdirectory(cores/mgba mgba-build EXCLUDE_FROM_ALL)

add_library(emuall_frontend SHARED native_frontend.cpp)
add_dependencies(emuall_frontend mgba_libretro)

find_library(log-lib log)
find_library(android-lib android)
find_library(gles-lib GLESv2)
find_library(dl-lib dl)

target_link_libraries(
    emuall_frontend
    ${log-lib}
    ${android-lib}
    ${gles-lib}
    ${dl-lib}
)
