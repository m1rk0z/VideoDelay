# VideoDelay - Project Reference & Guidelines

Developer guide and codebase reference for the **VideoDelay** Android application.

## 📦 Build & Run Commands
- **Compile Kotlin**: `.\gradlew compileDebugKotlin`
- **Build Debug APK**: `.\gradlew assembleDebug`
- **Build Release APK**: `.\gradlew assembleRelease`
- **Clean Project**: `.\gradlew clean`

---

## 🏛️ Architecture & Component Map
- **`MainActivity`** ([MainActivity.kt](file:///c:/Users/mcominardi/AndroidStudioProjects/VideoDelay/app/src/main/java/it/videodelay/app/ui/main/MainActivity.kt)): Handles portrait screen navigation and starts the main camera list.
- **`PlayerFragment`** ([PlayerFragment.kt](file:///c:/Users/mcominardi/AndroidStudioProjects/VideoDelay/app/src/main/java/it/videodelay/app/ui/player/PlayerFragment.kt)): Landscape ExoPlayer/Media3 interface containing the buffer timeline, delay presets, collapsing statistics panels, and clean screenshot extraction logic.
- **`StreamingForegroundService`** ([StreamingForegroundService.kt](file:///c:/Users/mcominardi/AndroidStudioProjects/VideoDelay/app/src/main/java/it/videodelay/app/service/StreamingForegroundService.kt)): Background service that buffers RTSP video streams to enable time-shifting.
- **`ScreenshotEditorActivity` & `DrawingView`** ([ScreenshotEditorActivity.kt](file:///c:/Users/mcominardi/AndroidStudioProjects/VideoDelay/app/src/main/java/it/videodelay/app/ui/player/ScreenshotEditorActivity.kt)): Screenshot markup canvas activity.
- **`ScreenshotGalleryActivity`** ([ScreenshotGalleryActivity.kt](file:///c:/Users/mcominardi/AndroidStudioProjects/VideoDelay/app/src/main/java/it/videodelay/app/ui/player/ScreenshotGalleryActivity.kt)): Manages captured screenshots, offering multi-selection mode, sharing (`ACTION_SEND_MULTIPLE`), and deletion using `MediaStore.createDeleteRequest` on Android 11+ (API 30+).
- **`CustomScannerActivity`** ([CustomScannerActivity.kt](file:///c:/Users/mcominardi/AndroidStudioProjects/VideoDelay/app/src/main/java/it/videodelay/app/ui/cameras/CustomScannerActivity.kt)): Custom scanner UI overlay wrapping the ZXing scanner code.

---

## 🎨 Design System & Styling Rules: **Cyber Teal**
We follow a high-contrast dark utility theme optimized for outdoor sports and coaches:
- **Backgrounds**: Deep Navy Blue (`#0F172A`, `@color/bg_primary`)
- **Card Surfaces**: Deep Blue Tech (`#192134`, `@color/bg_surface`) with sharp borders (`#243044`)
- **Primary Accent**: Cyan (`#06B6D4`, `@color/colorPrimary`).
  - *Accessibility Rule*: Text/Icons on primary cyan backgrounds **must** be set to black (`@android:color/black`) to guarantee readable WCAG contrast.
- **Secondary Accent**: Teal (`#0EA5E9`, `@color/colorSecondary`) used for timelines and seekbars.
- **Highlight / Marks**: Warm Orange (`#F97316`, `@color/mark_yellow`).
- **StatusBar & Safe Areas**: Always set `android:fitsSystemWindows="true"` on root views to prevent overlapping with status bars, battery/clock icons, and screen notches.

---

## 🔧 MediaStore Deletion Guidelines
On Android 11+ (API 30+), direct file deletions using `ContentResolver.delete()` on items created by previous app installations will fail due to Scoped Storage security rules.
- **Rule**: Always wrap deletions inside `MediaStore.createDeleteRequest(contentResolver, uris)` for devices running API 30+ (R), and start it using `startIntentSenderForResult`. Use fallback `contentResolver.delete` only on API 29 and lower.
