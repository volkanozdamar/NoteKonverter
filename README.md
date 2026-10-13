<p align="center">
  <img src="docs/icon.png" width="120" alt="NoteKonverter icon">
</p>

<h1 align="center">NoteKonverter</h1>

<p align="center">
  Convert handwritten notes between <b>Samsung Notes</b>, <b>Boox Notes</b> and <b>Saber</b> on Android.<br>
  <b>English</b> · <a href="README.tr.md">Türkçe</a>
</p>

---

Each note-taking app keeps handwriting in its own closed file format, so notes are hard to move between devices. NoteKonverter reads the handwriting from one format and writes it into another. Everything runs on the device, and the app needs no internet permission.

## Supported formats

| App | File | Import | Export |
|---|---|:---:|:---:|
| Samsung Notes | `.sdocx` | ✅ | ✅ |
| Onyx Boox Notes | `.note` | ✅ | ✅ |
| [Saber](https://github.com/saber-notes/saber) | `.sba` (also bare `.sbn2` on import) | ✅ | ✅ |

When you add a file, the app detects its format and offers the **other two** as targets. For example, a `.sdocx` file can become a `.note` or a `.sba` file, or both.

### What is converted

- Pen strokes with their points, pressure, colour and width
- Highlighter strokes, with transparency adjusted to each app
- All pages. Page sizes are scaled to each app's page size. Long scrolling Samsung pages are cut into Boox sheets.
- The note title, where the format stores one

### What is not converted

- Typed text, images, PDF backgrounds and page templates
- Eraser history and the layers and tags specific to each app
- Notes in Saber's old `.sbn` format. Open them in Saber and export them again.

## Features

- **Choose the target for each file**, or use **Convert all** to convert every added file to the same format at once.
- **Share to the app or use "Open with"**: files sent from Samsung Notes, Boox, Saber or a file manager are added straight to the list.
- **E-ink mode**: pure black and white, no animations or ripple effects, bolder text and larger touch targets. It turns on automatically on Onyx Boox, Bigme, PocketBook, Meebook and other e-paper devices.
- **Phones and large tablets**: phones use a single column. Tablets 840 dp and wider show a side panel and a grid with several cards per row.
- **English and Turkish**: the app follows the device language. Turkish devices get Turkish, and all other devices get English. You can also pick the language inside the app, or in Android 13+ per-app language settings.
- Converted files go to a folder you choose, and each one can be shared right away.

## Installation

There are no store releases yet. Build the app from source:

```bash
git clone https://github.com/volkanozdamar/NoteKonverter.git
cd NoteKonverter
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. Every push to `main` also builds an APK, which you can download from the **Actions** tab.

Requirements: Android 7.0 (API 24) or newer. To build, use Android Studio or the Android SDK. Gradle downloads the required JDK automatically.

## Usage

1. Tap **Add files** and pick one or more `.sdocx`, `.note` or `.sba` files.
2. Each file appears as a card with its detected format, page count and stroke count.
3. Tap the format you want under **Convert to**.
4. The first time, choose an output folder. The converted file is saved there, and you can share it from the card.
5. Import the file in the target app:
   - **Boox:** Notes → Import
   - **Saber:** + → Import
   - **Samsung Notes:** open the file from a file manager

> ⚠️ **Experimental.** None of these formats is publicly documented. The converters are built from community reverse-engineering work and checked with independent parsers, but app updates can change the formats. Always keep your original files.

## How it works

```
app/src/main/java/com/example/notekonverter/
├── converter/            Pure Kotlin, no Android dependencies, unit-tested on the JVM
│   ├── Ink.kt            Format-neutral model: document → pages → strokes → points
│   ├── NoteConverter.kt  Format detection, page layout per target, conversion
│   ├── samsung/          .sdocx reader/writer (ZIP + binary pages, SHA-256 integrity chain)
│   ├── boox/             .note reader/writer (ZIP + protobuf metadata + binary point files)
│   ├── saber/            .sba reader/writer (ZIP + BSON note, format version 19)
│   └── io/               Small ZIP, protobuf, BSON, JSON and byte helpers
├── ConverterViewModel.kt File loading, conversion jobs, settings
├── MainActivity.kt       Compose UI (adaptive layout, e-ink mode, language picker)
└── AppLanguage.kt        In-app language override
```

Each reader turns a file into the shared `InkDocument` model. `NoteConverter` then fits the pages to the target's page geometry, and the target's writer produces the file:

| Format | Page width |
|---|---|
| Samsung | 1440 |
| Boox | 1860 × 2480 |
| Saber | 1000 |

The converter has no runtime dependencies beyond the Kotlin standard library.

Run the tests with:

```bash
./gradlew :app:testDebugUnitTest
```

The tests build notes in code and run them through every reader and writer, so the repository contains no third-party or personal sample files.

## Contributing

Issues and pull requests are welcome. Sample files that fail to convert help the most, but please share only notes you are allowed to share. If the target app shows an error on import, include the exact error message.

## Acknowledgements

The file formats were understood with the help of these projects. Their documentation was read; no code was copied.

- [twangodev/sdocx](https://github.com/twangodev/sdocx): Samsung `.sdocx` format notes
- [bxff/samsung-notes-format](https://github.com/bxff/samsung-notes-format): Samsung sample files
- [nrontsis/boox-note-optimizer](https://github.com/nrontsis/boox-note-optimizer) and [bomberstudios/boox-notes-sync](https://github.com/bomberstudios/boox-notes-sync): Boox `.note` structure
- [saber-notes/saber](https://github.com/saber-notes/saber): Saber `.sba` / `.sbn2` structure

## Disclaimer

NoteKonverter is an independent project. It is not affiliated with or endorsed by Samsung, Onyx International (Boox) or the Saber project. Product names are trademarks of their respective owners.

## License

[MIT](LICENSE) © 2026 Volkan Özdamar
