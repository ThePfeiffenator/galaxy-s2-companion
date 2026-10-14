#!/usr/bin/env bash
set -euo pipefail
export PATH=/usr/bin
cd "$(dirname "$(cygpath -u "$0")")"
task_root=$(pwd)
export ANDROID_NDK_ROOT="$(cygpath -u "$ANDROID_SDK_ROOT")/ndk/28.1.13356709"
export PATH="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/windows-x86_64/bin:/usr/bin"
cd deps/openssl-3.5.9
perl Configure android-arm64 no-shared no-tests no-apps no-docs \
  -U__ANDROID_API__ -D__ANDROID_API__=26 \
  --prefix="$task_root/openssl-arm64" --libdir=lib \
  -ffunction-sections -fdata-sections
make -j4 build_libs
make install_dev
