# Engine host-side findings

Measured facts about unmodified Syncthing, from the host-side tests in [`engine/`](../../engine/README.md). They answer the Syncthing questions of the `end-to-end-prototype` task and replace inferences in [build-strategy.md](build-strategy.md). Nothing here was run on Android; device behaviour (storage, seccomp, memory, UIDT) is still open.

## Setup

- Syncthing v2.1.7, commit `d6d0181f7d466381c4b39ec8fd944c364f939b32`, required as `github.com/syncthing/syncthing v1.30.0-rc.1.0.20261020195438-d6d0181f7d46` with Syncthing's two `replace` directives copied. Build tag `noassets`.
- Go 1.26.2 (Syncthing's minimum, fetched through `GOTOOLCHAIN=auto`), `linux/amd64`, 16 cores.
- The "phone" embeds `lib/syncthing` in the test process and is driven over REST and `/rest/events`. The "computer" is the same binary re-run as a child process. Both use temporary homes, static `tcp://127.0.0.1` addresses, and have discovery, relays, NAT, usage and crash reporting, and upgrades turned off. The phone folder is receive-only, created paused, set to `*`, then unpaused.
- `bash dev/test-engine.sh` runs the suite in a network namespace that has only loopback. Default suite: 29 tests; three consecutive runs all passed (exit 0, 272 to 278 s each). Heavy runs: 50 and 150 start/stop cycles, and 100,000 remote files with up to 5,000 lines.
- Timings are from one machine. Treat them as orders of magnitude, not as phone numbers.
- "Read in source" marks statements taken from Syncthing's code at the pin rather than measured.

## Answers

### a) A paused folder does not receive index updates — confirmed

`TestPausedFolderGetsNoIndexUpdates`. While the phone's folder was paused, the computer added a file. In 5 s no `RemoteIndexUpdated` event arrived for the folder, and the connection stayed up. After unpausing, the event fired and the file was in the index 61 ms later. A paused folder also has no file list over REST: `/rest/db/browse` and `/rest/db/file` return `500 no such folder`, and `/rest/db/status` returns an empty state with `globalFiles=0`. Pausing therefore cannot be the Sync-button gate, and anything shown while paused must come from the app's own cache.

### b) What downloads with the app only open

- A new version of a whitelisted file on the device downloads by itself, 1.3 s after the computer's scan (`TestOpenAppDownloadsUpdatesOfSelectedFiles`).
- A new file under a whitelisted directory downloads by itself, 1.3 s after the computer's scan (`TestOpenAppDownloadsNewFilesInSelectedDirectory`).
- Resting per-file whitelist (option B), `TestRestingPerFileWhitelist`: rewriting `!/Movies` to one line per file on disk stops new files in `Movies` from downloading (the test first waits until the phone's index has the new file, then checks that it stays undownloaded for 5 s), but new versions of those files still download. The rewrite caused no re-hash: the 64 MiB file's local sequence was unchanged, no `LocalIndexUpdated` named it, and the scan took 6 ms against 5 ms for an unchanged scan.
- Resting on `*` alone (option C), `TestIgnoreAllRehashes`: the on-device file's row becomes an ignored row with size 0 and no block list, and the file stays on disk. Arming it again re-hashes it. The test asserts that the scan re-indexes the 64 MiB file as a new local row (a `LocalIndexUpdated` event names it, its sequence rises, its size is back) and that no locally changed item is left; that scan took 190 to 270 ms. This confirms option C's cost.
- Large `pullerDelayS` (3600), `TestLargePullerDelay` and `TestLargePullerDelayAfterRestart`. Each "held" check first waits until the phone counts the new version as needed, then asserts that it is not downloaded for 5 to 15 s.
  - Held: the pull after an ignore change, the pull an index update schedules while the app is open, and, after a restart, an update learned after connecting (again with the computer started only after the phone's start-up pull).
  - Not held by `pullerDelayS` alone: on restart with an update the phone already knew of from an earlier session, if the start-up pull runs before the connection is up (the test forces this order by starting the computer only after the phone's start-up pull), it fails ("no connected device has the required version"). The retry comes after `pullerPauseS` (default 60 s) and ignores `pullerDelayS`. With `pullerPauseS=5` the file downloaded 6.1 s after start; with `pullerPauseS=3600` it was held for 15 s.
  - Any folder config change restarts the folder, and the pull after its initial scan ignores the delay. Setting `pullerDelayS` to 0 downloaded the file in about 0.1 s. That is how a Sync would have to work, and it means any other config change also pulls.
  - Cost: until a pull pass runs, ignored files are not written as ignored rows. With `*` and the delay the phone sits in `sync-waiting` with `needTotalItems=1`, and the computer shows the phone at 0% (asserted after the computer has the phone's index).
  - Not tested: the start-up pull when the initial scan is slower than the connection (for example with many files on disk), which would pull at once while connected; and what happens when the finite delays expire (3600 s).

Answer: stock Syncthing downloads armed content whenever it runs. Per-file resting lines stop new files without re-hashing, but not updates of files already on the device. Large `pullerDelayS` and `pullerPauseS` together held every case tested here for up to 15 s. That is **not a proven workaround**: the start-up pull can bypass it, every folder config change restarts the folder and pulls, the delays are finite and expire, and in the meantime the "needed" count is wrong and the computer shows the phone out of sync.

### c) Deselect order — confirmed, with one generator change

Completion checks below are made after the computer holds the phone's latest index: the computer's view of the phone's sequence equals the phone's own sequence, and the remote state is `valid`. They are asserted, not just logged.

- `TestDeselectIgnoreScanDelete`, with a nested file `dir/a.bin`: while selected, the computer shows the phone at 100% with 0 needed items, and `dir` is a normal pulled directory row. Writing ignores, waiting for the scan, then deleting the file and its empty parent leaves zero `receiveOnlyChanged*` items and an empty `/rest/db/localchanged`. The computer showed the phone at 100% with 0 needed items about 0.3 s later, and still did after a rescan and after a restart.
- `TestSelectStopDeselect`: a 32 MiB download was interrupted by a stop and then deselected, once with Syncthing running (REST) and once stopped (`.stignore` written directly, partial file deleted). Both runs ended clean, also after a further restart; the computer showed 100% after 0.3 to 1.0 s.
- Wrong orders (`TestDeselectWrongOrders`). Delete, then ignore, then scan: the computer shows the phone at 100% with 0 needed items, also after a restart. Delete, then scan, then ignore and scan: the computer shows the phone at 0% with 1 needed item, and still does after a phone restart; this was observed within the test (seconds) and one restart, so it is not proven permanent. The phone's own counters look clean in this case, so the app could not detect it. The order "ignore, scan, delete" must be enforced, and no scan may run between deleting and writing ignores.
- **New problem with nested selections** (`TestNestedSelectionWithoutParentLines`). With only `!/dir/a.bin` and `*`, the puller creates `dir` as a local directory. That row carries the phone's own version and the receive-only flag, so `receiveOnlyChangedDirectories=1`. The computer then counts one needed item for the phone: completion is 100% by bytes. Its web GUI turns "no bytes needed but items needed" into 95% (read in source, `recalcCompletion` in the GUI; the GUI was not run). This survived deselecting, rescanning and restarting within the test. Un-ignoring each ancestor directory without its other children fixes it: `/dir/**` then `!/dir`, deepest first, after the include lines. With those lines (`WhitelistLines` in `engine/ignorepattern.go`) all the clean results above hold; `TestDeselectIgnoreScanDelete` is the positive case and `TestNestedSelectionWithoutParentLines` the negative one.

### d) "Sync finished" rule — reliable when it waits for the scan

`TestSyncFinishedRule`, 7 iterations by default (`ENGINE_FINISH_RUNS`), each selecting one more 512 KiB file. The rule was: after the ignore change, wait for a completed scan, then for folder `idle`, `needTotalItems == 0`, `pullErrors == 0` and no `/rest/folder/errors`. It had no false positives, whether the scan was waited for with `POST /rest/db/scan` (it returns when the scan is done) or with the `StateChanged scanning -> idle` event. A naive check right after `POST /rest/db/ignores` also had 0 false positives in 7 runs, but nothing in the code guarantees that. Typical sequence after setting ignores:

`StateChanged(idle->scan-waiting), StateChanged(scan-waiting->scanning), LocalIndexUpdated([f6.bin]), StateChanged(scanning->idle), StateChanged(idle->sync-waiting), StateChanged(sync-waiting->idle), StateChanged(idle->sync-preparing), ItemStarted(update f6.bin), StateChanged(sync-preparing->syncing), ItemFinished(update f6.bin), LocalIndexUpdated([f6.bin]), StateChanged(syncing->sync-preparing), StateChanged(sync-preparing->idle), FolderSummary(idle need=0), FolderCompletion(100%)`

Between the post-change scan and the pull pass, `needTotalItems` can include ignored files; one run showed `need=8` with one file selected. Download totals should therefore come from the app's own selection, not from `needBytes` read in that window. In the "scan call" iterations each ignore POST was followed by two scans: the one the POST schedules and the explicit one.

### e) Start/stop cycles — no leaks; stop can take 10 s

- `TestStartStopCycles`, connected to the computer, a 1 MiB selected file, stop right after connect and sync, `protocol.CloseTimeout` set to 1 s. Results from isolated runs of this test alone (`-test.run 'TestStartStopCycles$'`, a fresh process each time; RSS measured after GC and `debug.FreeOSMemory`) for 20, 50 and 150 cycles: goroutines +0 each time (6 to 7 stopped, 39 to 40 running), file descriptors +0 (8 stopped, 21 to 23 running), Go heap in use +0.1 to +0.2 MiB, RSS +3.3, +5.9 and +8.7 MiB (from about 40 to 45 MiB). RSS growth slows (about 0.03 MiB per cycle between 50 and 150 cycles) but was not shown to stop; on the host it is small, and it must be measured again on a device. Every stop took 1.02 to 1.13 s, the lowered close timeout (see below); start to REST ready took 17 to 29 ms median (153 ms max); start to connected took about 52 ms median. RSS is measured from `/proc/self/statm` because SQLite is the C library (`mattn/go-sqlite3` in cgo builds; gomobile builds use cgo) and its memory is invisible to the Go heap. The test asserts goroutines +2 or less, descriptors +2 or less, Go heap +16 MiB or less, RSS +32 MiB or less, and every stop under 3 s. `App.Stop` closes the database itself (`App.wait` calls `sdb.Close`; read in source, and consistent with no descriptor growth), which settles the research's open question.
- **Stop latency** (`TestStopRightAfterConnect`). A stop right after a new connection took 10.0 s in every run; 2 s after connect it took about 30 ms. The mechanism is read in source and matches a goroutine dump taken during a slow stop: a connection is started by the model's promotion timer about 1 s after it is added, although REST already reports it as connected, and `Close` waits `protocol.CloseTimeout` (10 s) for a writer loop that is not running. `protocol.CloseTimeout` is an exported variable; with it set to 1 s the same stop took 1.03 s, without patching Syncthing. The test asserts the bounded cases (under 2 s and 3 s) and only logs the default-timeout case, because hitting the window is timing-dependent.
- `TestStopMidTransfer`: a stop during a 32 MiB download took under 0.1 s (asserted under 3 s), and the next start finished the file with the right content.

### f) Partial files — survive stop and kill and are reused

`TestPartialFilesSurviveStopAndKill`, with the phone as a child process and the computer limited to 4 MiB/s. After a clean stop (SIGTERM → `App.Stop`), the `.syncthing.big.bin.tmp` file was kept, with the same allocated size as just before the stop (8.0 to 8.1 MiB). The next session received 8.1 to 8.2 MiB and then got SIGKILL; the partial kept its pre-kill size and had grown by the same amount, so nothing was fetched twice. The resume after the kill received what was still missing (for example 15.88 MiB for 15.87 MiB), finished correctly, and left no partial file. The test asserts no lost progress at either interruption and at most 1 MiB of extra transfer. Three consecutive runs passed. The database reopened without problems after SIGKILL. `keepTemporariesH` defaults to 24 hours (`TestKeepTemporariesDefault`). Expiry itself was not tested.

### g) Scan time with many ignore lines

`TestScanTimeWithManyIgnoreLines`. Include lines match no file, which is the worst case: every row is tested against every line before `*`. "Patterns" counts the lines including the generator's ancestor lines. "Scheduled scan" is the one scan that `POST /rest/db/ignores` schedules, timed from its `StateChanged` events; "POST to synced" runs from the POST until the finished rule holds; "Unchanged rescan" is one explicit scan, timed the same way.

| Remote files | Lines | Scheduled scan | POST to synced | Unchanged rescan |
|---|---|---|---|---|
| 2,000 | `*` only | 6 to 13 ms | 21 to 26 ms | 5 to 12 ms |
| 2,000 | 100 | 7 to 25 ms | 15 to 41 ms | 7 to 29 ms |
| 2,000 | 1,000 | 32 to 83 ms | 46 to 104 ms | 31 to 42 ms |
| 100,000 | `*` only | 0.82 s | 0.83 s | 0.82 s |
| 100,000 | 100 | 1.06 s | 1.07 s | 0.93 s |
| 100,000 | 1,000 | 2.63 s | 2.64 s | 2.51 s |
| 100,000 | 5,000 | 8.96 s | 9.01 s | 7.68 s |

The 2,000-file rows are the range over three default runs. The 100k rows come from one heavy run (`ENGINE_SCAN_FILES=100000 ENGINE_SCAN_LINES=100,1000,5000`, exit 0, 516 s). With the scan isolated, the scan that an ignore change schedules costs about the same as an unchanged rescan. An earlier version of this report showed the first figure twice as large because it timed two scans together. Scan cost grows roughly linearly with rows × lines, as the research predicted. Every ignore change costs one such scan, and every Sync after an ignore change waits for it, so the desktop figure at 5,000 lines will be several times longer on a phone. That argues for directory lines over per-file lines (option B), and for writing ignores once per user action. 20,000 lines were not run.

Database size is the bigger surprise. With nothing selected, the phone's home (SQLite database plus WAL, no maintenance run) was about 3.3 MiB for 2,000 remote files and 834 MiB for 100,000 one-byte remote files. The first index of 100,000 files, including the computer creating and hashing them, took 8 min 8 s. The split between database and WAL, and the effect of `blockIndexing` and of `StartMaintenance`, were not measured.

### h) Special file names — work with the generator

- Unit tests against Syncthing's own matcher (`TestIncludeLineMatchesExactlyOneName`, `TestExcludeLineCarvesOutOfSelectedFolder`, `TestWhitelistLines`, and others) cover `\ * ? [ ] { }`, leading `#`, `#include`, `#escape=`, `!`, `(?i)`, `(?d)`, a comma, `**`, trailing spaces, tabs and U+0085, carriage returns in the middle and at the end, a whitespace-only name, a leading space, a space before `/`, NFC and NFD, and non-Latin scripts. Each name is selected or excluded without matching its decoys.
- End to end (`TestSpecialFileNamesSelectDeselect`): 47 targets (23 names, each at the top level and inside a directory with the same special name, plus one NFD-named file) were each selected alone and downloaded with no other file, then deselected with no locally changed items. Afterwards the computer showed the phone at 100%.
- Escaping rule: backslash-escape `\ * ? [ ] { }`, always root the line with `/` (so `#`, `!` and `(?i)` in names are never read as syntax), and write trailing whitespace as one-character classes such as `[ ]`, because Syncthing trims every line. "Whitespace" is Go's `unicode.IsSpace` set, which `strings.TrimSpace` uses; a Kotlin port must use that set too (`Char.isWhitespace` misses U+0085). Names with a newline cannot be written; the helper rejects them. A selected path inside an excluded path is rejected with an error rather than silently dropped.
- NFD: a Linux computer with `autoNormalize` (the default) renamed an NFD file to NFC on its disk and indexed it as NFC. Syncthing also NFC-normalizes the patterns, so NFC and NFD lines both match. A real Mac peer was not tested.

## Against the design documents

| Statement | Source | Verdict |
|---|---|---|
| "App visible: Syncthing runs ... Nothing is downloaded" | flows.md | Contradicted by b): armed content downloads within about 1.3 s. Large `pullerDelayS` plus `pullerPauseS` held the tested cases but is not a proven workaround (see b)). |
| "The sync network setting limits only the Sync button" | flows.md | Contradicted for the same reason. |
| Flow 6 "Nothing else that is selected is downloaded" | flows.md | Contradicted whenever other files are armed. |
| Flow 4 "copy is deleted at once" | flows.md | Holds only after the ignore change has been scanned (about one scan, milliseconds to seconds); see c). |
| "App leaves the screen with no sync running: Syncthing stops" | flows.md | Workable. A stop costs 10 s if it lands within about 1 s of a connection, unless `protocol.CloseTimeout` is lowered. The computer learns the phone's state 0.25 to 1 s after the phone is synced, so a very early stop leaves the computer behind until the next session. |
| Selective sync as a whitelist; app deletes local copies | decisions.md (proposed) | Confirmed, with the ancestor-line change in c). |
| Pause and stop end the job and stop Syncthing; partial downloads kept "as long as Syncthing retains them" | decisions.md (proposed) | Confirmed for stop and kill: partials survive with no lost progress and are reused. Retention is the 24 h, scan-driven `keepTemporariesH`, which was not exercised here. |
| In-process library, many start/stop cycles | decisions.md, build-strategy.md | Confirmed on the host: no goroutine, descriptor or Go-heap growth over 50 cycles, and RSS as in e). The 10 s close wait is new. |
| "Parents need no un-ignoring because the puller recreates missing parent directories" | build-strategy.md | Contradicted: the recreated parent becomes a receive-only-changed row, and the computer kept counting it as needed through deselect and a restart. |
| Option B "needs a no re-hash check" | build-strategy.md | Checked: no re-hash. Option C does re-hash (190 to 270 ms for 64 MiB here). |
| A large `pullerDelayS` is "worth one prototype check" | build-strategy.md | Checked; see b). Alone it does not hold the start-up retry; together with `pullerPauseS` it held the tested cases, but it is not a proven workaround. |
| Deselect order "write ignores, wait for the scan, then delete" | build-strategy.md | Confirmed. The reverse order with a scan in between left the computer showing the phone at 0% through the test and one restart. |
| "Do not treat `receiveOnlyChanged*` counters as errors" | build-strategy.md | Confirmed: bare nested lines produce `receiveOnlyChangedDirectories=1` in normal use. With ancestor lines, no such items appeared in these tests. |
| Trailing-whitespace character-class workaround, NFD matching | build-strategy.md [U] | Confirmed on Linux. |
| `App.Stop` closing the database | build-strategy.md [U] | Answered: `App.wait` closes it. |
| Delete-retention default unresolved | build-strategy.md | `cmd/syncthing` at the pin passes 10920 h (15 months); read in source, not tested. |
| A paused folder rejects index messages | build-strategy.md [V] | Confirmed; also no REST file list while paused. |

## Other facts for the bridge

- Build with `-tags noassets`. The module has no generated GUI assets.
- At Info log level, `App.Start` runs a 450 ms CPU hashing benchmark on every start. The harness logs at Warn and skips it.
- `locations` is process-global, so only one in-process instance can run at a time. This is why the computer runs as a child process.
- Connections are reported as connected about 1 s before the connection is started and index exchange begins (read in source; consistent with the stop measurements).
- Lower `protocol.CloseTimeout` (for example to 1 s) before starting the app instance.

## Not verified

- Anything on Android: storage, the Go runtime as a shared library, memory, TLS on the GUI, a UNIX-socket GUI.
- A macOS peer, case-only name collisions, names longer than 255 bytes.
- A non-NFC name in the index, from a peer with `autoNormalize=false`: patterns are NFC-normalized and names from the wire are not normalized before matching (read in source), so such a file probably cannot be selected. The app should treat it as an error state. Not tested.
- The start-up race in b) with a slow initial scan; behaviour after more than one hour with `pullerDelayS=3600`.
- `keepTemporariesH` expiry; resume cost for multi-gigabyte partials; folder pause (as opposed to stop) during a download.
- 20,000 ignore lines; pull time at 100k files; what makes up the large database (WAL, `blockIndexing`, maintenance); ignore lines that match existing files.
