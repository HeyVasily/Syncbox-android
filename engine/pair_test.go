//go:build noassets

package engine

import (
	"bytes"
	"testing"
	"time"
)

// TestPairSelectOneFile is the baseline: the phone pairs, receives the index
// with nothing downloaded, and a whitelist line downloads exactly one file.
func TestPairSelectOneFile(t *testing.T) {
	a, b := randomData(1, 300_000), randomData(2, 300_000)
	t0 := time.Now()
	p := newPair(t, pairOpts{files: map[string][]byte{"a.bin": a, "dir/b.bin": b}})
	result(t, "pair and first index in %v", time.Since(t0).Round(time.Millisecond))

	if p.phone.has("a.bin") || p.phone.has("dir/b.bin") {
		t.Fatal("files downloaded under ignore \"*\"")
	}
	var browse []map[string]any
	p.phone.get("/rest/db/browse?folder="+folderID+"&levels=2", &browse)
	if len(browse) != 2 {
		t.Fatalf("browse shows %d top-level entries under \"*\", want 2: %v", len(browse), browse)
	}

	line, _ := IncludeLine("a.bin")
	p.phone.applyIgnores([]string{line, "*"})
	if !bytes.Equal(p.phone.content("a.bin"), a) {
		t.Fatal("a.bin not downloaded or wrong content")
	}
	if p.phone.has("dir/b.bin") {
		t.Fatal("unselected dir/b.bin was downloaded")
	}
	if lc := p.phone.localChanged(); len(lc) != 0 {
		t.Fatalf("locally changed items after select: %v", lc)
	}
	lag := waitPeerSeesPhoneComplete(t, p)
	result(t, "computer saw the phone at 100%% %v after the phone was synced", lag.Round(time.Millisecond))
}

// waitPeerSeesPhoneComplete waits until the computer has the phone's latest
// index, asserts that it then shows the phone as up to date (100%, no needed
// items), and returns how long the index took to arrive. The computer learns
// about the phone's ignored rows only through the phone's next index update.
func waitPeerSeesPhoneComplete(t *testing.T, p *pair) time.Duration {
	t.Helper()
	c, lag := waitPeerHasPhoneIndex(t, p)
	if c.Completion != 100 || c.NeedItems != 0 || c.NeedDeletes != 0 || c.RemoteState != "valid" {
		t.Fatalf("computer has the phone's index but sees it as %+v", c)
	}
	return lag
}
