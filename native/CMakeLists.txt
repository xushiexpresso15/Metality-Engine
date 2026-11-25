cmake_minimum_required(VERSION 3.25)

project(metality_native
    VERSION 0.1.0
    DESCRIPTION "Metality Native Metal 3/4 Rendering, Terrain & Aperture Shader Core for Apple Silicon"
    LANGUAGES C CXX OBJCXX
)

if(NOT APPLE)
    message(FATAL_ERROR "Metality Native Core strictly targets macOS on Apple Silicon (arm64).")
endif()

set(CMAKE_OSX_ARCHITECTURES "arm64" CACHE STRING "Target Apple Silicon arm64 only" FORCE)
set(CMAKE_OSX_DEPLOYMENT_TARGET "15.0" CACHE STRING "Minimum macOS deployment target" FORCE)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_OBJCXX_STANDARD 20)
set(CMAKE_OBJCXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

find_library(METAL_FRAMEWORK Metal REQUIRED)
find_library(METALFX_FRAMEWORK MetalFX REQUIRED)
find_library(QUARTZCORE_FRAMEWORK QuartzCore REQUIRED)
find_library(COREGRAPHICS_FRAMEWORK CoreGraphics REQUIRED)
find_library(APPKIT_FRAMEWORK AppKit REQUIRED)
find_library(FOUNDATION_FRAMEWORK Foundation REQUIRED)
find_library(JAVASCRIPTCORE_FRAMEWORK JavaScriptCore REQUIRED)

set(METALITY_SOURCES
    src/metal_context.mm
    src/frame_pacing.mm
    src/shader_cache.mm
    src/texture_pipeline.mm
    src/render_graph.mm
    src/chunk_arena.cpp
    src/chunk_mesher.cpp
    src/gpu_terrain_culler.mm
    src/metalfx_upscaler.mm
    src/slang_msl_bridge.mm
    src/aperture_pack_loader.mm
    src/iris_legacy_bridge.mm
    src/shader_composer.mm
    src/raytracing_scene.mm
    src/metality_c_api.mm
)

add_library(metality_native SHARED ${METALITY_SOURCES})

target_include_directories(metality_native
    PUBLIC
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/src
)

target_compile_options(metality_native PRIVATE
    $<$<COMPILE_LANGUAGE:OBJCXX>:-fobjc-arc>
    -Wall
    -Wextra
    -Wpedantic
    -Wno-gnu-anonymous-struct
    -Wno-nested-anon-types
    -mcpu=apple-m1
)

target_link_libraries(metality_native PUBLIC
    ${METAL_FRAMEWORK}
    ${METALFX_FRAMEWORK}
    ${QUARTZCORE_FRAMEWORK}
    ${COREGRAPHICS_FRAMEWORK}
    ${APPKIT_FRAMEWORK}
    ${FOUNDATION_FRAMEWORK}
    ${JAVASCRIPTCORE_FRAMEWORK}
)

enable_testing()

add_executable(metality_smoke_test tests/smoke_test.cpp)
target_link_libraries(metality_smoke_test PRIVATE metality_native)
target_compile_options(metality_smoke_test PRIVATE -Wall -Wextra)
add_test(NAME MetalityHardwareSmokeTest COMMAND metality_smoke_test)

add_executable(metality_terrain_benchmark tests/terrain_benchmark.cpp)
target_link_libraries(metality_terrain_benchmark PRIVATE metality_native)
target_compile_options(metality_terrain_benchmark PRIVATE -Wall -Wextra)
add_test(NAME MetalityPhase1TerrainBenchmark COMMAND metality_terrain_benchmark)

add_executable(metality_phase2_test tests/phase2_shader_pack_test.cpp)
target_link_libraries(metality_phase2_test PRIVATE metality_native)
target_compile_definitions(metality_phase2_test PRIVATE
    METALITY_EXAMPLE_PACK_DIR="${CMAKE_CURRENT_SOURCE_DIR}/../sdk/examples/aurora-tbdr"
)
target_compile_options(metality_phase2_test PRIVATE -Wall -Wextra)
add_test(NAME MetalityPhase2ShaderPackTest COMMAND metality_phase2_test)

add_executable(metality_phase3_test tests/phase3_raytracing_test.cpp)
target_link_libraries(metality_phase3_test PRIVATE metality_native)
target_compile_definitions(metality_phase3_test PRIVATE
    METALITY_PATHTRACED_PACK_DIR="${CMAKE_CURRENT_SOURCE_DIR}/../sdk/examples/aurora-pathtraced"
)
target_compile_options(metality_phase3_test PRIVATE -Wall -Wextra)
add_test(NAME MetalityPhase3RayTracingTest COMMAND metality_phase3_test)

add_executable(metality_phase4_test tests/phase4_legacy_iris_test.cpp)
target_link_libraries(metality_phase4_test PRIVATE metality_native)
target_compile_options(metality_phase4_test PRIVATE -Wall -Wextra)
add_test(NAME MetalityPhase4LegacyIrisTest COMMAND metality_phase4_test)

set(METALITY_METEOR_PACKS_ROOT "")
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/../shaderpacks/meteor-dusk/pack.ts")
    set(METALITY_METEOR_PACKS_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../shaderpacks")
elseif(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/../../Meteor-shader/meteor-dusk/pack.ts")
    set(METALITY_METEOR_PACKS_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../Meteor-shader")
endif()

if(NOT "${METALITY_METEOR_PACKS_ROOT}" STREQUAL "")
    add_executable(metality_phase5_test tests/phase5_meteor_pack_test.cpp)
    target_link_libraries(metality_phase5_test PRIVATE metality_native)
    target_compile_definitions(metality_phase5_test PRIVATE
        METALITY_METEOR_ZENITH_DIR="${METALITY_METEOR_PACKS_ROOT}/meteor-zenith"
        METALITY_METEOR_DUSK_DIR="${METALITY_METEOR_PACKS_ROOT}/meteor-dusk"
    )
    target_compile_options(metality_phase5_test PRIVATE -Wall -Wextra)
    add_test(NAME MetalityPhase5MeteorPackTest COMMAND metality_phase5_test)

    add_executable(metality_engine_shader_benchmark tests/engine_shader_benchmark.cpp)
    target_link_libraries(metality_engine_shader_benchmark PRIVATE metality_native)
    target_compile_definitions(metality_engine_shader_benchmark PRIVATE
        METALITY_METEOR_ZENITH_DIR="${METALITY_METEOR_PACKS_ROOT}/meteor-zenith"
        METALITY_METEOR_DUSK_DIR="${METALITY_METEOR_PACKS_ROOT}/meteor-dusk"
    )
    target_compile_options(metality_engine_shader_benchmark PRIVATE -Wall -Wextra)
    add_test(NAME MetalityEngineAndShaderBenchmark COMMAND metality_engine_shader_benchmark)
endif()
