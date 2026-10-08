cmake_minimum_required(VERSION 3.22.1)
project("agent_jni")

# Disable treating warnings as errors to prevent NDK 27 / ggml-quants.c build failures
add_compile_options(-Wno-error)

set(GGML_VULKAN OFF CACHE BOOL "Disable Vulkan backend" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "Disable OpenMP to avoid missing libomp.so on Android" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build static libraries for embedding" FORCE)

if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/CMakeLists.txt")
    add_subdirectory("llama.cpp")
else()
    message(FATAL_ERROR "llama.cpp source directory not found in cpp/llama.cpp")
endif()

add_library(
    agent_jni
    SHARED
    llama_jni.cpp
)

find_library(log-lib log)

target_include_directories(agent_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/ggml/include
)

target_link_libraries(
    agent_jni
    llama
    ggml
    ${log-lib}
    atomic
    m
)
