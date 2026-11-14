//go:build noassets

package engine

import (
	"bytes"
	"encoding/json"
	"fmt"
	"strings"
	"testing"
	"time"
)

// TestSyncFinishedRule answers question d): after an ignore change, is
// "folder idle, needTotalItems == 0, no pull errors" a reliable signal that
// the selection is on disk? Checked three ways per iteration:
//   - naive: right after POST /rest/db/ignores, without waiting for a scan;
//   - scan call: POST /rest/db/scan (which returns when the scan is done),
//     then the rule;
//   - events: wait for the StateChanged scanning -> idle event that follows
//     the POST, then the rule.
func TestSyncFinishedRule(t *testing.T) {
	runs := envInt("ENGINE_FINISH_RUNS", 7)
	nfiles := runs + 1
	files := map[string][]byte{}
	var names []string
	for i := 0; i < nfiles; i++ {
		name := fmt.Sprintf("f%d.bin", i)
		files[name] = randomData(uint64(i), 512<<10)
		names = append(names, name)
	}
	p := newPair(t, pairOpts{files: files})

	onDisk := func(sel []string) error {
		for _, n := range sel {
			if !bytes.Equal(p.phone.content(n), files[n]) {
				return fmt.Errorf("%s missing or incomplete", n)
			}
		}
		return nil
	}

	naiveFalse, scanFalse, eventFalse := 0, 0, 0
	for i := 0; i < runs; i++ {
		// Grow the selection by one file per iteration; alternate the method.
		sel := names[:i+1]
		useEvents := i%2 == 1
		since := p.phone.lastEventID()
		t0 := time.Now()
		p.phone.setIgnores(includeLines(t, sel...))
		if p.phone.synced() && onDisk(sel) != nil {
			naiveFalse++
		}
		if useEvents {
			waitScanEvent(t, p.phone, since)
		} else {
			p.phone.scan()
		}
		waitFor(t, waitTimeout, "finished rule", p.phone.synced)
		elapsed := time.Since(t0)
		if err := onDisk(sel); err != nil {
			if useEvents {
				eventFalse++
			} else {
				scanFalse++
			}
			t.Errorf("iteration %d (events=%v): rule said finished but %v", i, useEvents, err)
		}
		if i == runs-1 || i == 0 {
			result(t, "event sequence after setting ignores (iteration %d, events=%v, finished in %v): %s",
				i, useEvents, elapsed.Round(time.Millisecond), eventTrace(p.phone, since))
		}
	}
	result(t, "%d iterations: naive check false positives %d; scan-call rule false positives %d; event rule false positives %d",
		runs, naiveFalse, scanFalse, eventFalse)
}

// waitScanEvent waits for the folder's next completed scan after event since
// (StateChanged into and out of "scanning") and returns its duration from
// the two event timestamps. (The events' own "duration" field is based on a
// time truncated to whole seconds.)
func waitScanEvent(t *testing.T, n *node, since int) time.Duration {
	t.Helper()
	var start time.Time
	deadline := time.Now().Add(10 * waitTimeout)
	for time.Now().Before(deadline) {
		for _, ev := range n.events(since, 5*time.Second) {
			since = ev.ID
			if ev.Type != "StateChanged" {
				continue
			}
			var d struct{ Folder, From, To string }
			_ = json.Unmarshal(ev.Data, &d)
			if d.Folder != folderID {
				continue
			}
			if d.To == "scanning" {
				start = ev.Time
			}
			if d.From == "scanning" && !start.IsZero() {
				return ev.Time.Sub(start)
			}
		}
	}
	t.Fatal("no completed scan after the ignore change")
	return 0
}

// eventTrace summarises the events after since: type, plus state changes and
// item names.
func eventTrace(n *node, since int) string {
	var out []string
	for _, ev := range n.events(since, 0) {
		var d struct {
			Folder, From, To, Item, Action string
			Summary                        *struct {
				State          string `json:"state"`
				NeedTotalItems int    `json:"needTotalItems"`
			} `json:"summary"`
			Filenames  []string `json:"filenames"`
			Completion *float64 `json:"completion"`
		}
		_ = json.Unmarshal(ev.Data, &d)
		s := ev.Type
		switch {
		case ev.Type == "StateChanged":
			s += fmt.Sprintf("(%s->%s)", d.From, d.To)
		case d.Item != "":
			s += fmt.Sprintf("(%s %s)", d.Action, d.Item)
		case d.Summary != nil:
			s += fmt.Sprintf("(%s need=%d)", d.Summary.State, d.Summary.NeedTotalItems)
		case len(d.Filenames) > 0:
			s += fmt.Sprintf("(%v)", d.Filenames)
		case d.Completion != nil:
			s += fmt.Sprintf("(%.0f%%)", *d.Completion)
		}
		out = append(out, s)
	}
	return strings.Join(out, ", ")
}
