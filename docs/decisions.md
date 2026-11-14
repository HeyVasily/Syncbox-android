# Decisions

## Accepted

All decided on 2026-11-02.

- **Product scope:** this app is a Syncthing client and nothing else. The other Syncthing clients it syncs with are out of scope for this project.
- **Minimum Android version:** Android 14 (API 34). Older versions are not supported.
- **Selective sync:** the user picks files to keep locally, similar to offline files in Yandex Disk.
- **Storage:** synced files live in the app's internal storage, not on external storage.
- **Video playback:** videos open in an external player through `FileProvider` (`content://` URIs). VLC must work. A built-in player comes after the first release.
- **Sync in the first release:** sync starts only when the user presses a button. When the app opens, it connects and refreshes the list of available files.
- **Leaving the app during a sync:** a sync started with the button keeps running after the app is minimized, then ends on its own. It runs as a user-initiated data transfer (UIDT) job.
- **Sync notification:** a notification shows progress during a sync and lets the user stop or pause it.
- **Automatic background sync:** comes after the first release, as a separate task.
- **Battery:** the app must not stay running in the background permanently. Saving battery is a hard requirement.
- **Distribution:** GitHub and Google Play. F-Droid is planned.
- **Order of work:** no prototypes yet. App design and the verification setup come first, so that an agent can build and fully check a prototype on its own.
- **Verification setup:** agents get an emulator in the yard for on-device checks. This setup is not a project deliverable.
- **Git workflow:** work happens on `master`. Committing is not available to agents at the moment, so the operator commits. This may change: see the `agent-commit-policy` task.

## Proposed, not yet confirmed

- **Syncthing integration:** run Syncthing as an in-process Go library through a thin gomobile bridge, controlled through its REST API. Syncthing has no official SDK.
- **Toolchain starting point:** Go 1.27.3, NDK r30, Syncthing v2.1.7, AGP 9.5.0 and target SDK 36, per the [16 KB research](research/16kb-page-size.md). The full combination has not been built yet. Re-check versions when the project is set up.
- **ABIs:** ship `arm64-v8a`. Use `x86_64` for emulator testing, possibly only in a test variant.
- **16 KB checks:** native libraries stay uncompressed (`useLegacyPackaging = false`). CI checks ELF alignment of every 64-bit library and ZIP alignment of APKs, including those generated from the App Bundle.
- **16 KB runtime testing:** test inside the installed app, not through `adb shell`, because app seccomp rules do not apply there. Test on a 16 KB image with compatibility mode off, and on a normal 4 KB image at API 34.
- **Sync direction:** computer to phone only, using a receive-only folder on the phone.
- **Selective sync mechanism:** a whitelist in ignore patterns, with the app deleting local copies when a file is deselected. Behaviour still has to be verified.
- **Pause and stop behaviour:** both end the job and stop Syncthing, so a paused sync uses no battery. Pause keeps a notification with a resume action, and resuming opens the app, because scheduling a UIDT job generally requires the app to be visible. Stop removes the notification. Partial downloads are kept for later, for as long as Syncthing retains them. All of this still has to be verified.

## Open questions

- **App name:** "Avoska" is chosen, with the repository to become `avoska-android`; the name is not yet checked against stores and trademark registers, and the `applicationId` is open. See the `rename-to-avoska` task.
- **Background sync design:** to be discussed after the first release. See the `background-sync-design` task.
- **Verification setup:** how an agent reproduces and checks everything. See the `verification-setup` task.
- **App design:** to be developed together. See the `app-design` task.
- **Agent commits and operator control:** whether agents should be allowed to commit, and where the operator's control boundary should sit. See the `agent-commit-policy` task.
