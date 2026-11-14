# Sync download gate: holding downloads while the index stays fresh

Scope: Syncthing v2.1.7 at commit `d6d0181f7d46` (the project pin). Marks: [V] verified in source, primary text or a host test run for this report; [I] inferred; [U] unsourced. `ST/` means the pinned module root, `github.com/syncthing/syncthing@v1.30.0-rc.1.0.20261020195438-d6d0181f7d46`.

Product model used as the frame: offline mode means no network at all; online, the file list always refreshes on any network and never triggers a download; every download (user-pressed Sync, updates of on-device files, new files in selected folders) runs in a bounded Android background job that obeys the download-network setting; nothing runs permanently in the background.

## Answer

1. **No patch is needed.** Stock Syncthing has a usable download gate: the folder option `minDiskFree`. Set to an impossible value (for example `1000000 TB`), every file download fails its free-space check before any temp file or block request, while index exchange, ignore bookkeeping, deletions and metadata-only changes continue. The check fails open: if the filesystem usage query (`statfs`) errors, it allows the download (`ST/lib/config/folderconfiguration.go:369-373`), so the gate holds only where that query works [V source]. The host tests `TestDownloadGate` and `TestDownloadGateMidTransfer` (`engine/gate_test.go`) confirm it in every case where `pullerDelayS` failed: restart with a known update in either start order, unrelated folder config changes, and new versions and new files while open [V].
2. The gate fits the product model: gate closed whenever the UI runs Syncthing; gate opened only inside a download job. Which network the job's transfer actually uses is an open bridge question (section 4).
3. Costs of the stock gate: held files show as pull errors on the phone ("insufficient space" while a peer that has the file is connected, "no connected device has the required version" otherwise), the computer shows the phone below 100% while updates are pending (which is true), each open or close restarts the folder (a PATCH of 22 to 40 ms measured, plus one scan), and closing mid-transfer still receives the data already requested, up to the 32 MiB request window (31.9 MiB measured). Stopping Syncthing ends a transfer with no such tail. Not everything is held: deletions, remote renames of on-device files, new directories and symlinks under selected paths, and metadata-only changes are applied with the gate closed.
4. None of the surveyed clients uses this gate. Sushitrain/Synctrain (same pin, unpatched) downloads whatever is selected and gates by network by pausing devices; BasicSync and Syncthing-Fork stop or pause Syncthing as a whole. Sushitrain's author did write a "stall the puller" patch in 2024 and dropped it for the ignore-file approach.
5. Upstream calls on-demand and placeholder sync "out of scope for syncthing itself" and closes such issues, but has merged many small embedder-facing changes to `lib/syncthing`.
6. A patch stays a sound fallback: about 30 lines in `lib/model` plus `lib/syncthing`, local-only, wire-compatible with stock peers, MPL-2.0 and F-Droid compatible (BasicSync ships four carried patches on this same commit through F-Droid).
7. App-driven fetching through `Internals.DownloadBlock` works on stock but means rewriting the puller (resume, hashing, peer choice, progress, update tracking). Keep it for streaming or preview only.

## 1. Survey

### Syncthing-based clients

| Client | Remote list without download | Download gate | Network gate | Patches Syncthing |
|---|---|---|---|---|
| Sushitrain / Synctrain (iOS, macOS) | Global index is always synced; listing via `Internals.GlobalTree` / `GlobalFileInfo` [V] ([README](https://github.com/pixelspark/sushitrain/blob/1ed5e2c4728cca8f1673dfb2ae241affa58e7b53/README.md), "Selective synchronisation") | `.stignore` whitelist ending in `*`; whatever is selected is pulled by the stock puller at once. No "hold". Unselected files can be streamed or exported on demand with its own mini puller [V] (`SushitrainCore/src/puller.go`, `server.go`, `entry.go:540` "should be outside the synced folder!") | Pauses devices on cellular or metered networks (`disableDevicesOnCellular`, `setDevicesPaused` in `Sushitrain/AppState.swift:862-899`), so no index either [V] | No. `go.mod` requires the same pseudo-version as our pin, with no Syncthing replace [V] (`SushitrainCore/go.mod:17`) |
| BasicSync (Android) | No selective browsing; plain full-folder sync [V] ([README](https://github.com/chenxiaolong/BasicSync/blob/fede5ccbc622fdf2209d66ba631c42ce8ecec692/README.md)) | None | "Supports pausing syncing based on network and battery conditions" for the whole engine [V] (README line 17) | Yes: a fork as git submodule plus `replace ... => ../external/syncthing`; four commits on top of `d6d0181f7d` (global pause option, listener shutdown, support-bundle dir, proxy reset) [V] (`stbridge/go.mod:13`, [fork commits](https://github.com/chenxiaolong/syncthing/commits/2e6e1742f711d4dc35c8b184d13976b6fb47ad01)) |
| Syncthing-Fork / syncthing-android | No remote browsing | None | "Run conditions" start and stop the Syncthing binary as a whole [V, forum statement] ([forum 14473](https://forum.syncthing.net/t/feature-android-app-folder-override-run-conditions/14473)); whether it patches Syncthing was not checked [U] | Not checked [U] |
| Möbius Sync (iOS, closed source) | Store text mentions "selectively synced folders" with ignore patterns; mechanism not published [U] | Unknown [U] | Unknown [U] | Unknown [U] |
| syncthing-lite (Android, dead) | Own Java BEP implementation, "designed to work online by downloading and uploading files from an active device", no local sync; other devices show it at 0%. "Nobody is working on this project anymore." [V] ([README](https://github.com/syncthing/syncthing-lite/blob/master/README.md)) | Everything is on demand | n/a | Not Syncthing code at all |
| syncthingfuse (dead) | FUSE view over BEP, on demand [V, named by imsodin in the comment below] | on demand | n/a | separate implementation |

History worth knowing: in July 2024 Sushitrain's author first built exactly our option D. He reported two patches, one "to stall the puller" (which "needs to be configurable") and one exposing `model.RequestGlobal`, then switched to the ignore-file design, for which "the only Syncthing change" needed was exposing `RequestGlobal` [V] ([forum 22408](https://forum.syncthing.net/t/idea-for-on-demand-syncing-on-ios-need-some-guidance-on-implementing/22408)). The exposure was merged as `Internals` ([PR 9627](https://github.com/syncthing/syncthing/pull/9627)) [V]. In that thread imsodin warned that not pulling "will make your device look out-of-sync to all other devices", and Audrius Butkevicius suggested a "GetFile" API that walks block availability instead of using the puller [V].

### Upstream position

- imsodin, 2023-03-31, on on-demand download: "This can absolutely be done with the BEP ... This is out of scope for syncthing itself. I'd be super exited if anyone were to give it another push ... I might well be motivated to help spin-off the re-usable bits in syncthing into a stable library" [V] ([issue 8845 comment](https://github.com/syncthing/syncthing/issues/8845#issuecomment-1491773349)).
- Later requests are closed with a pointer to that comment: #10250 (duplicate, 2025-08), #10479 and #10433 (not planned, 2025-10/12), #9903 (not planned, 2025-01) [V] (GitHub issue states).
- Older requests (#193, #687, #7341 "New folder type: on-demand") were closed in favour of "next gen ignores" or the forum [V]. PR #9130 "Selective downloads for receive-only folders" and #9131 were closed unmerged as stale in 2025-03 [V] ([PR 9131](https://github.com/syncthing/syncthing/pull/9131)).
- Endorsed workaround in forum answers is always ignore patterns ("It's the way to go") [V] ([forum 21087](https://forum.syncthing.net/t/on-demand-synchronization/21087)); for Android specifically a 2026-05 thread got no answer beyond "Synctrain can do it on iOS and macOS" [V] ([forum 26629](https://forum.syncthing.net/t/how-to-list-only-i-e-moblie-and-sync-access-only-on-demand/26629)).
- No issue or PR was found for "receive index but hold the puller" as such (GitHub title searches for pause/pull/index, on-demand, selective, placeholder) [V for the searches run; absence is I].

### Non-Syncthing clients (brief)

Nextcloud, Dropbox and OneDrive mobile apps list from a server metadata API, keep a local metadata cache, and download a file only on tap or when it is marked "available offline"; offline-marked files are refreshed by a scheduled background job gated by an "only on Wi-Fi / unmetered" setting. Resilio's selective sync keeps the full index and fetches a file when its placeholder is opened [U for all; from general knowledge, not re-checked]. The pattern matches the clarified product model: listing is a metadata channel that is always on, content transfer is a separate, constraint-gated worker. Syncthing's `minDiskFree` gate (below) gives the same split inside one engine.

## 2. Other APIs and levers

### What the phone already has

- The global index for ignored files is received and stored, with block lists: block lists are inserted for every device's files (`ST/internal/db/sqlite/folderdb_update.go:94-150`), and `GetGlobalFile` joins them (`folderdb_global.go:21-43`) [V].
- `/rest/db/browse`, `/rest/db/file` and `Internals.GlobalTree` / `GlobalFileInfo` / `AllGlobalFiles` list ignored files with size and mtime (`ST/lib/syncthing/internals.go:55-61,111`; established in build-strategy.md) [V].
- Under `*` the computer sees the phone at 100% once the phone's pull pass has written the ignored rows; need counts exclude rows flagged ignored (`CountNeed`, `folderdb_counts.go:34` and the need query at `:65`) [V; also measured in the new test: "computer sees phone 100.0% needItems=0"].

### App-driven download with `Internals.DownloadBlock`

- Available on stock: `DownloadBlock` calls `model.RequestGlobal`, which only sends one BEP `Request` on a connection to a named device (`internals.go:47`, `ST/lib/model/model.go:2459-2467`); `BlockAvailability` lists candidate devices (`internals.go:51`, calling `model.go:2848`) [V]. It is not reachable over REST (no use in `lib/api`) [V], so it needs methods in our gomobile bridge, not a Syncthing patch.
- Hash verification is the caller's job: the stock puller verifies after the request (`ST/lib/model/folder_sendrecv.go:1686`), `RequestGlobal` does not [V]. Sushitrain's mini puller does not hash the buffer either (no SHA-256 in `puller.go`) [V].
- What Sushitrain does exactly: sort available peers by measured latency, try "known good" peers, then unknown, then bad, per-block timeout from a 62.5 kB/s floor, retry every 700 ms, 64-entry in-memory block cache, two parallel block fetchers, write sequentially to an `io.Writer`; used for HTTP range streaming, zip/iso browsing and export to a path outside the folder (`puller.go:22-340`, `entry.go:540`) [V]. No resume, no persistent partials [V].
- Where the file could live:
  - Outside the folder (app storage): Syncthing never sees it. Peer completion stays 100% under `*`. The app must track versions itself (compare stored `Version`/`BlocksHash` with the global entry), implement resume, deletion and rename handling, and loses block reuse from old versions [I].
  - Inside the folder while ignored: the file is left alone on disk; the row stays an ignored row with no block list (findings, option C). If it is later un-ignored it is scanned and hashed, and in a receive-only folder a mismatch becomes a "receive-only changed" item [V for the ignored-row behaviour, I for the rest].
- Verdict: this is a second puller (several hundred lines of Go plus tests) that gives up `.syncthing.*.tmp` resume, `needBytes`, `ItemStarted`/`DownloadProgress` events and the tested finish rule. Use it only if streaming or preview is wanted later [I].

### Other levers, checked in source

| Lever | Holds pulls with a fresh index? | Evidence and cost |
|---|---|---|
| **Folder `minDiskFree` set impossibly high** | **Yes** | The check sits in `processNeeded` right before `handleFile` (`folder_sendrecv.go:503`), after ignored rows, deletions, directories and metadata shortcuts are handled (`:341-417`) [V]. Tested below. Cost: pull errors, folder restart per toggle. |
| Options `minHomeDiskFree` high | Holds, but as a folder error | Checked in `getHealthErrorWithoutIgnores` (`ST/lib/model/folder.go:381-388`), which runs before scans too, so ignore changes and scans stop [V source; not tested]. Rejected. |
| Device pause | No | Closes the connection; no index. Sushitrain's network gate works this way [V]. |
| Folder pause | No | Established (findings a). |
| `maxRecvKbps` | No | The limiter wraps the whole connection reader (`ST/lib/connections/limiter.go:226-239`), so index data is throttled with file data; downloads still start and create temp files [V source, I effect]. |
| `pullerDelayS` / `pullerPauseS` | Not reliably | Established (findings b). |
| Unshare and reshare | No | No index while unshared; reshare is a full index exchange [I]. |
| Folder type send-only | Yes, but unsafe | Established (option E). |
| Receive-encrypted | No | Stores encrypted blobs, needs a password on the computer, and loads no ignores (`folder.go:367`) [V]. |
| `ignoreDelete` | Not a gate | Only skips remote deletions (`folder_sendrecv.go:336`) [V]. |
| `*`-only resting ignores | Yes | Re-hash cost on re-arm (findings, option C). |

### The `minDiskFree` gate: host test results

`TestDownloadGate` and `TestDownloadGateMidTransfer` in `engine/gate_test.go`, run on the pin in the loopback-only namespace, three consecutive passing runs on one machine. "(asserted)" marks what the tests check; "(logged)" marks measurements they print but do not check, given as the range over the three runs. Gate closed is `minDiskFree = {value: 1000000, unit: "TB"}`; open is `{1, "%"}`. In all runs the computer that has the files was connected while the held rows were read.

`TestDownloadGate` (about 37 s) [V]:

- Gate closed under `*`: phone idle, nothing needed, no errors, and the computer sees the phone at 100% with 0 needed items (asserted). The pull pass still writes ignored rows, unlike the `pullerDelayS` approach.
- Selection armed (ignores written, scan done) with the gate closed: nothing downloaded and no partial file for 5 s (asserted). Phone `idle`, `needTotalItems=3`, `needBytes=250000` (exactly the selected bytes), `pullErrors=3`, and `/rest/folder/errors` lists exactly the three selected files with an "insufficient space" error (asserted). The computer sees the phone below 100% (asserted; 28.6% logged).
- Gate opened by PATCH: selection downloaded and synced (asserted), 69 to 157 ms after the PATCH (logged); the computer then sees the phone at 100% (asserted).
- Gate closed again: after the folder restart's scan, the local sequence is unchanged, so there was no index update (asserted). Hashing itself is not measured.
- While running with the gate closed: a new version of an on-device file and a new file in a selected directory are both held for 5 s with no partial file, and `needBytes=200000`, the pending bytes (asserted).
- A remote deletion of an on-device file is applied with the gate closed (asserted).
- Restart with the update already known: held for 5 s after the held pass, both when the phone starts before the computer and when the computer is already running (asserted).
- An unrelated folder config change (which restarts the folder) with the gate closed: held for 5 s (asserted).
- Gate opened: pending update and new file synced (asserted), 60 to 63 ms after the PATCH (logged); unselected new files stayed off the device, and the computer sees the phone at 100% (asserted).

`TestDownloadGateMidTransfer` (about 38 s; 64 MiB file, computer limited to 4 MiB/s, so more remains after the close than the request window) [V]:

- Closing the gate at about 8 MiB: the PATCH returned in 22 to 24 ms (logged), and the partial file stopped growing and was kept (asserted).
- The phone still received 31.9 MiB after the close before the transfer stopped, and discarded it (logged; asserted at most 34 MiB). That matches `pullerMaxPendingKiB`, the outstanding-request window: 32 MiB by default (2 x the 16 MiB maximum block size) and never less than 16 MiB (`folder_sendrecv.go:117,158-163,1573`). BEP has no request cancel, so the computer answers every outstanding request [V measurement, I cause].
- Stopping Syncthing mid-transfer instead ends it with no tail. After a stop, `config.xml` rewritten with the gate closed, and a restart, the partial kept its size (16.3 MiB) and at most 1 MiB arrived from reconnect through the held pass and 5 s more (asserted; 173 to 174 bytes logged).
- Reopening finished the file from the partial: 47.76 MiB received since the reconnect for the 47.75 MiB missing (logged; asserted at most missing + 1 MiB).
- Consequence: end a download session by stopping Syncthing (or dropping the connection), not only by closing the gate. This applies equally to a puller patch.

Known properties and limits of the gate:

- It is all-or-nothing per folder. To download one file only, combine it with a narrowed ignore list for that session [I].
- Held files appear in `pullErrors` and `/rest/folder/errors`, one entry per held file, and also in `FolderErrors` events and Warn logs. With a peer that has the file connected the error is "insufficient space" [V, asserted]. Without one it is "no connected device has the required version of this file" (`errNotAvailable`), because availability is checked before space (`folder_sendrecv.go:493-506`) [V source]. While the gate is closed the app must treat both as "held"; the existing "sync finished" rule (`pullErrors == 0`) only applies with the gate open.
- The check fails open: `CheckAvailableSpace` returns nil when the filesystem usage query errors (`ST/lib/config/folderconfiguration.go:369-373`). Device check needed: `statfs` succeeds on Android app storage [V source; device untested].
- What passes the closed gate, because `processNeeded` handles it before the space check: deletions [V, asserted], remote renames of on-device files (the rename shortcut at `folder_sendrecv.go:471-490`, which runs in the file-processing loop before the availability and space checks), new directories and symlinks under selected paths, and metadata-only changes [V source, not tested].
- A failed pull re-arms `pullFailTimer` (`folder.go:472-474`); retries start at `pullerPauseS` (default 60 s) and double after each failure while below 60 times that, so they top out at 64 times (`folder.go:220-223`). Each retry only walks the needed list. Raise `pullerPauseS` to make it rarer [V source].
- The gate value lives in `config.xml`, so it survives a crash or kill. Rule: the bridge writes "closed" before every `App.Start` unless the download job is the caller. Writing it while stopped works (`TestDownloadGateMidTransfer`) [V]; the kill case is untested [I].
- While open, `minDiskFree` should be a real value, so normal space protection is kept [I].
- It relies on the position of one check inside `processNeeded`. That is internal behaviour, so the two tests are part of the default suite (`bash dev/test-engine.sh`) and must pass on every Syncthing bump.

## 3. Is a patch needed?

Not for the gate. If the stock gate's cosmetics (error rows, folder restart per toggle) or its folder-wide scope become a problem, the patch is small.

### Smallest patch

All four pull triggers funnel into `folder.pull` (`folder.go:195-228`), but holding there repeats the `pullerDelayS` flaw: ignored rows are only written inside the pull pass, so need counts and the computer's view go wrong [V] (findings b). The right place is the file case in `processNeeded`:

```go
// ST/lib/model/folder_sendrecv.go, case file.Type == protocol.FileInfoTypeFile:
} else if f.model.pullHeld(f.folderID, file.Name) {
    continue // held: leave it needed, do not queue
} else {
    f.queue.Push(file.Name, file.Size, file.ModTime())
}
```

Plus: an atomic flag or callback on `model`, one method on the `Model` interface, one `Internals.SetPullHold(folder, held)` that calls `SchedulePull` on release, and a start option so the default is "held". Estimate: 30 to 40 lines in `model.go`, `folder_sendrecv.go`, `internals.go`, and the generated mock for upstream tests [I]. A callback variant gives a per-file hold ("Download now" for one file) at the same size. A config-flag variant (`restart:"false"` tag, read live from `m.cfg`) would be togglable over REST without a folder restart; `RequiresRestartOnly` already supports such fields (`ST/lib/config/folderconfiguration.go:333-347`) [V mechanism, I design].

### Compatibility

- The puller is the only sender of file `Request` messages and of `DownloadProgress` (`folder_sendrecv.go:1673`, `progressemitter.go:43,332`); index exchange lives in `indexhandler.go` and is untouched [V].
- A held phone behaves on the wire like a stock device whose downloads fail for lack of space, which the test above shows working against a stock peer [V]. No message, field or version changes. It is not an incompatible fork [I, strongly supported].
- The only peer-visible effect is the truthful one: the computer shows the phone below 100% while selected content is pending [V].

### Carrying a patch on Android only

- Mechanics: a fork as git submodule plus a `replace` in the bridge's `go.mod`, as BasicSync does, or a patch file applied to a vendored copy at build time. The computer runs stock Syncthing either way [V for BasicSync].
- Upgrade cost: rebase one small patch per Syncthing bump. `processNeeded` is central code, so occasional manual conflict resolution is likely; BasicSync keeps four patches current on the same commit we pin [V], which bounds the effort [I].
- Licence: MPL-2.0 is file-level copyleft. Modified Syncthing files must be published under MPL-2.0; the app may use another licence. BasicSync is GPL-3.0 with an MPL fork [V] (README line 310, F-Droid metadata).
- F-Droid: BasicSync is built by F-Droid from source with `submodules: true` and its patched fork ([fdroiddata recipe](https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/com.chiller3.basicsync.yml)) [V]. A public fork or in-repo patch file is acceptable there [I].

### Upstreaming

- Feature-level on-demand sync is out of scope upstream [V, above].
- Small embedder changes do get merged: `Internals` (#9627), progress methods (#9886), external filesystems (#10040, #9887), maintenance controls (#10441, #10565), all from Sushitrain's author [V] ([PR list](https://github.com/syncthing/syncthing/pulls?q=is%3Apr+author%3Apixelspark)).
- The 2024 puller-stall patch was never proposed upstream; its author moved to ignores instead [V, forum 22408].
- A narrow "embedder may veto queuing a file" hook has a plausible but unproven chance; discuss on the forum first, as maintainers ask [I].

## 4. Fit to the product model, including Android

### Engine side

One Syncthing instance per process is a hard limit (`locations` is global, findings), so "one instance in the UI and another in the job" is not available; "a different configuration inside the job" is exactly what the gate is [I].

| State | Syncthing | Gate |
|---|---|---|
| Offline mode | stopped | n/a |
| App visible, no job | running | closed: list refreshes on any network, nothing downloads |
| Download job running on an allowed network | running (started by the job if the UI is not) | open |
| Job ends, or network no longer allowed | stopped if no UI; restarted with gate closed if the UI is visible (a stop cuts the tail that closing alone leaves) | closed |

- The pending download size for the job's notification and `setEstimatedNetworkBytes` is `needBytes` from `/rest/db/status` with the gate closed, after the post-ignore scan and the held pull pass [V, asserted equal to the pending selected bytes].
- "Switch ignores when the job starts and ends" is weaker: a `*`-only rest re-hashes every file on re-arm, and a per-file rest does not hold updates (findings b). With the gate, ignores change only when the selection changes [V].
- Deletions made on the computer reach on-device files while the gate is closed, and so do renames, new directories and symlinks, and metadata changes. None of these is a download, but the flows should say so [V for deletions, source for the rest].

### Android side

- **User-pressed Sync: UIDT job.** It must be scheduled while the app is visible, needs a notification, accepts `setRequiredNetworkType`/`setRequiredNetwork` (for example `NET_CAPABILITY_NOT_METERED`), is exempt from App Standby Bucket quotas, and is stopped when "a developer-defined constraint is no longer met", after which the system calls `onStopJob()` and retries later [V] ([UIDT guide](https://developer.android.com/develop/background-work/background-tasks/uidt)). That maps onto "open gate in `onStartJob`, stop Syncthing in `onStopJob`".
- **Automatic updates: WorkManager, never UIDT.** UIDT needs an explicit user request; the app being visible is not enough. Only a user-pressed Sync (or Download now) is a UIDT job. Automatic downloads are WorkManager periodic work with `NetworkType.UNMETERED` (or the user's setting). Google's guidance: UIDT only if "the user began the data transfer"; for a playlist that should "update periodically in the background without user initiation, WorkManager would be a better choice"; WorkManager is "for scheduling tasks with duration less than 10 minutes" and "jobs taking longer than 10 minutes will be rescheduled by the system" [V] ([data transfer options](https://developer.android.com/develop/background-work/background-tasks/data-transfer-options)). So automatic downloads run in slices of at most about 10 minutes and must resume; Syncthing partials survive stop and kill (findings f) [V]. Each slice: start Syncthing, wait for the index, open the gate, pull, stop.
- A long-running worker would be a `dataSync` foreground service, capped at 6 hours per 24 hours on Android 15 targets [V], and background starts of foreground services are restricted [I] ([FGS timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout)). Not needed here.
- Android 16: jobs started while the app is visible that continue after it becomes invisible, and jobs running alongside a foreground service, now count against the job runtime quota [V] ([Android 16 behavior changes](https://developer.android.com/about/versions/16/behavior-changes-all)). This affects regular WorkManager work started from the UI; whether it changes UIDT runtime is not stated there [U].
- Large automatic updates (multi-GB videos) will take many 10-minute slices. A product option: when the app is opened and `needBytes` is large, offer a Sync to the user; only the user's press may start a UIDT job [I].
- **Open bridge question: which network carries the transfer.** Syncthing opens its own sockets on whatever network is the default, and its existing connections may already run over cellular when an unmetered job opens the gate. A network-loss callback is therefore not enough. The job must make Syncthing use the job's network (`JobParameters.getNetwork()`), or at least drop and re-establish connections on that network before opening the gate. How to do either from the bridge (process default network, socket binding, or a reconnect) is not solved and needs a device test [I].

## 5. Recommendation

1. **Stock `minDiskFree` gate (recommended).** No fork, no patch, exact fit: index always fresh, downloads only when the job opens the gate. Costs: map the gate's pull errors (both "insufficient space" and "not available") to a "held" state in the app; route the job's traffic over the job's network (open question); one folder restart per open or close; folder-wide only; stop or reconnect Syncthing at the end of a session to avoid the trailing data; the two gate tests guard the behaviour on upgrades.
2. **Carried pull-hold patch (fallback, about 30 to 40 lines).** Take it if the gate's error rows or folder restarts prove troublesome, or if a per-file hold is required. Costs: a fork or build-time patch, a rebase on every Syncthing bump, MPL publication of the changed files; no peer or protocol impact. Optionally propose the hook upstream.
3. **App-side fetching with `DownloadBlock` (not for sync).** Only for streaming or preview of unselected files. As the main download path it replaces the puller and its resume, verification, progress and accounting.

## 6. Next host tests

1. Slow initial scan race: many on-disk files, gate closed, already connected at start-up; assert nothing downloads (the case left open for `pullerDelayS`).
2. Long hold: gate closed for longer than one `pullFailTimer` cycle with `pullerPauseS` at 5 s, 60 s and 3600 s; assert no download, and log CPU and event volume per retry.
3. Scale: 100,000 remote files, 2,000 selected and pending with the gate closed; time one held pull pass and count `FolderErrors` payload size.
4. Zero-byte files, a file whose content already exists locally under another name (copy-only), and a remote rename of an on-device file with the gate closed; record which of these pass the gate.
5. Trailing data: repeat the mid-transfer test with `pullerMaxPendingKiB` at its minimum and with "pause device" ("stop Syncthing" is done: no tail).
6. Finish rule with the gate: after opening, confirm the existing rule (scan done, idle, need 0, no errors) has no false positives over 7 runs, given that the folder restarts on the PATCH.
7. Gate state after kill: SIGKILL with the gate open, restart through the "write closed before start" path, assert nothing downloads.
8. Single-file download: gate open with ignores narrowed to one file plus per-file lines for on-device files; assert only that file transfers and no re-hash follows when the full selection is restored.
9. If the patch is pursued: apply the `processNeeded` hold in a scratch module copy and rerun the two gate tests and tests 1 to 4 against it, plus a check that toggling causes no folder restart.
