cmake_minimum_required(VERSION 3.22.1)
project(emuall_frontend LANGUAGES CXX)

add_library(emuall_frontend SHARED native_frontend.cpp)

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
