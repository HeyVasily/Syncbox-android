# Verification

How the app is checked, from host-only tests to the installed app on an emulator. CI runs the repository and build checks, and the instrumented tests on emulators ([ci.md](ci.md)); the rest below runs locally.

| Level | Command | What it covers |
|---|---|---|
| Host Go tests | `bash dev/test-engine.sh` | The bridge's start and stop, then Syncthing behaviour against a second Syncthing ([engine/README.md](../engine/README.md)) |
| App build | `bash dev/build-app.sh` | Bridge AAR, debug and minified release APKs, lint, JVM unit tests, 16 KB checks |
| Emulator, instrumented tests | `bash dev/instrumented-tests.sh` | The installed app starts Syncthing through the bridge, the library runs from the APK; also in CI on 4 KB and 16 KB pages |
| Emulator, real engine | `bash dev/e2e-emulator.sh [--screenshots]` | Installed app with Syncthing in-process, paired with a peer on the host |
| Emulator, demo screens | `bash dev/screenshots.sh` | Debug scenes on the demo engine ([screenshots](design/screenshots/README.md)) |

## Toolchain

- JDK 17, Android SDK with platform `android-36` and build-tools `36.0.0` (`ANDROID_HOME`).
- NDK r30, `30.0.16248370`, at `ANDROID_NDK_HOME`. The shared SDK may be read-only; install the NDK into a writable SDK root and point the variable at it, for example `sdkmanager --sdk_root=$HOME/.cache/avoska-sdk "ndk;30.0.16248370"` and `export ANDROID_NDK_HOME=$HOME/.cache/avoska-sdk/ndk/30.0.16248370`.
- Go: whatever `go` is installed; `GOTOOLCHAIN=auto` fetches the version `engine/go.mod` names (1.26.2).
- gomobile and gobind `v0.0.0-20261011034528-197770444867`, pinned by `tool` directives in `engine/go.mod` and built into `.build/bridge/bin/` by `dev/build-bridge.sh`.

## The bridge build

Gradle's `buildBridge` task (a dependency of every app build) runs `dev/build-bridge.sh`, which runs `gomobile bind` on `engine/bridge` for `android/arm64` and `android/amd64` with `-tags noassets`, `-checklinkname=0` and explicit 16 KB page-size linker flags, into `.build/bridge/bridge.aar` (gitignored, never committed). The task is up to date while the script, `engine/go.mod`, `engine/go.sum` and `engine/bridge/` are unchanged; a fresh build takes about 2 minutes, a rebuild about 15 s.

16 KB checks, all failing the build:

- `dev/build-bridge.sh`: every `LOAD` segment of both `libgojni.so` is aligned to at least 16 KiB (`llvm-readelf -l`).
- `dev/build-app.sh`, for the debug and the release APK: `zipalign -c -P 16 4`, every `lib/*.so` stored uncompressed (`useLegacyPackaging = false`), and the same ELF check on the libraries inside the APK.

## Emulator check with a peer

`dev/e2e-emulator.sh` builds the debug APK and the harness test binary, then starts a peer on the host: stock Syncthing from the pinned module (the binary's helper mode, as in the host tests), with discovery, relays and NAT off, sharing a folder of six files (5 MB, 200 KB, 1 KB, 10 B, and a `Series/` subdirectory with two files), including the names `a [b] {c} #d !e.txt` and a non-ASCII name. It leases an emulator from `android-broker` and checks, in order:

1. The installed app starts Syncthing in its own process (logcat line from `AvoskaEngine`; a `base.apk` mapping in `/proc/<pid>/maps` whose offset lies inside `libgojni.so`'s bytes in the APK, since the library is mapped from the APK without extraction).
2. The peer adds the phone's device ID (from logcat) and shares the folder with it. The phone adds the peer through the app's Add a device screen: the ID is typed in chunks of 8 characters and each chunk checked in the field (the emulator's `input text` drops characters under load), and Enter, the field's Done action, adds it.
3. The phone gets a static address for the peer through its REST API, called with `adb shell` and the key from the debug app's config (`run-as`). Reason: the broker's adb server runs in another network namespace, so `adb reverse` and `adb forward` ports never reach this host; the emulator does reach the host's own address, so the peer listens there.
4. The folder offer appears on the Connect a folder screen and is accepted in the app.
5. Files lists every name and size from the peer and the subdirectory's content. Sync is disabled with "Downloading is not available in this version yet".
6. Nothing is downloaded: the folder is receive-only with `minDiskFree` 1000000 TB and ignores `*`. Because the ignores already hold everything back, the gate's free-space check (the pull refused for lack of space) is not exercised on the device yet (`engine/gate_test.go` covers it on the host). The phone's database has 0 local bytes and needs nothing; only `.stignore` is on disk; the peer sees the phone at 100%. Selecting a file stores the intent in `files/selection.json` and still downloads nothing.
7. Home button: Syncthing stops within 5 s (logcat; the open `/rest/events` long-poll is cancelled, not waited out), its REST API closes, the peer sees the phone disconnect. Opening the app starts it again and reconnects.
8. Offline mode in Settings: Syncthing stops within 5 s and stays stopped; Files says "Offline mode". After `am force-stop` and a cold start, the stored setting keeps Syncthing stopped.
9. No crash in logcat.

A "System UI isn't responding" dialog, which the loaded emulator sometimes shows, is dismissed with Wait; the same dialog for the app fails the run. On failure the script saves `failure.png` and `logcat.txt`.

Results go to `.build/e2e/result.txt`, one `CHECK ok:` line per check; logcat, the peer log and raw screenshots stay in `.build/e2e/`. `--screenshots` also saves the `real-*` screenshots in [design/screenshots](design/screenshots/README.md).

The broker's API 36 image has 4 KB pages (`getconf PAGE_SIZE` is 4096); its catalog has no 16 KB image. The 16 KB runtime check runs in CI instead: the instrumented tests on the API 36 `google_apis_ps16k` image ([ci.md](ci.md#emulator)).
