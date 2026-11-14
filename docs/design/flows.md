# Screens and flows, first release

Status: approved by the user on 2026-11-14. Visual style and control layout are in [ui.md](ui.md).

## Screens

| Screen | Purpose |
|---|---|
| Pairing | First launch: add the first device and accept a shared folder. |
| Files | Main screen: browse remote files, select which to keep, start sync, open files. |
| Settings | Devices, this phone's device ID, network, storage use, licences. |

Outside the app: the sync notification.

## Model

The app follows the Syncthing model. It can be connected to any number of devices, and each shared folder can be shared with any of them. The file list is the folder's global state as Syncthing reports it. The app assumes the other devices keep converging among themselves and downloads from whichever of them are connected.

## When Syncthing runs

- App visible: Syncthing runs, connects and refreshes the file list. Nothing is downloaded.
- Connecting and refreshing the file list work on any network. The sync network setting (flow 9) limits only the Sync button.
- Offline mode on: Syncthing does not run and the app uses no network.
- Global discovery and relays are on, as in stock Syncthing, with no setting.
- App leaves the screen with no sync running: Syncthing stops.
- Sync running: Syncthing runs inside the UIDT job until the sync finishes, is paused or is stopped.

## Flows

### 1. Pairing (first launch)

1. The user adds a device: scans the QR code of its device ID, or pastes the ID. Syncthing on a computer shows this QR code under Actions > Show ID. The camera permission is requested when the user taps Scan; if it is refused, pasting still works.
2. The app shows "Waiting for the device" and a hint: confirm the new device in Syncthing on the computer. The other side sees the phone's connection attempt and offers to add it, so the phone's ID does not have to be typed there. The phone's own ID is available as QR code and text behind a "Show my ID" action.
3. When a device shares a folder, the app shows the offer with the folder name and the offering device. The user accepts it. The folder is added as receive-only with nothing selected.
4. The app opens Files.

Errors: an invalid device ID is rejected at entry. If no connection appears, the screen stays on step 2 with a hint that both devices must be online and must have added each other.

More devices are added later in Settings with the same steps 1 and 2.

### 2. Opening the app

1. Files opens at once with the last known file list and a "Connecting" status.
2. When at least one device connects, the list refreshes and the status shows how many devices are connected.
3. With no device connected the status is "Offline". Downloaded files stay usable, selection stays editable, and Sync and Download are disabled.
4. In offline mode the app does not connect. The status is "Offline mode" with an action to turn it off, and the screen behaves as in step 3.

A folder offered later by any device appears as a banner on Files with an accept action.

### 3. Browsing and selecting

- Top level lists the shared folders. With one folder the app opens straight into it.
- Two tabs: "All files" and "On device". "On device" lists only the files kept on the device, across subfolders, with their total size in the tab label.
- Sort by name, size or modification date. The default is name. There is no search.
- Each row shows name, size and state. Folders show the total size of their selected content.
- Each row has a "keep on device" toggle. Toggling a folder applies to everything inside it, including files added to it later.
- File states: not selected, selected and waiting for sync, downloading, on device, on device but outdated.
- A bar at the bottom shows how much is waiting for download, the free space on the device and the Sync button.
- Each row has a menu: Download now (flow 6), Open, Remove from device, Details. Only the actions that apply to the file's state are shown.
- Selection changes are saved at once but download nothing until Sync is pressed.

### 4. Deselecting

- A file that is not on the device: deselected at once.
- A file or folder that is on the device: the app asks to confirm deleting the local copy and shows the size it frees. On confirmation the copy is deleted at once.

### 5. Sync

Sync downloads everything selected. It is allowed only on the networks chosen in the sync network setting. On any other network, or with the setting off, the Sync button is disabled and the bar says why.

1. The user presses Sync. On first use the app asks for the notification permission. If it is refused, sync still runs, and the app explains that progress is visible only in the app.
2. If the pending download exceeds free space, sync does not start and the app says how much is missing.
3. The bottom bar becomes a progress bar with downloaded and total size, plus Pause and Stop. Rows show per-file progress.
4. The user may leave the app. Sync continues.
5. On completion the bar returns to idle. The notification shows "Sync finished" for about 3 seconds and then disappears.

If the network changes to one the setting does not allow, the sync pauses and the notification says why.

Files that changed on other devices and are selected on the phone are downloaded again in the same sync.

### 6. Downloading one file

- Download on a file selects it and downloads it at once, on any network and with any sync network setting.
- Tapping a file that is not on the device does the same and opens the file when the download finishes, if the app is still on screen.
- The download runs like a sync: same progress bar, notification, Pause and Stop, and it continues after the app is minimized.
- Nothing else that is selected is downloaded.

### 7. Notification

| State | Content | Actions |
|---|---|---|
| Running | Progress bar, downloaded and total size | Pause, Stop |
| Paused | "Sync paused", downloaded and total size | Resume (opens the app and continues), Stop |
| Finished | "Sync finished", number of files. Disappears after about 3 seconds | None |
| Failed | Reason: connection lost, out of space | Tap opens the app |

Tapping the notification opens Files. Stop removes the notification. Pause and Stop keep partial downloads.

If every device disconnects during a sync, the job waits for it to return until the system ends the job. Then the notification shows the failure.

### 8. Opening a file

- A file of any type on the device: tap opens it in an external app through `FileProvider`. If several apps can open it, the system chooser appears.
- No app can open it: the app says so. For video it suggests installing VLC.
- A file not on the device: tap downloads it and then opens it (flow 6).
- An outdated file on the device opens as is.

### 9. Settings

- Devices: list with name and connection state, an action to add a device, and an action to remove one.
- Folders: list with the devices each is shared with, and an action to remove a folder. Removing it asks to confirm deleting its downloaded files.
- This phone's device ID: QR code and text.
- Sync network: off; Wi-Fi (default); Wi-Fi and mobile data except roaming; any network. Off means there is no Sync, only single-file downloads. Automatic background sync, when it is added after the first release, will obey the same setting.
- Offline mode: a toggle. When on, the app uses no network.
- Storage: space used by downloaded files, free space.
- About: version, open-source licences.

Permissions are requested at the moment they are needed, following Android guidance: camera on Scan, notifications on the first Sync.

## Open questions

None.
