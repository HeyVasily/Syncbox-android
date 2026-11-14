//go:build noassets

package engine

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/syncthing/syncthing/lib/config"
)

// The download gate: folder minDiskFree set out of reach makes the puller
// fail every file on its free-space check, while index exchange, ignored rows
// and deletions go on. See docs/research/sync-download-gate.md.
var (
	gateClosedSize = config.Size{Value: 1000000, Unit: "TB"}
	gateOpenSize   = config.Size{Value: 1, Unit: "%"}
)

func (n *node) setGate(open bool) {
	n.t.Helper()
	s := gateClosedSize
	if open {
		s = gateOpenSize
	}
	n.patchFolder(map[string]any{"minDiskFree": map[string]any{"value": s.Value, "unit": s.Unit}})
}

// writeGateClosed sets the gate in config.xml while the node is stopped, as
// the bridge must do before every start outside the download job.
func (n *node) writeGateClosed() {
	n.t.Helper()
	path := filepath.Join(n.home, "config.xml")
	fd, err := os.Open(path)
	if err != nil {
		n.t.Fatal(err)
	}
	cfg, _, err := config.ReadXML(fd, n.id)
	fd.Close()
	if err != nil {
		n.t.Fatal(err)
	}
	for i := range cfg.Folders {
		cfg.Folders[i].MinDiskFree = gateClosedSize
	}
	n.writeConfig(cfg)
}

func closedGate(_ *config.Configuration, f *config.FolderConfiguration) {
	f.MinDiskFree = gateClosedSize
}

// heldErrors returns the folder errors that are the gate's "insufficient
// space" failures, by path.
func heldErrors(n *node) map[string]bool {
	n.t.Helper()
	var fe folderErrors
	n.get("/rest/folder/errors?folder="+folderID, &fe)
	held := map[string]bool{}
	for _, e := range fe.Errors {
		if strings.Contains(e.Error, "insufficient space") {
			held[e.Path] = true
		}
	}
	return held
}

// waitHeld waits until exactly the given files are reported as held and the
// folder is idle, which is what a finished held pull pass looks like.
func waitHeld(t *testing.T, n *node, names ...string) folderStatus {
	t.Helper()
	var st folderStatus
	waitFor(t, waitTimeout, "held pull pass", func() bool {
		if n.do("GET", "/rest/db/status?folder="+folderID, nil, &st) != nil || st.State != "idle" {
			return false
		}
		h := heldErrors(n)
		if len(h) != len(names) || st.PullErrors != len(names) {
			return false
		}
		for _, name := range names {
			if !h[name] {
				return false
			}
		}
		return true
	})
	return st
}

// assertHeld checks for d that cond stays true and no partial file appears.
func assertHeld(t *testing.T, n *node, what string, d time.Duration, cond func() bool) {
	t.Helper()
	if !holds(d, func() bool { return cond() && len(n.tempFiles()) == 0 }) {
		t.Fatalf("%s: downloaded (or partial file created) with the gate closed", what)
	}
	result(t, "%s: held for %v, no partial file", what, d)
}

// TestDownloadGate checks that a closed gate holds every download case while
// the index stays fresh, what the held state looks like, and that opening
// the gate downloads promptly.
func TestDownloadGate(t *testing.T) {
	files := map[string][]byte{
		"kept.bin":       randomData(1, 100_000),
		"Movies/a.bin":   randomData(2, 100_000),
		"other/skip.bin": randomData(3, 100_000),
		"gone.bin":       randomData(4, 50_000),
	}
	p := newPair(t, pairOpts{files: files, phoneCfg: closedGate})

	// Under "*" with the gate closed the pull pass still writes the ignored
	// rows: idle, nothing needed, computer at 100%.
	waitFor(t, waitTimeout, "phone synced under \"*\"", p.phone.synced)
	waitPeerSeesPhoneComplete(t, p)
	result(t, "gate closed under \"*\": phone synced, computer sees the phone at 100%%")

	// Armed selection with the gate closed.
	p.phone.setIgnores(includeLines(t, "kept.bin", "Movies", "gone.bin"))
	p.phone.scan()
	st := waitHeld(t, p.phone, "kept.bin", "Movies/a.bin", "gone.bin")
	assertHeld(t, p.phone, "armed selection", 5*time.Second, func() bool {
		return !p.phone.has("kept.bin") && !p.phone.has("Movies/a.bin") && !p.phone.has("gone.bin")
	})
	c, _ := waitPeerHasPhoneIndex(t, p)
	result(t, "held rows: state=%q needTotalItems=%d needBytes=%d pullErrors=%d, folder errors say \"insufficient space\"; computer sees the phone at %.1f%%",
		st.State, st.NeedTotalItems, st.NeedBytes, st.PullErrors, c.Completion)
	if st.NeedBytes != 250_000 || st.NeedTotalItems != 3 {
		t.Errorf("needBytes=%d needTotalItems=%d, want exactly the selected 250000 bytes in 3 files", st.NeedBytes, st.NeedTotalItems)
	}
	if c.Completion >= 100 {
		t.Errorf("computer sees the phone at 100%% while selected files are pending")
	}

	// Open: the selection downloads promptly.
	t0 := time.Now()
	p.phone.setGate(true)
	waitFor(t, 30*time.Second, "synced after opening", func() bool {
		return p.phone.synced() && bytes.Equal(p.phone.content("kept.bin"), files["kept.bin"]) && p.phone.has("Movies/a.bin")
	})
	result(t, "gate opened: selection downloaded and synced %v after the PATCH", time.Since(t0).Round(time.Millisecond))
	waitPeerSeesPhoneComplete(t, p)

	// Closing again (a folder restart) causes no index update: compared
	// after the restart's scan has completed. Hashing itself is not measured.
	seq := p.phone.status().Sequence
	since := p.phone.lastEventID()
	p.phone.setGate(false)
	waitScanEvent(t, p.phone, since)
	waitFor(t, waitTimeout, "synced after closing", p.phone.synced)
	if got := p.phone.status().Sequence; got != seq {
		t.Errorf("closing the gate changed the local sequence %d -> %d (index update)", seq, got)
	}
	result(t, "gate closed again: after the restart scan the local sequence is unchanged at %d (no index update)", seq)

	// While running: a new version of an on-device file and a new file in a
	// selected directory are held; an unselected new file is ignored.
	v2 := randomData(5, 100_000)
	p.peerUpdate(map[string][]byte{"kept.bin": v2, "Movies/b.bin": randomData(6, 100_000), "other/new.bin": randomData(7, 10)})
	st = waitHeld(t, p.phone, "kept.bin", "Movies/b.bin")
	pending := func() bool {
		return bytes.Equal(p.phone.content("kept.bin"), files["kept.bin"]) && !p.phone.has("Movies/b.bin")
	}
	assertHeld(t, p.phone, "new version and new file in a selected directory", 5*time.Second, pending)
	if st.NeedBytes != 200_000 {
		t.Errorf("needBytes=%d, want the pending 200000", st.NeedBytes)
	}

	// A remote deletion of an on-device file is applied with the gate closed.
	if err := os.Remove(p.peer.path("gone.bin")); err != nil {
		t.Fatal(err)
	}
	p.peer.scan()
	waitFor(t, 30*time.Second, "remote deletion applied", func() bool { return !p.phone.has("gone.bin") })
	result(t, "gate closed: a remote deletion of an on-device file was applied")

	// Restarts with the update already known, in both start orders.
	restartPhoneBeforePeer(t, p, true)
	waitHeld(t, p.phone, "kept.bin", "Movies/b.bin")
	assertHeld(t, p.phone, "restart, phone started before the computer", 5*time.Second, pending)
	p.phone.stop()
	p.startPhone(false)
	waitFor(t, waitTimeout, "connected", func() bool { return p.phone.connectedTo(p.peer) })
	waitHeld(t, p.phone, "kept.bin", "Movies/b.bin")
	assertHeld(t, p.phone, "restart, computer already running", 5*time.Second, pending)

	// An unrelated folder config change restarts the folder: still held.
	p.phone.patchFolder(map[string]any{"pullerPauseS": 30})
	waitHeld(t, p.phone, "kept.bin", "Movies/b.bin")
	assertHeld(t, p.phone, "unrelated folder config change", 5*time.Second, pending)

	// Open: everything pending arrives, nothing unselected.
	t0 = time.Now()
	p.phone.setGate(true)
	waitFor(t, 30*time.Second, "synced after opening", func() bool {
		return p.phone.synced() && bytes.Equal(p.phone.content("kept.bin"), v2) && p.phone.has("Movies/b.bin")
	})
	result(t, "gate opened: pending update and new file synced %v after the PATCH", time.Since(t0).Round(time.Millisecond))
	if p.phone.has("other/new.bin") || p.phone.has("other/skip.bin") {
		t.Error("unselected files downloaded")
	}
	waitPeerSeesPhoneComplete(t, p)
}

// TestDownloadGateMidTransfer measures what closing the gate during a
// download does, and shows that stopping Syncthing ends the transfer. The
// file is 64 MiB so that more remains after the close than the puller's
// default 32 MiB of outstanding requests, which bounds the tail.
func TestDownloadGateMidTransfer(t *testing.T) {
	const size = 64 << 20
	data := randomData(8, size)
	slow := slowPeer(4096)
	p := newPair(t, pairOpts{files: map[string][]byte{"big.bin": data}, peerCfg: slow, phoneCfg: closedGate})
	p.phone.setIgnores(includeLines(t, "big.bin"))
	p.phone.scan()
	waitHeld(t, p.phone, "big.bin")

	// Closing the gate mid-transfer: the partial stops growing at once, but
	// already-requested blocks keep arriving and are discarded.
	p.phone.setGate(true)
	waitPartial(t, p.phone, 8<<20)
	t0 := time.Now()
	p.phone.setGate(false)
	patch := time.Since(t0)
	inClose := p.phone.bytesIn(p.peer)
	time.Sleep(time.Second)
	partial1 := partialSize(t, p.phone)
	// The tail ends when the received byte count stops changing for 2 s.
	last, stable := p.phone.bytesIn(p.peer), 0
	waitFor(t, 60*time.Second, "tail ends", func() bool {
		time.Sleep(500 * time.Millisecond)
		now := p.phone.bytesIn(p.peer)
		if now == last {
			stable++
		} else {
			stable, last = 0, now
		}
		return stable >= 4
	})
	tail := last - inClose
	partial2 := partialSize(t, p.phone)
	result(t, "close mid-transfer: PATCH %v; partial %.1f MiB 1 s after close and %.1f MiB when the tail ended; tail %.1f MiB received and discarded",
		patch.Round(time.Millisecond), mib(uint64(partial1)), mib(uint64(partial2)), mib(uint64(tail)))
	if partial2 != partial1 || p.phone.has("big.bin") {
		t.Errorf("partial kept growing after closing the gate: %d -> %d", partial1, partial2)
	}
	if tail > 34<<20 {
		t.Errorf("tail %.1f MiB exceeds pullerMaxPendingKiB (32 MiB) plus slack", mib(uint64(tail)))
	}

	// Stopping Syncthing ends a transfer with no tail: stop mid-transfer,
	// write the gate closed, start again; nothing more arrives.
	p.phone.setGate(true)
	waitPartial(t, p.phone, partial2+8<<20)
	p.phone.stop()
	partial3 := partialSize(t, p.phone)
	p.phone.writeGateClosed()
	p.startPhone(false)
	// Counters belong to the new connection, so they count from reconnect.
	waitFor(t, waitTimeout, "connected", func() bool { return p.phone.connectedTo(p.peer) })
	waitHeld(t, p.phone, "big.bin")
	time.Sleep(5 * time.Second)
	held := p.phone.bytesIn(p.peer)
	result(t, "stop mid-transfer, restart with the gate closed: partial kept at %.1f MiB; %d bytes received from reconnect through the held pass and 5 s more",
		mib(uint64(partial3)), held)
	if held > 1<<20 || partialSize(t, p.phone) != partial3 {
		t.Errorf("data arrived after stop and restart with the gate closed: %d bytes", held)
	}

	// Open again: the file finishes from the partial, fetching only what is
	// missing.
	const slack = 1 << 20
	p.phone.setGate(true)
	waitFor(t, 3*waitTimeout, "download finished", func() bool { return bytes.Equal(p.phone.content("big.bin"), data) })
	waitFor(t, waitTimeout, "synced", p.phone.synced)
	got, missing := p.phone.bytesIn(p.peer), size-partial3
	result(t, "reopened: finished; %.2f MiB received since reconnect for the %.2f MiB missing", mib(uint64(got)), mib(uint64(missing)))
	if got > missing+slack {
		t.Errorf("received %.2f MiB for %.2f MiB missing: the partial was not reused", mib(uint64(got)), mib(uint64(missing)))
	}
}
