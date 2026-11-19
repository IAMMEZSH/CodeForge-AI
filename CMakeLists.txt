cmake_minimum_required(VERSION 3.16)
project(CodeForgeAI VERSION 1.0.8 LANGUAGES C CXX)

# The Java server and the Win32 client both resolve native binaries under
# native/bin, so every build route (CMake and bootstrap.py) must agree.
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_SOURCE_DIR}/native/bin")

set(CMAKE_C_STANDARD 17)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
endif()

add_library(cforge_sha256 STATIC native-c/sha256.c)
target_include_directories(cforge_sha256 PUBLIC native-c)

add_executable(cforge_native native-c/native_tool.c)
target_link_libraries(cforge_native PRIVATE cforge_sha256)
if(WIN32)
    target_compile_definitions(cforge_native PRIVATE WIN32_LEAN_AND_MEAN)
    target_link_libraries(cforge_native PRIVATE shell32)
endif()

set(CORE_SOURCES
    core/main.cpp
    core/config.cpp
    core/util.cpp
    core/process.cpp
    core/http.cpp
    core/llm.cpp
    core/runners.cpp
    core/debugger.cpp)

add_executable(CodeForgeCore ${CORE_SOURCES})
target_include_directories(CodeForgeCore PRIVATE core)
if(WIN32)
    target_compile_definitions(CodeForgeCore PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX)
    target_link_libraries(CodeForgeCore PRIVATE winhttp shell32)
else()
    find_package(CURL REQUIRED)
    find_package(Threads REQUIRED)
    target_link_libraries(CodeForgeCore PRIVATE CURL::libcurl Threads::Threads)
endif()

if(WIN32)
    add_executable(CodeForge WIN32
        client/main.cpp
        client/app.rc
        core/http.cpp
        core/util.cpp)
    target_include_directories(CodeForge PRIVATE core)
    target_compile_definitions(CodeForge PRIVATE UNICODE _UNICODE WIN32_LEAN_AND_MEAN NOMINMAX)
    target_link_libraries(CodeForge PRIVATE
        winhttp shell32 gdi32 user32 comctl32 dwmapi ole32)
    if(MINGW)
        target_link_options(CodeForge PRIVATE -municode)
    endif()
endif()
