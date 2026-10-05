#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ "$(uname -s)" != Darwin || "$(uname -m)" != arm64 ]]; then
    echo 'Native MetalMC builds require an Apple Silicon Mac.' >&2
    exit 1
fi
METALMC_JDK="${JAVA_HOME:-$(/usr/libexec/java_home)}"
METALMC_NATIVE_DIR="build/generated-resources/natives/macos-arm64"
mkdir -p "$METALMC_NATIVE_DIR"
test -f build/native/headers/dev_metalmc_metal_NativeMetal.h
xcrun clang++ -std=c++17 -fobjc-arc -arch arm64 -mmacosx-version-min=14.0 \
    -Wall -Wextra -Werror -dynamiclib \
    -I "$METALMC_JDK/include" -I "$METALMC_JDK/include/darwin" -I build/native/headers \
    src/main/native/metal_bridge.mm -framework Foundation -framework Metal -framework QuartzCore \
    -install_name @rpath/libmetalmc.dylib \
    -o "$METALMC_NATIVE_DIR/libmetalmc.dylib"
codesign --force --sign - "$METALMC_NATIVE_DIR/libmetalmc.dylib"
