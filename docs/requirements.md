# Requirements

## First release

- Works as a Syncthing client on Android 14 and newer.
- Lets the user browse remote files and select which ones to keep locally.
- Stores selected files in internal app storage.
- Connects and refreshes the list of available files when the app opens.
- Starts sync only when the user presses a button.
- Keeps a started sync running after the app is minimized, then ends it when finished.
- Shows a notification with progress during a sync, with actions to stop or pause it.
- Opens videos in an external player through `FileProvider`. VLC must work.
- Never stays running in the background permanently.
- Meets Google Play requirements, including target API level and 16 KB memory page support.
- Has an app design developed before implementation starts.
- Is published on GitHub and Google Play.

## After the first release

- Automatic background sync, only while charging and on Wi-Fi, aiming for once every 6 hours. It must run as a short job and must not stay in the background between runs. Details depend on the `background-sync-design` task.
- Built-in video player.
- F-Droid distribution.
