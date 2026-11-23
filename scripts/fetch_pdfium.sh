#!/bin/bash
# Downloads the prebuilt PDFium (bblanchon/pdfium-binaries, MIT; non-V8 builds: no JavaScript inside PDFs) into
# vendor/pdfium/<platform>/ and checks each archive against scripts/pdfium.sha256 (written on the first fetch).
set -e
cd "$(dirname "$0")/.."
TAG=chromium/8179
SUMS=scripts/pdfium.sha256
mkdir -p vendor/pdfium
for P in win-x64 android-arm64 android-arm android-x64; do
  F=pdfium-$P.tgz
  [ -f vendor/pdfium/$F ] || curl -sSL --max-time 300 -o vendor/pdfium/$F "https://github.com/bblanchon/pdfium-binaries/releases/download/$TAG/$F"
  H=$(sha256sum vendor/pdfium/$F | cut -d' ' -f1)
  if grep -q " $F\$" $SUMS 2>/dev/null; then
    grep -q "^$H  $F\$" $SUMS || { echo "CHECKSUM MISMATCH $F"; exit 1; }
  else
    echo "$H  $F" >> $SUMS
  fi
  mkdir -p vendor/pdfium/$P && tar -xzf vendor/pdfium/$F -C vendor/pdfium/$P
done
echo "pdfium $TAG ok"
