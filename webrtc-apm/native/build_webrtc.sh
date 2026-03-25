#!/usr/bin/env bash
#
# Build libwebrtc_apms.so from a WebRTC source checkout.
#
# Prerequisites:
#   1. depot_tools on PATH (provides gn, ninja)
#   2. WebRTC Android source fetched:
#        mkdir webrtc-checkout && cd webrtc-checkout
#        fetch --nohooks webrtc_android
#        gclient sync
#   3. Set WEBRTC_SRC to point to the "src" directory inside the checkout.
#
# Usage:
#   WEBRTC_SRC=/path/to/webrtc-checkout/src ./build_webrtc.sh
#
# The script will:
#   - Copy apm_jni.cpp, BUILD.gn, and jni_exports.lds into $WEBRTC_SRC/apm_jni/
#   - Build for arm64-v8a, armeabi-v7a, and x86_64
#   - Strip and copy the resulting .so files into src/main/libs/<abi>/
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(dirname "$SCRIPT_DIR")"
LIBS_DIR="$MODULE_DIR/src/main/libs"

# ── Validate environment ────────────────────────────────────────────────────

if [[ -z "${WEBRTC_SRC:-}" ]]; then
    echo "ERROR: Set WEBRTC_SRC to the WebRTC src/ directory."
    echo "  Example: WEBRTC_SRC=~/webrtc-checkout/src $0"
    exit 1
fi

if [[ ! -f "$WEBRTC_SRC/modules/audio_processing/include/audio_processing.h" ]]; then
    echo "ERROR: Cannot find audio_processing.h in \$WEBRTC_SRC."
    echo "  WEBRTC_SRC=$WEBRTC_SRC"
    echo "  Did you run 'fetch --nohooks webrtc_android && gclient sync'?"
    exit 1
fi

if ! command -v gn &>/dev/null; then
    echo "ERROR: 'gn' not found. Add depot_tools to PATH."
    exit 1
fi

if ! command -v ninja &>/dev/null; then
    echo "ERROR: 'ninja' not found. Add depot_tools to PATH."
    exit 1
fi

# ── Copy JNI source into WebRTC tree ────────────────────────────────────────

JNI_DIR="$WEBRTC_SRC/apm_jni"
mkdir -p "$JNI_DIR"
cp "$SCRIPT_DIR/apm_jni.cpp"      "$JNI_DIR/"
cp "$SCRIPT_DIR/BUILD.gn"         "$JNI_DIR/"
cp "$SCRIPT_DIR/jni_exports.lds"  "$JNI_DIR/"

echo "Copied JNI sources to $JNI_DIR"

# ── Common GN args ──────────────────────────────────────────────────────────

COMMON_ARGS='
  target_os="android"
  is_debug=false
  is_component_build=false
  rtc_include_tests=false
  treat_warnings_as_errors=false
  rtc_build_examples=false
  rtc_build_tools=false
'

# ── Build function ──────────────────────────────────────────────────────────

build_abi() {
    local cpu="$1"
    local abi="$2"
    local out_dir="$WEBRTC_SRC/out/apm-$cpu"

    echo ""
    echo "═══════════════════════════════════════════════════════════════"
    echo "  Building for $abi (target_cpu=$cpu)"
    echo "═══════════════════════════════════════════════════════════════"

    gn gen "$out_dir" --args="$COMMON_ARGS target_cpu=\"$cpu\""
    ninja -C "$out_dir" apm_jni:webrtc_apms

    # Find the built .so
    local so_file="$out_dir/libwebrtc_apms.so"
    if [[ ! -f "$so_file" ]]; then
        # Some builds put it in a subdirectory
        so_file=$(find "$out_dir" -name "libwebrtc_apms.so" -type f | head -1)
    fi

    if [[ -z "$so_file" || ! -f "$so_file" ]]; then
        echo "ERROR: libwebrtc_apms.so not found in $out_dir"
        exit 1
    fi

    # Strip debug symbols to reduce size
    local strip_tool
    strip_tool="$(find "$WEBRTC_SRC/third_party/android_toolchain/ndk/toolchains/llvm/prebuilt" \
        -name "llvm-strip" -type f 2>/dev/null | head -1)"
    if [[ -n "$strip_tool" && -x "$strip_tool" ]]; then
        echo "Stripping $so_file"
        "$strip_tool" "$so_file"
    else
        echo "WARNING: llvm-strip not found, skipping strip"
    fi

    # Copy to module libs
    mkdir -p "$LIBS_DIR/$abi"
    cp "$so_file" "$LIBS_DIR/$abi/libwebrtc_apms.so"

    local size
    size=$(du -h "$LIBS_DIR/$abi/libwebrtc_apms.so" | cut -f1)
    echo "Installed $LIBS_DIR/$abi/libwebrtc_apms.so ($size)"
}

# ── Build all ABIs ──────────────────────────────────────────────────────────

build_abi "arm64" "arm64-v8a"
build_abi "arm"   "armeabi-v7a"
build_abi "x64"   "x86_64"

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  Build complete! Libraries installed to:"
echo "    $LIBS_DIR/arm64-v8a/libwebrtc_apms.so"
echo "    $LIBS_DIR/armeabi-v7a/libwebrtc_apms.so"
echo "    $LIBS_DIR/x86_64/libwebrtc_apms.so"
echo ""
echo "  Verify with:"
echo "    llvm-readelf --dyn-syms $LIBS_DIR/arm64-v8a/libwebrtc_apms.so | grep -i 'echo\|rnn\|audio_processing'"
echo "═══════════════════════════════════════════════════════════════"
