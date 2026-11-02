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

- **Syncthing integration:** run Syncthing as an in-process Go library with a thin bridge, controlled through its REST API. Syncthing has no official SDK.
- **Sync direction:** computer to phone only, using a receive-only folder on the phone.
- **Selective sync mechanism:** a whitelist in ignore patterns, with the app deleting local copies when a file is deselected. Behaviour still has to be verified.
- **Pause and stop behaviour:** both end the job and stop Syncthing, so a paused sync uses no battery. Pause keeps a notification with a resume action, and resuming opens the app, because scheduling a UIDT job generally requires the app to be visible. Stop removes the notification. Partial downloads are kept for later, for as long as Syncthing retains them. All of this still has to be verified.

## Open questions

- **App name:** to be discussed. See the `decide-app-name` task.
- **Background sync design:** to be discussed after the first release. See the `background-sync-design` task.
- **Verification setup:** how an agent reproduces and checks everything. See the `verification-setup` task.
- **16 KB memory pages:** being researched. See the `research-16kb-page-size` task.
- **App design:** to be developed together. See the `app-design` task.
- **Agent commits and operator control:** whether agents should be allowed to commit, and where the operator's control boundary should sit. See the `agent-commit-policy` task.
