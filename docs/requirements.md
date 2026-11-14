# Requirements

## First release

- Works as a Syncthing client on Android 14 and newer.
- Lets the user browse remote files and select which ones to keep locally.
- Stores selected files in internal app storage.
- Connects and refreshes the list of available files when the app opens.
- Refreshes the file list on any network without downloading anything; downloads run only in background jobs.
- Downloads newly selected files when the user presses Sync.
- Downloads updates of selected content automatically in the background, only on the networks the user allows.
- Keeps a started sync running after the app is minimized, then ends it when finished.
- Shows a notification with progress during a sync, with actions to stop or pause it.
- Opens videos in an external player through `FileProvider`. VLC must work.
- Never stays running in the background permanently.
- Meets Google Play requirements, including target API level and 16 KB memory page support.
- Has an app design developed before implementation starts.
- Is published on GitHub and Google Play.

## After the first release

- Periodic automatic background sync without opening the app, only while charging and on Wi-Fi, aiming for once every 6 hours. It must run as a short job and must not stay in the background between runs. Details depend on the `background-sync-design` task.
- Built-in video player.
- F-Droid distribution.
