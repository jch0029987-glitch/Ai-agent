cmake_minimum_required(VERSION 3.22.1)
project("agent_jni")

# Disable treating warnings as errors to prevent NDK build failures
add_compile_options(-Wno-error)

# Target Pixel 9 (Tensor G4) ARMv9 vector/matrix extensions explicitly
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv8.2-a+dotprod+i8mm")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8.2-a+dotprod+i8mm")

# Aggressive CPU performance & vector flags for llama.cpp / GGML
set(GGML_VULKAN OFF CACHE BOOL "Disable Vulkan to avoid mobile driver overhead" FORCE)
set(GGML_CPU_ARM_FP16 ON CACHE BOOL "Enable ARM FP16 vector math acceleration" FORCE)
set(GGML_CPU_KLEIDIAI ON CACHE BOOL "Enable Arm KleidiAI optimized matrix kernels for i8mm/dotprod" FORCE)
set(GGML_LLAMAFILE ON CACHE BOOL "Enable high-performance llamafile SGEMM kernels" FORCE)

# Ensure server target and core components are built
set(LLAMA_BUILD_SERVER ON CACHE BOOL "Build llama-server target for local HTTP API" FORCE)

# Handle OpenMP safely for Android NDK cross-compilation
find_package(OpenMP QUIET)
if(OpenMP_CXX_FOUND)
    set(GGML_OPENMP ON CACHE BOOL "Enable OpenMP for multi-core parallelism" FORCE)
    message(STATUS "OpenMP found and enabled for Android cross-compilation.")
else()
    set(GGML_OPENMP OFF CACHE BOOL "Disable OpenMP due to missing NDK runtime target" FORCE)
    message(WARNING "OpenMP not found in NDK toolchain; building without OpenMP multi-processing.")
endif()

# Build configurations for performance
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
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

# Conditional linking to prevent target_link_libraries crash if OpenMP target is absent
set(AGENT_LINK_LIBS
    llama
    ggml
    ${log-lib}
    atomic
    m
)

if(OpenMP_CXX_FOUND)
    list(APPEND AGENT_LINK_LIBS OpenMP::OpenMP_CXX)
endif()

target_link_libraries(
    agent_jni
    PRIVATE
    ${AGENT_LINK_LIBS}
)
