#!/usr/bin/env sh
# CodeForge AI — portable POSIX build (Linux, macOS, Termux, iSH).
set -eu
cd "$(dirname "$0")"

if ! command -v cmake >/dev/null 2>&1; then
    echo "cmake is required."
    echo "  Debian/Ubuntu: sudo apt install cmake build-essential libcurl4-openssl-dev"
    echo "  macOS:         brew install cmake curl"
    echo "  Termux:        pkg install cmake clang make libcurl"
    echo "  iSH/Alpine:    apk add cmake build-base curl-dev"
    exit 1
fi

cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --parallel "${CODEFORGE_JOBS:-2}"

echo "built:"
echo "  native/bin/CodeForgeCore"
echo "  native/bin/cforge_native"
