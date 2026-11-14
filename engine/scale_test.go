//go:build noassets

package engine

import (
	"fmt"
	"io/fs"
	"path/filepath"
	"testing"
	"time"
)

// TestScanTimeWithManyIgnoreLines answers question g): how long a phone scan
// takes with many ignore lines against a large remote folder.
//
// The computer holds ENGINE_SCAN_FILES files (default 2000) in directories of
// 100. For each line count in ENGINE_SCAN_LINES (default "100,1000") the
// phone gets that many per-file include lines that match no file, plus "*".
// That is the worst case for Syncthing's linear matcher: every row is tested
// against every line before "*" matches.
func TestScanTimeWithManyIgnoreLines(t *testing.T) {
	nfiles := envInt("ENGINE_SCAN_FILES", 2000)
	counts := envInts("ENGINE_SCAN_LINES", []int{100, 1000})

	peer := map[string][]byte{}
	for i := 0; i < nfiles; i++ {
		peer[fmt.Sprintf("d%04d/f%06d.bin", i/100, i)] = []byte{byte(i)}
	}
	t0 := time.Now()
	p := newPair(t, pairOpts{files: peer})
	waitFor(t, 10*waitTimeout, "phone synced under \"*\"", p.phone.synced)
	st := p.phone.status()
	result(t, "%d remote files: setup and index in %v; phone rows localTotalItems=%d globalTotalItems=%d; phone database %.1f MiB",
		nfiles, time.Since(t0).Round(time.Millisecond), st.LocalTotalItems, st.GlobalTotalItems, mib(uint64(dirSize(p.phone.home))))

	// measureLines sets ignores and times the one scan that the POST
	// schedules (from its events), plus the time until the rule says synced.
	measureLines := func(lines []string) (scan, settled time.Duration) {
		since := p.phone.lastEventID()
		t0 := time.Now()
		p.phone.setIgnores(lines)
		scan = waitScanEvent(t, p.phone, since)
		waitFor(t, 10*waitTimeout, "synced", p.phone.synced)
		settled = time.Since(t0)
		return
	}
	// rescan times one explicit scan with unchanged ignores, from its events.
	rescan := func() time.Duration {
		since := p.phone.lastEventID()
		p.phone.scan()
		return waitScanEvent(t, p.phone, since)
	}

	scan, settled := measureLines([]string{"*"})
	result(t, "%d files, \"*\" only: scheduled scan %v, POST to synced %v, unchanged rescan %v",
		nfiles, scan.Round(time.Millisecond), settled.Round(time.Millisecond), rescan().Round(time.Millisecond))
	for _, n := range counts {
		var sel []string
		for i := 0; i < n; i++ {
			sel = append(sel, fmt.Sprintf("x%04d/missing%06d.bin", i/100, i))
		}
		lines := includeLines(t, sel...)
		scan, settled := measureLines(lines)
		result(t, "%d files, %d lines (%d patterns incl. ancestors): scheduled scan %v, POST to synced %v, unchanged rescan %v",
			nfiles, n, len(lines), scan.Round(time.Millisecond), settled.Round(time.Millisecond), rescan().Round(time.Millisecond))
	}
}

func dirSize(dir string) int64 {
	var total int64
	_ = filepath.WalkDir(dir, func(_ string, d fs.DirEntry, err error) error {
		if err == nil && !d.IsDir() {
			if fi, err := d.Info(); err == nil {
				total += fi.Size()
			}
		}
		return nil
	})
	return total
}
