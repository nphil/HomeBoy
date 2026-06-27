cmake_minimum_required(VERSION 3.22)
project(llmkit CXX)

# llama.cpp is vendored as a git submodule (see ../../../README.md / docs/llmkit-plan.md §4.2).
# Bail early with a helpful message if it hasn't been checked out yet.
if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/CMakeLists.txt")
    message(FATAL_ERROR
        "llama.cpp submodule is missing. Run:\n"
        "  git submodule update --init --recursive\n"
        "from the repo root (the submodule lives at android/llmkit/src/main/cpp/llama.cpp).")
endif()

# Build only the pieces we need; flags (GGML_OPENCL / GGML_HEXAGON / GGML_OPENMP / LLAMA_*)
# are passed down from android/llmkit/build.gradle.kts via the CMake arguments list.
add_subdirectory(llama.cpp)

add_library(llmkit SHARED llmkit.cpp)

find_library(log-lib log)

target_link_libraries(llmkit
    llama
    ggml
    android
    ${log-lib})
