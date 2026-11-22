#!/usr/bin/env bash
# Builds llama-server for arm64 Android and drops it into the app as libllama_server.so.
# Needs: Android NDK (ANDROID_NDK), cmake, ninja, and a llama.cpp checkout (LLAMA_SRC).
set -euo pipefail
: "${ANDROID_NDK:?set ANDROID_NDK to your NDK path}"
: "${LLAMA_SRC:?set LLAMA_SRC to a llama.cpp source checkout}"
OUT="${OUT:-/tmp/llama-android}"
cmake -S "$LLAMA_SRC" -B "$OUT" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
  -DGGML_NATIVE=OFF -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 -DGGML_OPENMP=OFF \
  -DLLAMA_OPENSSL=OFF -DLLAMA_BUILD_UI=OFF -DLLAMA_USE_PREBUILT_UI=OFF \
  -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_APP=OFF -DLLAMA_BUILD_SERVER=ON
ninja -C "$OUT" llama-server
DEST="$(dirname "$0")/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$DEST"
"$ANDROID_NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip -o "$DEST/libllama_server.so" "$OUT/bin/llama-server"
ls -la "$DEST"
