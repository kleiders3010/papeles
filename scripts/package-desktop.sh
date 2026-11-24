#!/usr/bin/env bash
# Package the Windows desktop app: the release Rust bridge and PDFium go into app/desktop/build/native/windows (the
# app's resources folder), then Compose builds the app with its own runtime and the MSI. Before the MSI, the packaged
# app runs once with --check (no window): the bridge and PDFium must load from where the installer puts them.
# Output: app/desktop/build/compose/binaries/main/msi/Papeles-<version>.msi
# Needs scripts/fetch_pdfium.sh first.
set -euo pipefail
cd "$(dirname "$0")/.."
cargo build -q --release -p pp-ffi
out=app/desktop/build/native/windows
rm -rf "$out"
mkdir -p "$out"
cp target/release/pp_ffi.dll vendor/pdfium/win-x64/bin/pdfium.dll "$out/"
(cd app && ./gradlew -q --no-daemon :desktop:createDistributable)
exe=app/desktop/build/compose/binaries/main/app/Papeles/Papeles.exe
"$exe" --check || { echo "the packaged app did not start its engine"; exit 1; }
echo "packaged app check: ok"
(cd app && ./gradlew -q --no-daemon :desktop:packageMsi)
ls app/desktop/build/compose/binaries/main/msi/
