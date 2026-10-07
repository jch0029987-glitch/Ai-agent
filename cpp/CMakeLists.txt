cmake_minimum_required(VERSION 3.22.1)
project("agent_jni")

# Bring in llama.cpp source subdirectory if present, or link via targets
# Ensure llama.cpp is cloned or available in the repo tree
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/../llama.cpp/CMakeLists.txt")
    add_subdirectory("../llama.cpp" "${CMAKE_CURRENT_BINARY_DIR}/llama")
endif()

add_library(
    agent_jni
    SHARED
    llama_jni.cpp
)

find_library(log-lib log)

include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/../llama.cpp/include)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/../llama.cpp/ggml/include)

target_link_libraries(
    agent_jni
    llama
    ggml
    ggml-vulkan
    ${log-lib}
)
