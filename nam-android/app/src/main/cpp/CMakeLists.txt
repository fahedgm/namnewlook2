cmake_minimum_required(VERSION 3.22)
project(namamp_native)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)
FetchContent_Declare(
  NeuralAmpModelerCore
  GIT_REPOSITORY https://github.com/sdatkinson/NeuralAmpModelerCore.git
  GIT_TAG v0.5.3
  GIT_SUBMODULES_RECURSE TRUE
)

# Deliberately using Populate (not MakeAvailable): NeuralAmpModelerCore's own
# CMakeLists.txt only builds ITS OWN test tools (run_tests/loadmodel/
# benchmodel) via add_subdirectory(tools) — confirmed by reading it directly.
# It does not define a reusable library target. If we let CMake execute that
# CMakeLists.txt (what MakeAvailable does), it would try to build those
# desktop test tools during our Android cross-compile, which isn't what we
# want and could break the build for reasons unrelated to our own code.
# Populate just fetches the source without executing it.
FetchContent_GetProperties(NeuralAmpModelerCore)
if(NOT neuralampmodelercore_POPULATED)
  FetchContent_Populate(NeuralAmpModelerCore)
endif()

set(NAM_CORE_DIR "${neuralampmodelercore_SOURCE_DIR}")

file(GLOB_RECURSE NAM_CORE_SOURCES "${NAM_CORE_DIR}/NAM/*.cpp")
if(NOT NAM_CORE_SOURCES)
  message(FATAL_ERROR "No NAM/*.cpp sources found under ${NAM_CORE_DIR} — the fetch may have failed or the layout has changed.")
endif()

add_library(namamp_native SHARED
    native-lib.cpp
    ${NAM_CORE_SOURCES}
)

target_include_directories(namamp_native PRIVATE
    ${NAM_CORE_DIR}
    ${NAM_CORE_DIR}/Dependencies
    ${NAM_CORE_DIR}/Dependencies/eigen
    ${NAM_CORE_DIR}/Dependencies/AudioDSPTools
)

# NAM/activations.h does `#include "json.hpp"` — nlohmann/json's older
# bare-filename style. It's nested somewhere inside AudioDSPTools' own
# dependency tree, but the exact path isn't documented anywhere I could
# verify, so find it dynamically rather than guessing.
file(GLOB_RECURSE NAM_JSON_HEADER "${NAM_CORE_DIR}/json.hpp")
if(NAM_JSON_HEADER)
  list(GET NAM_JSON_HEADER 0 NAM_JSON_HEADER_FIRST)
  get_filename_component(NAM_JSON_INCLUDE_DIR "${NAM_JSON_HEADER_FIRST}" DIRECTORY)
  message(STATUS "Found json.hpp at: ${NAM_JSON_HEADER_FIRST}")
  target_include_directories(namamp_native PRIVATE ${NAM_JSON_INCLUDE_DIR})
else()
  message(FATAL_ERROR "Could not find json.hpp anywhere under ${NAM_CORE_DIR}. "
    "AudioDSPTools' own nested dependency may not have been fetched — "
    "check whether GIT_SUBMODULES_RECURSE actually pulled its submodules.")
endif()

# Matches NeuralAmpModelerCore's own default build option (NAM_ENABLE_A2_FAST
# ON by default in its CMakeLists.txt) — routes A2-shaped models to the
# hand-optimized path instead of the generic one.
target_compile_definitions(namamp_native PRIVATE NAM_ENABLE_A2_FAST)

# This runs real neural-network inference (matrix math via Eigen) inside a
# hard real-time audio deadline (a few milliseconds per callback). The
# Android Gradle Plugin's Debug build variant — which is what
# `gradle assembleDebug` produces, i.e. every APK built by this project's
# CI so far — compiles native code essentially unoptimized (-O0) by
# default. For most native code that's a fine tradeoff for faster builds
# and better debugging; for this specific compute-heavy DSP workload it's
# very likely too slow to keep up with the audio callback's deadline,
# which would explain the glitching/artifact reported after live testing.
# Force real optimization on just this library regardless of the overall
# Gradle build type.
target_compile_options(namamp_native PRIVATE -O2)

# Oboe is resolved via prefab (enabled in app/build.gradle.kts, buildFeatures.prefab),
# which Gradle wires into CMAKE_FIND_ROOT_PATH automatically — no manual path needed.
find_package(oboe REQUIRED CONFIG)

find_library(log-lib log)
target_link_libraries(namamp_native ${log-lib} oboe::oboe)
