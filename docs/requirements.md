# Requirements

## First release

- Works as a Syncthing client on Android 14 and newer.
- Lets the user browse remote files and select which ones to keep locally.
- Stores selected files in internal app storage.
- Starts sync manually.
- Opens videos in an external player through `FileProvider`. VLC must work.
- Never stays running in the background permanently.
- Meets Google Play requirements, including target API level and 16 KB memory page support.
- Has an app design developed before implementation starts.
- Is published on GitHub and Google Play.

## Required, timing to confirm

- Automatic sync, only while charging and on Wi-Fi, aiming for once every 6 hours. It must run as a short job and must not stay in the background between runs. Details depend on the `background-sync-design` task.

## After the first release

- Built-in video player.
- F-Droid distribution.
