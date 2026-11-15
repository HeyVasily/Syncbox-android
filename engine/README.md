# engine

A verification harness, not the shipped bridge. It embeds unmodified upstream
Syncthing through `lib/syncthing`, as the app will, and checks the Syncthing
behaviour the design relies on with host-side Go tests on `linux/amd64`. No
emulator and no network beyond `127.0.0.1` are needed. The findings are in
[`docs/research/engine-host-findings.md`](../docs/research/engine-host-findings.md).

Non-test code: the ignore-file generator in `ignorepattern.go`
(`WhitelistLines`, `IncludeLine`, `ExcludeLine`), which the app's own
generator should mirror, and `bridge/`, the app's gomobile bridge.

## bridge

`bridge/` is the only Go code the app ships: `Start(home, downloadJob)`,
`Stop`, `Running`, `Failure`, `APIAddress`, `APIKey` and `DeviceID`, with
strings and bools only, so no Syncthing type crosses into Kotlin. Start forces
the REST API on (random 127.0.0.1 port, new API key per start) with GUI
auth on (user `avoska`, a random password nobody keeps, so other apps on
the phone get 401 on `/rest` and the API key is the only way in), turns off
usage and crash reporting and upgrades, lowers `protocol.CloseTimeout` to
1 s, and, unless a download job is starting it, writes the download gate
closed (`minDiskFree` 1000000 TB) into every folder and the folder defaults
before Syncthing starts. Exported functions turn panics into errors; a panic
in one of Syncthing's goroutines still ends the process. `dev/build-bridge.sh`
builds it into an AAR; `bridge_test.go` (run first by `dev/test-engine.sh`)
starts and stops it twice and checks that a folder left open is closed again,
that a second Start fails while one runs, and that requests without the key
are rejected on `/rest`, also with a CSRF cookie and header from `/`.
gomobile and gobind are pinned by the `tool` directives in `go.mod`; adding
them added `golang.org/x/{mobile,mod,sync,tools}` as indirect requirements.
`dev/build-bridge.sh` builds with exactly the Go version `go.mod` names.

## Running

```sh
bash dev/test-engine.sh                         # default suite, a few minutes
bash dev/test-engine.sh -test.run TestPaused    # one test
```

The script vets the module, builds the test binary into `.build/engine/` and,
where `unshare -rn` and `ip` work, runs it in a network namespace that has only
a loopback interface. Otherwise it prints a warning and runs without one; the
tests then rely on their loopback-only configuration. Inside the namespace
Syncthing warns that it runs as a privileged user; that is the namespace's
uid 0 and is harmless. Output is also written to `.build/engine/test.log`.
Measurements are logged as lines starting with `RESULT`.

Heavier runs:

| Variable | Default | Meaning |
|---|---|---|
| `ENGINE_CYCLES` | 20 | start/stop cycles in `TestStartStopCycles` |
| `ENGINE_SCAN_FILES` | 2000 | remote files in `TestScanTimeWithManyIgnoreLines` |
| `ENGINE_SCAN_LINES` | `100,1000` | ignore line counts, comma-separated |
| `ENGINE_FINISH_RUNS` | 7 | iterations in `TestSyncFinishedRule` |
| `ENGINE_LOG` | unset | `1` sends Syncthing's debug log to stderr |
| `ENGINE_NETNS` | 1 | `0` runs without the network namespace |

Example: `ENGINE_SCAN_FILES=100000 ENGINE_SCAN_LINES=100,1000,5000 bash dev/test-engine.sh -test.run TestScanTime`.

## How it works

- The "phone" is Syncthing started in the test process (`startEmbedded` in
  `harness_test.go`). It is driven over the REST API and `/rest/events`,
  like the app.
- The "computer" is the same test binary run again as a child process in
  helper mode (`TestMain`), so it is Syncthing from the same pinned module. The
  phone also runs as a child process when a test needs to kill it.
- Both use temporary homes, static `tcp://127.0.0.1:<port>` addresses, and
  have global and local discovery, relays, NAT, usage and crash reporting,
  upgrades and the browser turned off. The phone folder is receive-only and is
  created paused, set to `*`, then unpaused.

## Pinned versions

- Syncthing v2.1.7, commit `d6d0181f7d466381c4b39ec8fd944c364f939b32`, as the
  pseudo-version `v1.30.0-rc.1.0.20261020195438-d6d0181f7d46`; the module
  path has no `/v2`, so the tag cannot be required directly.
- Go 1.26.2, which this Syncthing requires. With an older `go` installed,
  `GOTOOLCHAIN=auto` (the default) downloads it.
- Syncthing's two `replace` directives are copied into `go.mod`. The
  `jackpal/gateway` one targets v1.1.1 while Syncthing requires v1.2.0, so it
  has no effect upstream either; it is kept identical on purpose.
- Build tag `noassets`: the module does not contain the generated GUI assets.
  The app never serves the GUI, so this is what it needs too. Test files that
  use Syncthing carry `//go:build noassets`; without the tag only the
  generator's unit tests build.
