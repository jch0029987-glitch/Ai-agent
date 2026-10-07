cmake_minimum_required(VERSION 3.22.1)
project("agent_jni")

set(GGML_VULKAN ON CACHE BOOL "Enable Vulkan backend for GGML" FORCE)
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
find_library(atomic-lib atomic)

target_include_directories(agent_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/ggml/include
)

target_link_libraries(
    agent_jni
    llama
    ggml
    ggml-vulkan
    ${log-lib}
    ${atomic-lib}
    m
)
