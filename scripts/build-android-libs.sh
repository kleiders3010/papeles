#!/bin/bash
# Build the Rust bridge for the Android app (libpp_ffi.so) for real devices (arm64-v8a) and the emulator (x86_64),
# put PDFium next to it (scripts/fetch_pdfium.sh first), then regenerate the Kotlin bindings.
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_NDK_HOME="$(cygpath -w ~/AppData/Local/Android/Sdk/ndk/29.0.14206865 2>/dev/null || echo ~/Android/Sdk/ndk/29.0.14206865)"
OUT=app/android/src/main/jniLibs
cargo ndk -t arm64-v8a -t x86_64 -P 26 -o "$OUT" build --release -p pp-ffi
cp vendor/pdfium/android-arm64/lib/libpdfium.so "$OUT/arm64-v8a/"
cp vendor/pdfium/android-x64/lib/libpdfium.so "$OUT/x86_64/"
cargo build -p pp-ffi
cargo run -q -p pp-ffi --features bindgen --bin uniffi-bindgen -- generate --library target/debug/pp_ffi.dll --language kotlin --out-dir target/uniffi-kotlin --no-format
cp target/uniffi-kotlin/com/jurisgames/papeles/core/pp_ffi.kt app/shared/src/com/jurisgames/papeles/core/pp_ffi.kt
ls -la "$OUT"/*/
