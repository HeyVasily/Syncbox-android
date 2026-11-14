# Visual style and control layout

Status: approved by the user on 2026-11-14.

## Mockups

Standalone HTML files in [mockups/](mockups/), each showing three screens in the light theme and the same three in the dark theme. Open them in a browser. UI text, file names and sizes in them are examples.

| File | Screens |
|---|---|
| `files.html` | All files, On device tab, syncing |
| `files-states.html` | File menu, remove confirmation, offline mode |
| `settings.html` | Settings, sync network, my device ID |
| `first-launch.html` | Add device, folder offer, notification states |

## Style

- Plain Material 3 with standard components.
- Light and dark themes, both required on every screen. The app follows the system theme.
- One accent colour (blue in the mockups). File state is shown by text under the file name, with colour as a second cue.

## Files screen

From top to bottom:

1. Top bar: back, folder name, connection status under the name, settings (gear icon).
2. Tabs: "All files" and "On device" with the size kept on the device.
3. Sort row: Name, Size, Date as chips.
4. File rows: type icon, name, size and state, a "keep on device" switch, a menu button.
5. Bottom bar: size waiting for download, free space, Sync button. During a sync it shows progress, Pause and Stop.

Row menu: Download now, Open, Remove from device, Details.

Tapping a row opens the file, or downloads and then opens it if it is not on the device.

## Other screens

Settings, adding a device, the folder offer and the notification are as drawn in the mockups. Devices and folders are managed in Settings.

## Rejected layouts

Bottom navigation tabs, a selection mode on long-press, a floating Sync button, a sync card at the top, a home dashboard, a separate sync screen.
