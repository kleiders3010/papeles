# Papeles: PDF tools on your own device, free

A free, open-source PDF app for Android and Windows. Every feature is free: no account, no ads, no subscription, no
watermark. Your files never leave your device: the app has no servers and doesn't upload anything.

- **Read:** open any PDF, scroll its pages, zoom, jump with the strip of small pages, search its text (every hit
  marked on the page), open locked PDFs with their password. Files you open are kept on a recent list.
- **Fill in forms:** text boxes, checkboxes, choices; a filled copy is saved (a digitally signed PDF says what filling
  does to its signature first).
- **Sign:** draw your signature once (it stays on the device) and tap it onto the pages. It is a picture of your
  signature, as on paper, not a digital signature.
- **Mark up:** type text, highlight, write with a pen. The marks are standard PDF notes other readers show too.
- **Scan:** photograph each page (or choose photos); the paper's corners are found, the page is straightened and
  cleaned (document, grey or colour), then saved as a PDF.
- **Edit pages:** turn, move and delete pages, then save a new PDF (the original is never changed).
- **Join PDFs:** put several PDFs together in the order you choose.
- **Pictures to PDF:** one page per picture (JPEG or PNG), on A4, Letter or each picture's own size.
- **Make smaller:** shrink the pictures inside a PDF for screens, for printing at home, or just a little. A picture
  is only replaced when the new one is smaller.
- English and Spanish.


## How it works

The PDF work runs in Rust on the device: [lopdf](https://github.com/J-F-Liu/lopdf) for page work and making PDFs,
[PDFium](https://pdfium.googlesource.com/pdfium/) (the PDF engine in Chrome, from
[pdfium-binaries](https://github.com/bblanchon/pdfium-binaries)) for drawing pages and search. The app is Compose
(Android and desktop) and talks to Rust through [UniFFI](https://github.com/mozilla/uniffi-rs). Every file opened may
be hostile: loads are bounded (pages, stream sizes, picture sizes) and errors are messages, never crashes.

## Build

```
scripts/fetch_pdfium.sh           # PDFium for Windows and Android, checked against scripts/pdfium.sha256
cargo test                        # the Rust core
cargo build -p pp-ffi             # the bridge for the desktop app
cd app && ./gradlew :desktop:run  # the desktop app
scripts/build-android-libs.sh     # the bridge and PDFium for Android, then ./gradlew :android:assembleDebug
scripts/package-desktop.sh        # the Windows installer (MSI), after checking the packaged app starts
```

## Problems or ideas

In the app: ⋮ › Send feedback. If the app doesn't open at all, open an issue at
https://github.com/kleiders3010/papeles/issues or email hello@jurisgames.com.

## License

Apache-2.0. PDFium is BSD-3-Clause / Apache-2.0 (its notices ship with the app).
