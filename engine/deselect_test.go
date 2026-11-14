//go:build noassets

package engine

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/syncthing/syncthing/lib/config"
)

// removeLocal deletes a file, its partial file and empty parent directories,
// which is what the app does after the ignore change has been scanned.
func removeLocal(t *testing.T, n *node, name string) {
	t.Helper()
	full := n.path(name)
	for _, f := range []string{full, filepath.Join(filepath.Dir(full), ".syncthing."+filepath.Base(full)+".tmp")} {
		if err := os.Remove(f); err != nil && !os.IsNotExist(err) {
			t.Fatal(err)
		}
	}
	for dir := filepath.Dir(full); dir != n.dir && strings.HasPrefix(dir, n.dir); dir = filepath.Dir(dir) {
		if os.Remove(dir) != nil {
			break
		}
	}
}

// assertClean checks the state the design requires after a deselect: no
// locally changed items, nothing needed, no errors, and the computer sees the
// phone as up to date.
func assertClean(t *testing.T, p *pair, when string) {
	t.Helper()
	waitFor(t, waitTimeout, "phone synced "+when, p.phone.synced)
	st := p.phone.status()
	lc := p.phone.localChanged()
	if st.ReceiveOnlyTotalItems != 0 || st.ReceiveOnlyChangedFiles != 0 || st.ReceiveOnlyChangedDeletes != 0 || len(lc) != 0 {
		t.Fatalf("%s: locally changed items: status %+v, list %v", when, st, lc)
	}
	if tmp := p.phone.tempFiles(); len(tmp) != 0 {
		t.Fatalf("%s: partial files left: %v", when, tmp)
	}
	lag := waitPeerSeesPhoneComplete(t, p)
	result(t, "%s: no locally changed items, computer saw the phone at 100%% after %v", when, lag.Round(time.Millisecond))
}

// TestDeselectIgnoreScanDelete answers question c): deselecting in the order
// "change ignores, wait for the scan, delete the file" leaves no
// receiveOnlyChanged items, and the computer shows the phone up to date, also
// after a rescan and a restart.
func TestDeselectIgnoreScanDelete(t *testing.T) {
	p := newPair(t, pairOpts{files: map[string][]byte{
		"dir/a.bin": randomData(1, 300_000),
		"b.bin":     randomData(2, 1000),
	}})
	p.phone.applyIgnores(includeLines(t, "dir/a.bin"))
	if !p.phone.has("dir/a.bin") {
		t.Fatal("dir/a.bin not downloaded")
	}
	// Positive case for the ancestor lines (see
	// TestNestedSelectionWithoutParentLines for the negative one).
	lag := waitPeerSeesPhoneComplete(t, p)
	if dir, _ := p.phone.file("dir"); dir.Local.Invalid || dir.Local.Ignored {
		t.Fatalf("parent directory row is %+v, want a normal pulled directory", dir.Local)
	}
	result(t, "nested file selected with ancestor lines: computer saw the phone at 100%% with 0 needed items after %v", lag.Round(time.Millisecond))

	p.phone.setIgnores([]string{"*"})
	p.phone.scan()
	removeLocal(t, p.phone, "dir/a.bin")
	if p.phone.has("dir") {
		t.Fatal("empty parent directory left")
	}
	assertClean(t, p, "right after delete")

	p.phone.scan()
	assertClean(t, p, "after a rescan")

	p.phone.stop()
	p.startPhone(false)
	p.waitIndex()
	assertClean(t, p, "after a restart")
}

// TestDeselectWrongOrders records what the wrong orders leave behind, judged
// by the computer's completion once it holds the phone's latest index, right
// away and after a phone restart.
func TestDeselectWrongOrders(t *testing.T) {
	check := func(t *testing.T, p *pair, when string) completion {
		t.Helper()
		waitFor(t, waitTimeout, "phone synced", p.phone.synced)
		c, _ := waitPeerHasPhoneIndex(t, p)
		st := p.phone.status()
		result(t, "%s: computer sees completion=%.0f%% needItems=%d needDeletes=%d; phone receiveOnlyTotalItems=%d",
			when, c.Completion, c.NeedItems, c.NeedDeletes, st.ReceiveOnlyTotalItems)
		return c
	}
	restart := func(p *pair) {
		p.phone.stop()
		p.startPhone(false)
		p.waitIndex()
	}
	t.Run("delete-ignore-scan", func(t *testing.T) {
		p := newPair(t, pairOpts{files: map[string][]byte{"a.bin": randomData(1, 1000)}})
		p.phone.applyIgnores(includeLines(t, "a.bin"))
		removeLocal(t, p.phone, "a.bin")
		p.phone.setIgnores([]string{"*"})
		p.phone.scan()
		for _, when := range []string{"delete, ignore, scan", "after a restart"} {
			if when != "delete, ignore, scan" {
				restart(p)
			}
			if c := check(t, p, when); c.Completion != 100 || c.NeedItems != 0 {
				t.Errorf("%s: computer sees the phone at %.0f%%, needItems=%d; want 100%%, 0", when, c.Completion, c.NeedItems)
			}
		}
	})
	t.Run("delete-scan-ignore-scan", func(t *testing.T) {
		p := newPair(t, pairOpts{files: map[string][]byte{"a.bin": randomData(1, 1000)}})
		p.phone.applyIgnores(includeLines(t, "a.bin"))
		removeLocal(t, p.phone, "a.bin")
		p.phone.scan()
		st := p.phone.status()
		result(t, "delete, then scan: receiveOnlyChangedDeletes=%d localchanged=%v", st.ReceiveOnlyChangedDeletes, p.phone.localChanged())
		p.phone.setIgnores([]string{"*"})
		p.phone.scan()
		for _, when := range []string{"delete, scan, ignore, scan", "after a restart"} {
			if when != "delete, scan, ignore, scan" {
				restart(p)
			}
			if c := check(t, p, when); c.Completion >= 100 {
				t.Errorf("%s: computer sees the phone at 100%%; the wrong order was expected to leave it behind", when)
			}
			if st := p.phone.status(); st.ReceiveOnlyTotalItems != 0 {
				t.Errorf("%s: phone shows %d locally changed items; expected its own counters to look clean", when, st.ReceiveOnlyTotalItems)
			}
		}
	})
}

// slowPeer limits the computer's upload so that a transfer can be
// interrupted.
func slowPeer(kibps int) func(*config.Configuration, *config.FolderConfiguration) {
	return func(c *config.Configuration, _ *config.FolderConfiguration) {
		c.Options.MaxSendKbps = kibps
		c.Options.LimitBandwidthInLan = true
	}
}

// waitPartial waits until the phone has a partial file of at least min
// allocated bytes and returns its allocated size.
func waitPartial(t *testing.T, n *node, min int64) int64 {
	t.Helper()
	var got int64
	waitFor(t, waitTimeout, "partial file on the phone", func() bool {
		for _, size := range n.tempFiles() {
			got = size
			if size >= min {
				return true
			}
		}
		return false
	})
	return got
}

// TestSelectStopDeselect answers the second part of question c): a file
// selected, interrupted by a stop, then deselected leaves no stuck state,
// whether the deselect happens with Syncthing running (REST) or stopped
// (writing .stignore directly).
func TestSelectStopDeselect(t *testing.T) {
	for _, mode := range []string{"running", "stopped"} {
		t.Run(mode, func(t *testing.T) {
			p := newPair(t, pairOpts{
				files:   map[string][]byte{"big.bin": randomData(1, 32<<20)},
				peerCfg: slowPeer(4096),
			})
			p.phone.setIgnores(includeLines(t, "big.bin"))
			p.phone.scan()
			waitPartial(t, p.phone, 2<<20)
			p.phone.stop()

			if mode == "stopped" {
				if err := os.WriteFile(p.phone.path(".stignore"), []byte("*\n"), 0o600); err != nil {
					t.Fatal(err)
				}
				removeLocal(t, p.phone, "big.bin")
				p.startPhone(false)
				p.waitIndex()
			} else {
				p.startPhone(false)
				p.waitIndex()
				p.phone.setIgnores([]string{"*"})
				p.phone.scan()
				removeLocal(t, p.phone, "big.bin")
			}
			assertClean(t, p, "deselected while "+mode)
			p.phone.stop()
			p.startPhone(false)
			p.waitIndex()
			assertClean(t, p, "deselected while "+mode+", after a restart")
		})
	}
}

// TestNestedSelectionWithoutParentLines pins an upstream behaviour that the
// generator works around: with only "!/dir/a.bin" and "*", the puller creates
// "dir" as a local directory that carries the phone's own version and the
// receive-only flag. The computer then counts one needed item for the phone
// (completion 100% by bytes, which its GUI shows as 95%), and that survives
// deselecting, rescanning and restarting. If this test starts failing,
// upstream changed and the ancestor lines may no longer be needed.
func TestNestedSelectionWithoutParentLines(t *testing.T) {
	p := newPair(t, pairOpts{files: map[string][]byte{"dir/a.bin": randomData(1, 1000)}})
	p.phone.applyIgnores([]string{"!/dir/a.bin", "*"})
	stuck := func(when string) {
		t.Helper()
		waitFor(t, waitTimeout, "phone synced", p.phone.synced)
		c, _ := waitPeerHasPhoneIndex(t, p)
		dir, _ := p.phone.file("dir")
		st := p.phone.status()
		result(t, "%s: computer sees completion=%.0f%% needItems=%d; phone row dir invalid=%v localFlags=%v; receiveOnlyChangedDirectories=%d",
			when, c.Completion, c.NeedItems, dir.Local.Invalid, dir.Local.LocalF, st.ReceiveOnlyChangedDirectories)
		if c.NeedItems != 1 {
			t.Fatalf("%s: expected the computer to count the parent directory as needed", when)
		}
	}
	stuck("selected")
	p.phone.setIgnores([]string{"*"})
	p.phone.scan()
	removeLocal(t, p.phone, "dir/a.bin")
	p.phone.scan()
	stuck("deselected")
	p.phone.stop()
	p.startPhone(false)
	p.waitIndex()
	stuck("restarted")
}
