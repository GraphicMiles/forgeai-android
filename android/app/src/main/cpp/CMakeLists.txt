cmake_minimum_required(VERSION 3.22)
project(ondevice_runtime)

set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# Link the CPU backend into the app; dynamic backend loading is unreliable on Android.
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
# Avoid compiling every optional ARM CPU variant on Android; this reduces NDK compiler pressure.
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)

add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/llama.cpp ${CMAKE_CURRENT_BINARY_DIR}/llama)

# NDK ARM quantization compilation can exhaust or crash older Clang frontends.
# Keep the Android CPU path conservative; correctness is more important than peak SIMD speed.
if(ANDROID AND TARGET ggml-cpu)
    target_compile_options(ggml-cpu PRIVATE -O1 -fno-vectorize -fno-slp-vectorize)
endif()
add_library(ondevice_runtime SHARED OnDeviceRuntime.cpp)
target_include_directories(ondevice_runtime PRIVATE ${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/llama.cpp/include)
target_link_libraries(ondevice_runtime llama ggml android log)
