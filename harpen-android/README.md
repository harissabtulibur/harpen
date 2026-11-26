# HARPEN — Native Android Digital Notebook & Creative Studio

**Tagline:** *"Your thoughts. Your strokes. Your world."*  
**Package:** `com.harpen.studio`  
**Minimum SDK:** Android 8.0 (API 26) · **Target SDK:** Android 15 (API 35)

HARPEN is an offline-first native Android tablet application engineered in **Kotlin** and **Jetpack Compose**, paired with a custom hardware-accelerated **`android.view.View` drawing & `MotionEvent` input engine** optimized for 4 GB RAM tablets (including Samsung Galaxy Tab devices) and universal capacitive styluses.

---

## Architecture Overview

| Module / Package | Responsibility |
| :--- | :--- |
| `com.harpen.studio.model` | Serializable vector strokes, 15 pen/brush tools, layers, text objects, images, page templates, PalmGuard modes, Touch Debugger telemetry |
| `com.harpen.studio.data.local` | Room database (`NoteEntity`, `FolderEntity`, `ColorPaletteEntity`) for Recent, Notebooks, Favorites, PDFs, Images, Trash, Search |
| `com.harpen.studio.data.settings` | DataStore Preferences backing all 13 centralized Settings categories with real runtime binding and per-category/full reset |
| `com.harpen.studio.data.format` | Native `.harpen` versioned ZIP+JSON archive container (`HarpenArchiveManager`) with Save, Save As, Autosave, Recovery, and optional AES-GCM encryption |
| `com.harpen.studio.data.storage` | Android Storage Access Framework (`SafStorageManager`) supporting internal storage, SD cards, external SSD/USB where permitted |
| `com.harpen.studio.input` | **HARPEN PalmGuard** (Modes A–E, safe recovery, never permanently locks) + **Touch Debugger** bounded ring buffer & CSV/JSON exporter |
| `com.harpen.studio.canvas` | Custom `HarpenCanvasView` (`MotionEvent` batching, pointer lifecycle, Catmull-Rom smoothing, 15 tools, layers, lasso/rect selection, ruler, snap-to-grid) |
| `com.harpen.studio.ocr` | CameraX capture/import + offline ML Kit Latin OCR + offline Indonesian/English cleanup & spell-correction review (`IndonesianEnglishCorrector`) + Digital Ink handwriting conversion |
| `com.harpen.studio.pdf` | Native `PdfRenderer` PDF import/annotation + crisp `PdfDocument` multi-page vector/raster export + Android Sharesheet |
| `com.harpen.studio.ui` | Adaptive portrait/landscape tablet UI, collapsible sidebar & toolbar, Distraction-Free Mode, Color Wheel/Eyedropper, 13-category Settings, Touch Debugger |

---

## Building an APK in Android Studio

> **Honest Build Note:** This project was generated inside an environment without the Android SDK/JDK/Gradle toolchain installed, so an APK has **not** been compiled or tested on-device yet. Follow the steps below on your workstation to compile and run HARPEN.

### Option 1 — Android Studio (Recommended)
1. Install **Android Studio Ladybug (2024.2.1)** or newer with **Android SDK Platform 35** and **JDK 17**.
2. Open the `harpen-android/` folder via **File → Open**.
3. Let Gradle sync (`gradle/libs.versions.toml` resolves all dependencies automatically).
4. If the Gradle Wrapper JAR is not yet present in `gradle/wrapper/`, Android Studio generates it automatically during sync, or run `gradle wrapper` locally.
5. Connect your Android tablet via USB Debugging (or launch an Android Tablet emulator).
6. Choose **Build → Build Bundle(s) / APK(s) → Build APK(s)** to produce `app/build/outputs/apk/debug/app-debug.apk`.

### Option 2 — Command Line (Gradle)
```bash
cd harpen-android
# Generate wrapper if needed (first time only, requires local Gradle 8.10+)
gradle wrapper --gradle-version 8.10.2

# Compile Debug APK
./gradlew assembleDebug

# Run Unit Tests
./gradlew testDebugUnitTest

# Compile Release APK (configure signingConfig in app/build.gradle.kts first)
./gradlew assembleRelease
```

The debug APK will be written to:
`app/build/outputs/apk/debug/app-debug.apk`

---

## Key Design & Hardware Notes

- **Universal Capacitive Stylus Realism:** Android often reports passive capacitive styluses as `TOOL_TYPE_FINGER` with constant `pressure = 1.0f`. HARPEN never fakes pressure curves when hardware pressure is absent, never hardcodes `Y=200`, never assumes pointer 0 is the stylus, and never permanently locks input after multi-touch.
- **Offline OCR & Grammar Honesty:** Offline Latin OCR (`com.google.mlkit:text-recognition`) runs 100% locally with zero internet. Indonesian/English cleanup & dictionary spelling correction run offline with diff review and protected-token preservation (numbers, dates, formulas, abbreviations, technical acronyms). Full offline neural grammar parsing is explicitly separated from basic cleanup/punctuation rules when no external grammar model is bundled.
- **4 GB RAM Tablet Optimization:** Bounded Touch Debugger ring buffer, pre-allocated `Path`/`Paint` objects on the draw thread, low-memory bitmap downsampling toggle, and background coroutine dispatchers for autosave, OCR, PDF export, and `.harpen` archiving.
