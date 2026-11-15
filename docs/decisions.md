# Decisions

## Accepted

All decided on 2026-11-02.

- **Product scope:** this app is a Syncthing client and nothing else. The other Syncthing clients it syncs with are out of scope for this project.
- **Minimum Android version:** Android 14 (API 34). Older versions are not supported.
- **Selective sync:** the user picks files to keep locally, similar to offline files in Yandex Disk.
- **Storage:** synced files live in the app's internal storage, not on external storage.
- **Video playback:** videos open in an external player through `FileProvider` (`content://` URIs). VLC must work. A built-in player comes after the first release.
- **Sync in the first release:** newly selected files download when the user presses Sync. When the app opens, it connects and refreshes the list of available files. Updates of selected content download automatically, see below.
- **Leaving the app during a sync:** a sync started with the button keeps running after the app is minimized, then ends on its own. It runs as a user-initiated data transfer (UIDT) job.
- **Sync notification:** a notification shows progress during a sync and lets the user stop or pause it.
- **Automatic background sync:** periodic checks without opening the app come after the first release, as a separate task.
- **Battery:** the app must not stay running in the background permanently. Saving battery is a hard requirement.
- **Distribution:** GitHub and Google Play. F-Droid is planned.
- **Order of work:** no prototypes yet. App design and the verification setup come first, so that an agent can build and fully check a prototype on its own.
- **Verification setup:** agents get an emulator in the yard for on-device checks. This setup is not a project deliverable.
- **Network levels (2026-11-14):** offline mode: Syncthing does not run and the app uses no network. Online: the file list refreshes on any network and never downloads anything by itself. Every download runs in a background job that obeys the sync network setting; only Download now on one file ignores the setting, as decided for flow 6 on 2026-11-13.
- **Automatic updates in the first release (2026-11-14):** new versions of files kept on the device and new files in selected folders download on their own in a background job, on the networks the sync network setting allows. They are noticed when the app refreshes the list; the job keeps running after the app is closed and then ends.
- **Folder size (2026-11-14):** a folder row shows the total size of its selected content, the part kept for offline use.
- **Application ID (2026-11-14):** `app.avoska`. It cannot change after the first Google Play release.
- **App name:** "Avoska"; the repository becomes `avoska-android`. The store title is the Latin "Avoska"; the product targets the worldwide market.
- **Git workflow:** work happens on `master`. Committing is not available to agents at the moment, so the operator commits. This may change: see the `agent-commit-policy` task.
- **App design (2026-11-14):** approved. Screens and flows are in [design/flows.md](design/flows.md), visual style and control layout in [design/ui.md](design/ui.md).

## Proposed, not yet confirmed

- **Syncthing integration:** run Syncthing as an in-process Go library through a thin gomobile bridge, controlled through its REST API. Syncthing has no official SDK.
- **Toolchain starting point:** Go 1.27.3, NDK r30, Syncthing v2.1.7, AGP 9.5.0 and target SDK 36, per the [16 KB research](research/16kb-page-size.md). The full combination has not been built yet. Re-check versions when the project is set up.
- **ABIs:** ship `arm64-v8a`. Use `x86_64` for emulator testing, possibly only in a test variant.
- **16 KB checks:** native libraries stay uncompressed (`useLegacyPackaging = false`). CI checks ELF alignment of every 64-bit library and ZIP alignment of APKs, including those generated from the App Bundle.
- **16 KB runtime testing:** test inside the installed app, not through `adb shell`, because app seccomp rules do not apply there. Test on a 16 KB image with compatibility mode off, and on a normal 4 KB image at API 34.
- **Sync direction:** computer to phone only, using a receive-only folder on the phone.
- **Selective sync mechanism:** a whitelist in ignore patterns, with the app deleting local copies when a file is deselected. Behaviour still has to be verified.
- **Download gate:** stock Syncthing holds downloads with the folder option `minDiskFree` set out of reach, while the index keeps refreshing. The gate is closed whenever Syncthing runs outside a download job. A job opens it and ends by stopping Syncthing; if the app is still on screen, Syncthing starts again with the gate closed. The gate is folder-wide, so a job downloads everything armed in the ignore file: a Download now job on a network the setting does not allow must arm only its file, so that pending automatic updates do not download with it. That still has to be built and tested. A small puller-hold patch is the fallback. Evidence and limits: [download gate research](research/sync-download-gate.md); still to check on a device: free-space lookup on app storage, and that a job's transfer uses the job's network.
- **Job types:** Sync and Download now run as user-initiated data transfer (UIDT) jobs. Automatic update downloads run as WorkManager work with a network constraint from the sync network setting, never as UIDT.
- **Pause and stop behaviour:** both end the job and stop Syncthing, so a paused sync uses no battery. Pause keeps a notification with a resume action, and resuming opens the app, because scheduling a UIDT job generally requires the app to be visible. Stop removes the notification. Partial downloads are kept for later, for as long as Syncthing retains them. All of this still has to be verified.

## Open questions

- **Periodic background sync:** checking for changes without opening the app is discussed after the first release. See the `background-sync-design` task.
- **Verification setup:** how an agent reproduces and checks everything. See the `verification-setup` task.
- **Agent commits and operator control:** whether agents should be allowed to commit, and where the operator's control boundary should sit. See the `agent-commit-policy` task.
