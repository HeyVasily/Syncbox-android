//go:build noassets

package engine

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/url"
	"testing"
	"time"

	"github.com/syncthing/syncthing/lib/config"
)

// includeLines is the app's ignore file for a selection of paths.
func includeLines(t *testing.T, paths ...string) []string {
	t.Helper()
	lines, err := WhitelistLines(paths, nil)
	if err != nil {
		t.Fatal(err)
	}
	return lines
}

// TestOpenAppDownloadsUpdatesOfSelectedFiles answers part of question b):
// with Syncthing merely running, a new version of a whitelisted file on the
// device downloads without any Sync action.
func TestOpenAppDownloadsUpdatesOfSelectedFiles(t *testing.T) {
	p := newPair(t, pairOpts{files: map[string][]byte{"kept.bin": randomData(1, 200_000)}})
	p.phone.applyIgnores(includeLines(t, "kept.bin"))

	v2 := randomData(2, 200_000)
	t0 := time.Now()
	p.peerUpdate(map[string][]byte{"kept.bin": v2})
	waitFor(t, 30*time.Second, "new version downloaded without Sync", func() bool {
		return bytes.Equal(p.phone.content("kept.bin"), v2)
	})
	result(t, "new version of an on-device file downloaded %v after the computer's scan, with no Sync action", time.Since(t0).Round(time.Millisecond))
}

// TestOpenAppDownloadsNewFilesInSelectedDirectory answers part of question
// b): a directory line pulls new files under it automatically.
func TestOpenAppDownloadsNewFilesInSelectedDirectory(t *testing.T) {
	p := newPair(t, pairOpts{files: map[string][]byte{"Movies/m1.bin": randomData(1, 100_000)}})
	p.phone.applyIgnores(includeLines(t, "Movies"))

	t0 := time.Now()
	p.peerUpdate(map[string][]byte{"Movies/m2.bin": randomData(2, 100_000)})
	waitFor(t, 30*time.Second, "new file in selected directory downloaded", func() bool {
		return p.phone.has("Movies/m2.bin")
	})
	result(t, "new file under a selected directory downloaded %v after the computer's scan, with no Sync action", time.Since(t0).Round(time.Millisecond))
}

// TestRestingPerFileWhitelist answers the option B part of question b):
// rewriting a directory line to per-file lines for the files on disk stops
// new files from arriving, does not re-hash the files on disk, but still
// lets new versions of those files download.
func TestRestingPerFileWhitelist(t *testing.T) {
	const size = 64 << 20
	p := newPair(t, pairOpts{files: map[string][]byte{
		"Movies/big.bin":   randomData(1, size),
		"Movies/small.bin": randomData(2, 1000),
	}})
	p.phone.applyIgnores(includeLines(t, "Movies"))

	before, _ := p.phone.file("Movies/big.bin")
	t0 := time.Now()
	p.phone.scan()
	noopScan := time.Since(t0)

	since := p.phone.lastEventID()
	p.phone.setIgnores(includeLines(t, "Movies/big.bin", "Movies/small.bin"))
	t0 = time.Now()
	p.phone.scan()
	rewriteScan := time.Since(t0)
	waitFor(t, waitTimeout, "synced", p.phone.synced)
	after, _ := p.phone.file("Movies/big.bin")
	touched := localIndexedNames(p.phone, since)
	result(t, "dir line -> per-file lines: scan %v (no-op scan %v); big.bin local sequence %d -> %d; LocalIndexUpdated names %v",
		rewriteScan.Round(time.Millisecond), noopScan.Round(time.Millisecond), before.Local.Sequence, after.Local.Sequence, touched)
	if after.Local.Sequence != before.Local.Sequence || touched["Movies/big.bin"] {
		t.Errorf("rewriting the directory line to per-file lines re-indexed big.bin")
	}

	p.peerUpdate(map[string][]byte{"Movies/new.bin": randomData(3, 1000)})
	waitKnown(t, p.phone, "Movies/new.bin")
	if !holds(5*time.Second, func() bool { return !p.phone.has("Movies/new.bin") }) {
		t.Fatal("new file downloaded under a per-file whitelist")
	}
	result(t, "per-file whitelist: new file in the directory known to the phone but not downloaded within 5s")

	v2 := randomData(4, 1000)
	p.peerUpdate(map[string][]byte{"Movies/small.bin": v2})
	waitFor(t, 30*time.Second, "updated on-device file downloads", func() bool {
		return bytes.Equal(p.phone.content("Movies/small.bin"), v2)
	})
	result(t, "per-file whitelist: new version of an on-device file still downloads automatically")
}

// TestIgnoreAllRehashes checks option C: resting on "*" alone with files on
// disk turns their rows into ignored rows, and arming them again re-hashes.
func TestIgnoreAllRehashes(t *testing.T) {
	const size = 64 << 20
	p := newPair(t, pairOpts{files: map[string][]byte{"big.bin": randomData(1, size)}})
	arm := includeLines(t, "big.bin")
	p.phone.applyIgnores(arm)
	armed, _ := p.phone.file("big.bin")

	p.phone.applyIgnores([]string{"*"})
	rest, _ := p.phone.file("big.bin")

	since := p.phone.lastEventID()
	p.phone.setIgnores(arm)
	t0 := time.Now()
	p.phone.scan()
	rearmScan := time.Since(t0)
	waitFor(t, waitTimeout, "synced", p.phone.synced)
	rearmed, _ := p.phone.file("big.bin")
	reindexed := localIndexedNames(p.phone, since)["big.bin"]
	st := p.phone.status()
	result(t, "\"*\" with file on disk: local row ignored=%v size=%d seq %d -> %d; re-arm scan %v, seq -> %d size=%d, re-indexed=%v, receiveOnlyTotalItems=%d",
		rest.Local.Ignored, rest.Local.Size, armed.Local.Sequence, rest.Local.Sequence, rearmScan.Round(time.Millisecond),
		rearmed.Local.Sequence, rearmed.Local.Size, reindexed, st.ReceiveOnlyTotalItems)
	if !rest.Local.Ignored || rest.Local.Size != 0 {
		t.Errorf("expected the on-device file's row to become an ignored row without content, got %+v", rest.Local)
	}
	if !p.phone.has("big.bin") {
		t.Errorf("file was deleted by ignoring it")
	}
	// The ignored row kept no size or block list, so re-arming must read and
	// hash the file again: the scan re-indexes it as a new local row.
	if !reindexed || rearmed.Local.Sequence <= rest.Local.Sequence || rearmed.Local.Ignored || rearmed.Local.Size != size {
		t.Errorf("expected re-arming to re-index (re-hash) big.bin: re-indexed=%v row %+v", reindexed, rearmed.Local)
	}
	if st.ReceiveOnlyTotalItems != 0 || len(p.phone.localChanged()) != 0 {
		t.Errorf("re-arming left locally changed items: %d %v", st.ReceiveOnlyTotalItems, p.phone.localChanged())
	}
}

// waitKnown waits until the phone's index has the computer's current version
// of name, which is what makes a "download held" check meaningful.
func waitKnown(t *testing.T, n *node, name string) {
	t.Helper()
	waitFor(t, waitTimeout, n.name+" knows "+name, func() bool {
		var f dbFile
		return n.do("GET", "/rest/db/file?folder="+folderID+"&file="+url.QueryEscape(name), nil, &f) == nil && f.Global.Size > 0
	})
}

// waitNeeded waits until the phone counts something as needed.
func waitNeeded(t *testing.T, n *node) {
	t.Helper()
	waitFor(t, waitTimeout, n.name+" needs something", func() bool {
		var s folderStatus
		return n.do("GET", "/rest/db/status?folder="+folderID, nil, &s) == nil && s.NeedTotalItems > 0
	})
}

// TestLargePullerDelay checks whether a large pullerDelayS holds downloads
// while the app is open, and what it does to the Sync action.
func TestLargePullerDelay(t *testing.T) {
	p := newPair(t, pairOpts{
		files: map[string][]byte{"kept.bin": randomData(1, 100_000)},
		phoneCfg: func(_ *config.Configuration, f *config.FolderConfiguration) {
			f.PullerDelayS = 3600
		},
	})
	v1 := p.peer.content("kept.bin")
	// Under "*" the ignored rows are written only by a pull pass, which the
	// delay holds: the phone counts the ignored file as needed and the
	// computer sees the phone as incomplete.
	waitNeeded(t, p.phone)
	waitFor(t, waitTimeout, "phone sync-waiting", func() bool {
		var s folderStatus
		return p.phone.do("GET", "/rest/db/status?folder="+folderID, nil, &s) == nil && s.State == "sync-waiting"
	})
	c, _ := waitPeerHasPhoneIndex(t, p)
	st := p.phone.status()
	result(t, "pullerDelayS=3600 under \"*\": state=%q needTotalItems=%d; computer sees the phone at %.0f%%",
		st.State, st.NeedTotalItems, c.Completion)
	if st.State != "sync-waiting" || c.Completion >= 100 {
		t.Errorf("expected sync-waiting and an incomplete phone on the computer, got %q and %.0f%%", st.State, c.Completion)
	}

	// Arming through ignores alone: the post-scan pull is delayed too.
	p.phone.setIgnores(includeLines(t, "kept.bin"))
	p.phone.scan()
	waitNeeded(t, p.phone)
	held := holds(5*time.Second, func() bool { return !p.phone.has("kept.bin") })
	result(t, "pullerDelayS=3600: after arming ignores and scanning, download held for 5s = %v", held)
	if !held {
		t.Fatal("expected the pull after an ignore change to wait for pullerDelayS")
	}

	// A folder restart (any folder config change) pulls at once, because the
	// pull after the initial scan ignores pullerDelayS.
	t0 := time.Now()
	p.phone.patchFolder(map[string]any{"pullerDelayS": 0})
	waitFor(t, 30*time.Second, "download after folder restart", func() bool { return bytes.Equal(p.phone.content("kept.bin"), v1) })
	waitFor(t, waitTimeout, "synced", p.phone.synced)
	result(t, "\"Sync\" by setting pullerDelayS=0: downloaded in %v", time.Since(t0).Round(time.Millisecond))
	p.phone.patchFolder(map[string]any{"pullerDelayS": 3600})
	waitFor(t, waitTimeout, "synced", p.phone.synced)

	// While open: an index update schedules a pull that waits for the delay.
	v2 := randomData(2, 100_000)
	p.peerUpdate(map[string][]byte{"kept.bin": v2})
	waitNeeded(t, p.phone)
	held = holds(5*time.Second, func() bool { return bytes.Equal(p.phone.content("kept.bin"), v1) })
	result(t, "pullerDelayS=3600 while open: new version held for 5s = %v", held)
	if !held {
		t.Fatal("new version downloaded despite pullerDelayS=3600")
	}

	// App reopened, update made while the phone was stopped and unknown to
	// it: the start-up pull finds nothing, the index arrives later and its
	// pull waits for pullerDelayS.
	p.phone.patchFolder(map[string]any{"pullerDelayS": 0})
	waitFor(t, waitTimeout, "synced", p.phone.synced)
	p.phone.patchFolder(map[string]any{"pullerDelayS": 3600})
	waitFor(t, waitTimeout, "synced", p.phone.synced)
	p.phone.stop()
	v3 := randomData(3, 100_000)
	p.peerUpdate(map[string][]byte{"kept.bin": v3})
	restartPhoneBeforePeer(t, p, false)
	waitNeeded(t, p.phone)
	held = holds(10*time.Second, func() bool { return !bytes.Equal(p.phone.content("kept.bin"), v3) })
	result(t, "pullerDelayS=3600, update unknown to the phone before start: known and held for 10s after connect = %v", held)
	if !held {
		t.Fatal("update learned after start downloaded despite pullerDelayS=3600")
	}
}

// TestLargePullerDelayAfterRestart covers the other restart case: the phone
// learned of an update in an earlier session but did not download it. The
// test forces the start-up pull to run before the connection (computer
// stopped); it fails and is retried after pullerPauseS (default 60 s)
// regardless of pullerDelayS.
func TestLargePullerDelayAfterRestart(t *testing.T) {
	for _, pause := range []int{5, 3600} {
		t.Run(fmt.Sprintf("pullerPauseS=%d", pause), func(t *testing.T) {
			p := newPair(t, pairOpts{
				files: map[string][]byte{"kept.bin": randomData(1, 100_000)},
				phoneCfg: func(_ *config.Configuration, f *config.FolderConfiguration) {
					f.PullerDelayS = 3600
					f.PullerPauseS = pause
				},
			})
			p.phone.setIgnores(includeLines(t, "kept.bin"))
			p.phone.scan()
			if p.phone.status().NeedTotalItems == 0 {
				t.Fatal("expected kept.bin to be needed")
			}
			t0 := time.Now()
			restartPhoneBeforePeer(t, p, true)
			waitNeeded(t, p.phone)
			got := func() bool { return p.phone.has("kept.bin") }
			if pause < 60 {
				waitFor(t, 30*time.Second, "download through the pull retry", got)
				result(t, "known pending update, pullerDelayS=3600, pullerPauseS=%d: downloaded %v after start", pause, time.Since(t0).Round(time.Millisecond))
			} else {
				held := holds(15*time.Second, func() bool { return !got() })
				result(t, "known pending update, pullerDelayS=3600, pullerPauseS=%d: held for 15s after start = %v", pause, held)
				if !held {
					t.Fatal("downloaded despite large pullerDelayS and pullerPauseS")
				}
			}
		})
	}
}

// restartPhoneBeforePeer controls the start-up order: the computer is
// stopped, the phone starts and finishes its initial scan and start-up pull
// without a connection, and only then the computer starts and they connect.
// With pullFails the phone has a pending need, so the start-up pull must fail
// ("no connected device"); without it the start-up pull must find nothing.
func restartPhoneBeforePeer(t *testing.T, p *pair, pullFails bool) {
	t.Helper()
	p.peer.stop()
	if p.phone.running() {
		p.phone.stop()
	}
	p.startPhone(false)
	waitScanEvent(t, p.phone, 0)
	if pullFails {
		waitFor(t, waitTimeout, "start-up pull failed", func() bool {
			var s folderStatus
			return p.phone.do("GET", "/rest/db/status?folder="+folderID, nil, &s) == nil && s.PullErrors > 0
		})
	} else {
		waitFor(t, waitTimeout, "start-up pull found nothing", p.phone.synced)
	}
	p.peer.startSubprocess()
	waitFor(t, waitTimeout, "connected", func() bool { return p.phone.connectedTo(p.peer) })
}

// localIndexedNames returns the file names in LocalIndexUpdated events after
// since.
func localIndexedNames(n *node, since int) map[string]bool {
	n.t.Helper()
	names := map[string]bool{}
	for _, ev := range n.events(since, 0) {
		if ev.Type != "LocalIndexUpdated" {
			continue
		}
		var d struct {
			Filenames []string `json:"filenames"`
		}
		_ = json.Unmarshal(ev.Data, &d)
		for _, f := range d.Filenames {
			names[f] = true
		}
	}
	return names
}
