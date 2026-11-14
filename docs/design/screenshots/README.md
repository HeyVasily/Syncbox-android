# Screenshots

Screenshots of the demo app on the emulator, one per screen and state, in the light and dark theme. The app runs on in-memory demo data: no Syncthing, no sync, no network. Compare them with the [mockups](../mockups/).

| Screen or state | Light | Dark | Mockup |
|---|---|---|---|
| Files, All files | `files-light.png` | `files-dark.png` | `files.html` |
| Files, On device tab | `files-on-device-light.png` | `files-on-device-dark.png` | `files.html` |
| Files, syncing | `files-syncing-light.png` | `files-syncing-dark.png` | `files.html` |
| File menu, file on device and outdated | `files-menu-light.png` | `files-menu-dark.png` | `files-states.html` |
| Remove confirmation | `files-remove-light.png` | `files-remove-dark.png` | `files-states.html` |
| Offline mode | `files-offline-light.png` | `files-offline-dark.png` | `files-states.html` |
| Settings | `settings-light.png` | `settings-dark.png` | `settings.html` |
| Sync network | `sync-network-light.png` | `sync-network-dark.png` | `settings.html` |
| My device ID | `device-id-light.png` | `device-id-dark.png` | `settings.html` |
| Add a device, first launch | `add-device-light.png` | `add-device-dark.png` | `first-launch.html` |
| Waiting for the device | `pairing-waiting-light.png` | `pairing-waiting-dark.png` | `first-launch.html` |
| Folder offer | `folder-offer-light.png` | `folder-offer-dark.png` | `first-launch.html` |

The system notification in `first-launch.html` is not built yet.

## How they were taken

Run `bash dev/screenshots.sh` (or `bash dev/screenshots.sh <scene>...` for some of them). It builds the debug APK, leases an emulator from `android-broker`, opens each scene in the light and the dark theme, and saves the PNGs here at 540 x 960 with an 8-bit palette. It needs ImageMagick.

- Build: AGP 9.5.1, Gradle 9.9.1, Kotlin 2.4.21, Compose BOM 2026.06.01 (Material 3 1.4.0), compileSdk and targetSdk 36.
- Emulator: `android-broker` phone profile, API 36 `google_apis` x86_64 image (build `BE2A.250530.026.F3`), 1080 x 1920 at 420 dpi, software GLES.
- Status bar in SystemUI demo mode: clock 12:00, full battery and Wi-Fi.
- A scene is a fixed demo state opened by a debug-only intent extra, after a force stop so the demo data starts fresh: `adb shell am start -n app.avoska/.MainActivity --es scene files-syncing`. Scene names are the file names without the theme suffix. They live in `app/src/debug/java/app/avoska/DebugHooks.kt`; release builds contain a no-op instead.

## Differences from the mockups

- Sizes and counts are computed from the demo data instead of copied from the mockups. The On device tab and Storage show 18.5 GB (the mockups say 17.3 GB), because the outdated local copy and all eight episodes inside Series count. "6.8 GB to download", "2.3 of 6.8 GB" and "34%" match.
- Rows are sorted: folders first, then by the chosen order. The mockups list Series before Courses and the files in no particular order.
- The On device tab lists every kept file across subfolders, so it shows Episode 01 to 08 where the mockup shows two.
- The waiting step of pairing, which the mockups describe only in text, shows the start of the device ID, "Waiting for the device", a spinner and the hint from flow 1.
- In the folder offer the demo device is "Office PC" offering "Photos" (the mockup: "Home PC" offering "Movies"), because the demo already shares Movies with a Home PC.
- Connect a folder has a back arrow, which the mockup does not draw, so the step can be left without adding another device.
- The QR code is real (generated with `io.nayuki:qrcodegen`), and the device ID is shown in two lines of four groups so it fits a phone screen.
- Fonts and spacing follow Android's Roboto and the Material 3 components, so text sits a few pixels differently than in the browser mockups.
- The emulator screen is 1080 x 1920, shorter than the 390 x 844 mockup frame, so Settings is cut off below "Show my device ID" and scrolls.
