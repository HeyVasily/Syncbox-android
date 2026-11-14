//go:build noassets

package engine

import (
	"encoding/json"
	"testing"
	"time"
)

// TestPausedFolderGetsNoIndexUpdates answers question a): while the phone's
// folder is paused, an index update from the computer is not applied (no
// RemoteIndexUpdated event), and the REST file list is not available at all.
// After unpausing, the update arrives.
func TestPausedFolderGetsNoIndexUpdates(t *testing.T) {
	p := newPair(t, pairOpts{files: map[string][]byte{"old.bin": randomData(1, 1000)}})

	p.phone.patchFolder(map[string]any{"paused": true})
	waitFor(t, waitTimeout, "computer sees the phone's folder as paused", func() bool {
		return p.peer.completionOf(p.phone).RemoteState == "paused"
	})
	since := p.phone.lastEventID()
	p.peerUpdate(map[string][]byte{"new.bin": randomData(2, 1000)})

	// What the app can read about a paused folder.
	var st folderStatus
	statusErr := p.phone.do("GET", "/rest/db/status?folder="+folderID, nil, &st)
	var browse []map[string]any
	browseErr := p.phone.do("GET", "/rest/db/browse?folder="+folderID, nil, &browse)
	fileErr := p.phone.do("GET", "/rest/db/file?folder="+folderID+"&file=old.bin", nil, nil)
	result(t, "paused folder: /rest/db/status err=%v state=%q globalFiles=%d; /rest/db/browse err=%v entries=%d; /rest/db/file err=%v",
		statusErr, st.State, st.GlobalFiles, browseErr, len(browse), fileErr)

	// No index for the folder is applied while paused.
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		for _, ev := range p.phone.events(since, time.Second) {
			since = ev.ID
			if ev.Type == "RemoteIndexUpdated" && eventFolder(ev) == folderID {
				t.Fatalf("paused folder applied an index update: %s", ev.Data)
			}
		}
	}
	if !p.phone.connectedTo(p.peer) {
		t.Fatal("connection dropped while the folder was paused")
	}
	result(t, "no RemoteIndexUpdated for the paused folder within 5s after the computer indexed new.bin; connection stayed up")

	t0 := time.Now()
	p.phone.patchFolder(map[string]any{"paused": false})
	waitFor(t, waitTimeout, "new.bin in the phone's index after unpause", func() bool {
		var f dbFile
		return p.phone.do("GET", "/rest/db/file?folder="+folderID+"&file=new.bin", nil, &f) == nil
	})
	result(t, "new.bin appeared in the phone's index %v after unpause", time.Since(t0).Round(time.Millisecond))
	// Positive control: the event the check above relies on does fire.
	sawIndex := false
	for _, ev := range p.phone.events(since, time.Second) {
		sawIndex = sawIndex || (ev.Type == "RemoteIndexUpdated" && eventFolder(ev) == folderID)
	}
	if !sawIndex {
		t.Fatal("no RemoteIndexUpdated after unpause; the paused check would be vacuous")
	}
	if p.phone.has("new.bin") {
		t.Fatal("new.bin was downloaded under ignore \"*\"")
	}
}

func eventFolder(ev event) string {
	var d struct {
		Folder string `json:"folder"`
	}
	_ = json.Unmarshal(ev.Data, &d)
	return d.Folder
}
