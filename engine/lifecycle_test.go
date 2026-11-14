//go:build noassets

package engine

import (
	"bytes"
	"os"
	"runtime"
	"runtime/debug"
	"slices"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/syncthing/syncthing/lib/protocol"
)

type usage struct {
	goroutines int
	fds        int
	heap       uint64 // HeapInuse after GC
	sys        uint64
	// rss is the resident set size. It includes what the Go heap does not
	// see, such as SQLite's C allocations (cgo builds use mattn/go-sqlite3).
	rss uint64
}

func measure() usage {
	runtime.GC()
	debug.FreeOSMemory() // return freed Go memory so RSS reflects what is held
	var ms runtime.MemStats
	runtime.ReadMemStats(&ms)
	ents, _ := os.ReadDir("/proc/self/fd")
	return usage{goroutines: runtime.NumGoroutine(), fds: len(ents), heap: ms.HeapInuse, sys: ms.Sys, rss: rss()}
}

// rss reads the resident set size from /proc/self/statm.
func rss() uint64 {
	bs, err := os.ReadFile("/proc/self/statm")
	if err != nil {
		return 0
	}
	f := strings.Fields(string(bs))
	if len(f) < 2 {
		return 0
	}
	pages, _ := strconv.ParseUint(f[1], 10, 64)
	return pages * uint64(os.Getpagesize())
}

// settle waits briefly for goroutines of a stopped instance to exit.
func settle(limit int) usage {
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) && runtime.NumGoroutine() > limit {
		time.Sleep(50 * time.Millisecond)
	}
	return measure()
}

// TestStartStopCycles answers the first part of question e): Syncthing
// embedded in one process starts and stops many times (ENGINE_CYCLES,
// default 20) while connected to a peer, without leaking goroutines,
// descriptors or memory.
//
// The phone stops right after it is connected and synced, which hits the
// 10 s close wait described in TestStopRightAfterConnect; the loop therefore
// runs with protocol.CloseTimeout lowered to 1 s, as the app should.
func TestStartStopCycles(t *testing.T) {
	cycles := envInt("ENGINE_CYCLES", 20)
	defer setCloseTimeout(time.Second)()
	p := newPair(t, pairOpts{files: map[string][]byte{
		"a.bin":     randomData(1, 1<<20),
		"dir/b.bin": randomData(2, 1000),
	}})
	p.phone.applyIgnores(includeLines(t, "a.bin"))

	cycle := func() (stop, start, connect time.Duration) {
		stop = p.phone.stop()
		start = p.phone.startInProcess()
		connect = waitFor(t, waitTimeout, "connected", func() bool { return p.phone.connectedTo(p.peer) })
		waitFor(t, waitTimeout, "synced", p.phone.synced)
		return
	}
	// Warm up: first-use allocations (TLS, SQLite, HTTP) are not leaks.
	for i := 0; i < 2; i++ {
		cycle()
	}
	p.phone.stop()
	base := settle(0)
	p.phone.startInProcess()
	waitFor(t, waitTimeout, "synced", p.phone.synced)
	running := measure()

	var stops, starts, connects []time.Duration
	for i := 0; i < cycles; i++ {
		s, st, c := cycle()
		stops, starts, connects = append(stops, s), append(starts, st), append(connects, c)
	}
	p.phone.stop()
	after := settle(base.goroutines)

	result(t, "%d cycles; stopped baseline: goroutines=%d fds=%d heapInuse=%.1fMiB rss=%.1fMiB; running: goroutines=%d fds=%d heapInuse=%.1fMiB rss=%.1fMiB",
		cycles, base.goroutines, base.fds, mib(base.heap), mib(base.rss), running.goroutines, running.fds, mib(running.heap), mib(running.rss))
	result(t, "after cycles, stopped: goroutines=%d (%+d) fds=%d (%+d) heapInuse=%.1fMiB (%+.1f) rss=%.1fMiB (%+.1f)",
		after.goroutines, after.goroutines-base.goroutines, after.fds, after.fds-base.fds,
		mib(after.heap), mib(after.heap)-mib(base.heap), mib(after.rss), mib(after.rss)-mib(base.rss))
	result(t, "stop latency %s; start to API ready %s; start to connected %s", stats(stops), stats(starts), stats(connects))

	if d := after.goroutines - base.goroutines; d > 2 {
		t.Errorf("goroutines grew by %d over %d cycles", d, cycles)
	}
	if d := after.fds - base.fds; d > 2 {
		t.Errorf("file descriptors grew by %d over %d cycles", d, cycles)
	}
	if d := mib(after.heap) - mib(base.heap); d > 16 {
		t.Errorf("heap in use grew by %.1f MiB over %d cycles", d, cycles)
	}
	if d := mib(after.rss) - mib(base.rss); d > 32 {
		t.Errorf("resident set grew by %.1f MiB over %d cycles", d, cycles)
	}
	if m := slices.Max(stops); m > 3*time.Second {
		t.Errorf("slowest stop took %v with CloseTimeout=1s", m)
	}
}

// TestStopMidTransfer answers the second part of question e): a stop during
// a download returns promptly, and the next start finishes the file
// correctly.
func TestStopMidTransfer(t *testing.T) {
	data := randomData(1, 32<<20)
	p := newPair(t, pairOpts{files: map[string][]byte{"big.bin": data}, peerCfg: slowPeer(4096)})
	p.phone.setIgnores(includeLines(t, "big.bin"))
	p.phone.scan()
	waitPartial(t, p.phone, 4<<20)
	if p.phone.has("big.bin") {
		t.Fatal("download finished before the stop")
	}
	stop := p.phone.stop()
	result(t, "stop during a download took %v", stop.Round(time.Millisecond))
	if stop > 3*time.Second {
		t.Errorf("stop took %v", stop)
	}
	p.phone.startInProcess()
	waitFor(t, 3*waitTimeout, "download finished after restart", func() bool { return bytes.Equal(p.phone.content("big.bin"), data) })
	waitFor(t, waitTimeout, "synced", p.phone.synced)
}

// TestStopRightAfterConnect pins an upstream behaviour: a new connection is
// started by the model's promotion timer about one second after it is
// added, while REST already reports the device as connected. A stop inside
// that window waits protocol.CloseTimeout (10 s) for a writer loop that never
// runs. Lowering the exported protocol.CloseTimeout bounds the stop without
// patching Syncthing.
func TestStopRightAfterConnect(t *testing.T) {
	p := newPair(t, pairOpts{files: map[string][]byte{"a.bin": randomData(1, 1000)}})
	stopAfter := func(wait time.Duration) time.Duration {
		p.phone.stop()
		p.phone.startInProcess()
		waitFor(t, waitTimeout, "connected", func() bool { return p.phone.connectedTo(p.peer) })
		time.Sleep(wait)
		return p.phone.stop()
	}
	immediate := stopAfter(0)
	settled := stopAfter(2 * time.Second)
	t.Cleanup(setCloseTimeout(time.Second))
	short := stopAfter(0)
	result(t, "stop right after connect %v; 2s after connect %v; right after connect with CloseTimeout=1s %v",
		immediate.Round(time.Millisecond), settled.Round(time.Millisecond), short.Round(time.Millisecond))
	// The default-timeout case is only logged: whether a stop lands in the
	// one-second window is timing-dependent.
	if settled > 2*time.Second || short > 3*time.Second {
		t.Errorf("stop too slow: settled %v, short timeout %v", settled, short)
	}
}

// setCloseTimeout sets protocol.CloseTimeout and returns a restore func.
// Only call it while no embedded instance is running.
func setCloseTimeout(d time.Duration) func() {
	old := protocol.CloseTimeout
	protocol.CloseTimeout = d
	return func() { protocol.CloseTimeout = old }
}

func mib(b uint64) float64 { return float64(b) / (1 << 20) }

func stats(ds []time.Duration) string {
	if len(ds) == 0 {
		return "n/a"
	}
	s := slices.Clone(ds)
	slices.Sort(s)
	return "min " + s[0].Round(time.Millisecond).String() +
		" median " + s[len(s)/2].Round(time.Millisecond).String() +
		" max " + s[len(s)-1].Round(time.Millisecond).String()
}
