//go:build noassets

package engine

import (
	"bytes"
	"testing"
	"time"

	"github.com/syncthing/syncthing/lib/config"
)

// TestKeepTemporariesDefault records the keepTemporariesH default of the
// pinned Syncthing.
func TestKeepTemporariesDefault(t *testing.T) {
	cfg := config.New(newNode(t, "x").id)
	result(t, "keepTemporariesH default = %d hours", cfg.Options.KeepTemporariesH)
	if cfg.Options.KeepTemporariesH != 24 {
		t.Errorf("keepTemporariesH default changed to %d", cfg.Options.KeepTemporariesH)
	}
}

// TestPartialFilesSurviveStopAndKill answers question f): the phone runs as
// a subprocess; a download is interrupted first by a clean stop (SIGTERM ->
// App.Stop) and then by SIGKILL. The partial file must survive both, and the
// bytes received after each restart must show that the blocks already on
// disk were reused rather than fetched again.
func TestPartialFilesSurviveStopAndKill(t *testing.T) {
	const size = 32 << 20
	data := randomData(7, size)
	p := newPair(t, pairOpts{
		files:           map[string][]byte{"big.bin": data},
		peerCfg:         slowPeer(4096),
		phoneSubprocess: true,
	})
	p.phone.setIgnores(includeLines(t, "big.bin"))
	p.phone.scan()

	// Progress is the partial file's allocated size (st_blocks, see
	// tempFiles), which assumes ext4-like allocation of written blocks only.
	// slack covers protocol overhead and blocks received but not yet
	// written when a counter was read (Syncthing blocks are 128 KiB here).
	const slack = 1 << 20

	// Session 1, ended by a clean stop.
	waitPartial(t, p.phone, 8<<20)
	beforeStop := partialSize(t, p.phone)
	in1 := p.phone.bytesIn(p.peer)
	p.phone.stop()
	afterStop := partialSize(t, p.phone)
	result(t, "clean stop: partial %.1f MiB before, %.1f MiB after (session received %.1f MiB)",
		mib(uint64(beforeStop)), mib(uint64(afterStop)), mib(uint64(in1)))
	if afterStop < beforeStop {
		t.Errorf("clean stop lost progress: %d -> %d bytes", beforeStop, afterStop)
	}

	// Session 2, ended by SIGKILL.
	p.startPhone(true)
	waitFor(t, waitTimeout, "connected", func() bool { return p.phone.connectedTo(p.peer) })
	waitPartial(t, p.phone, afterStop+8<<20)
	beforeKill := partialSize(t, p.phone)
	in2 := p.phone.bytesIn(p.peer)
	p.phone.kill()
	afterKill := partialSize(t, p.phone)
	result(t, "SIGKILL: partial %.1f MiB before, %.1f MiB after (session received %.1f MiB, partial grew %.1f MiB)",
		mib(uint64(beforeKill)), mib(uint64(afterKill)), mib(uint64(in2)), mib(uint64(afterKill-afterStop)))
	if afterKill < beforeKill {
		t.Errorf("SIGKILL lost progress: %d -> %d bytes", beforeKill, afterKill)
	}
	if in2 > afterKill-afterStop+slack {
		t.Errorf("session 2 received %.2f MiB but the partial grew only %.2f MiB: blocks were fetched again", mib(uint64(in2)), mib(uint64(afterKill-afterStop)))
	}

	// Session 3 finishes the file.
	t0 := time.Now()
	p.startPhone(true)
	waitFor(t, 3*waitTimeout, "download finished", func() bool { return bytes.Equal(p.phone.content("big.bin"), data) })
	in3 := p.phone.bytesIn(p.peer)
	result(t, "resume after kill: finished in %v, received %.2f MiB for the %.2f MiB still missing",
		time.Since(t0).Round(time.Millisecond), mib(uint64(in3)), mib(uint64(size-afterKill)))
	if in3 > size-afterKill+slack {
		t.Errorf("resume received %.2f MiB, more than the %.2f MiB missing: the partial was not reused", mib(uint64(in3)), mib(uint64(size-afterKill)))
	}
	waitFor(t, waitTimeout, "synced", p.phone.synced)
	if tmp := p.phone.tempFiles(); len(tmp) != 0 {
		t.Errorf("partial files left after completion: %v", tmp)
	}
}

func partialSize(t *testing.T, n *node) int64 {
	t.Helper()
	tmp := n.tempFiles()
	if len(tmp) != 1 {
		t.Fatalf("want exactly one partial file, got %v", tmp)
	}
	for _, size := range tmp {
		return size
	}
	return 0
}
