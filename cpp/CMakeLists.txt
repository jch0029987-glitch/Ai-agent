cmake_minimum_required(VERSION 3.22.1)
project("agent_jni")

add_library(
    agent_jni
    SHARED
    llama_jni.cpp
)

find_library(log-lib log)

# Use the headers we pulled into the workflow/repo
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include)

target_link_libraries(
    agent_jni
    ${log-lib}
)
