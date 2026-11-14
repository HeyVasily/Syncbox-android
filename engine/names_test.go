//go:build noassets

package engine

import (
	"os"
	"strings"
	"testing"

	"golang.org/x/text/unicode/norm"
)

// TestSpecialFileNamesSelectDeselect answers question h) end to end: each
// special name (and the same name inside a directory) is selected alone with
// the generated ignore file, must be the only file downloaded, and is then
// deselected cleanly. Decoys are the names a wrong pattern would also match.
// The computer also holds an NFD-named file, as a Mac or a Linux tool may
// produce.
func TestSpecialFileNamesSelectDeselect(t *testing.T) {
	nfd := norm.NFD.String("nfd-café")
	files := map[string][]byte{nfd: []byte("nfd")}
	var targets []string
	for i, tc := range specialNames {
		for _, name := range []string{tc.name, "sub " + tc.name + "/" + tc.name} {
			files[name] = []byte(name)
			targets = append(targets, name)
		}
		for _, d := range tc.decoys {
			if _, ok := files[d]; !ok && !strings.Contains(d, "/") {
				files[d] = []byte{byte(i)}
			}
		}
	}
	p := newPair(t, pairOpts{files: files})

	// What does the index call the NFD file?
	nfc := norm.NFC.String(nfd)
	_, peerKeptNFD := os.Lstat(p.peer.path(nfd))
	_, indexNFC := p.phone.file(nfc)
	result(t, "computer file created with an NFD name: still NFD on the computer's disk=%v, indexed as NFC=%v", peerKeptNFD == nil, indexNFC)
	targets = append(targets, nfc)
	files[nfc] = files[nfd]

	for _, name := range targets {
		lines := includeLines(t, name)
		p.phone.applyIgnores(lines)
		if got := string(p.phone.content(name)); got != string(files[name]) {
			t.Errorf("%q: not downloaded with lines %q", name, lines)
			continue
		}
		if extra := filesOnPhone(t, p.phone, name); len(extra) != 0 {
			t.Errorf("%q: lines %q also downloaded %q", name, lines, extra)
		}
		p.phone.setIgnores([]string{"*"})
		p.phone.scan()
		removeLocal(t, p.phone, name)
		waitFor(t, waitTimeout, "synced after deselect", p.phone.synced)
		if st := p.phone.status(); st.ReceiveOnlyTotalItems != 0 {
			t.Errorf("%q: %d locally changed items after deselect: %v", name, st.ReceiveOnlyTotalItems, p.phone.localChanged())
		}
	}
	assertClean(t, p, "after selecting and deselecting every special name")
	result(t, "%d targets (%d names, each at the top level and inside a same-named directory, plus 1 NFD name) selected alone and deselected cleanly",
		len(targets), len(specialNames))
}

// filesOnPhone lists regular files in the phone's folder other than want.
func filesOnPhone(t *testing.T, n *node, want string) []string {
	t.Helper()
	var extra []string
	for name := range walkFiles(n.dir) {
		if name != norm.NFC.String(want) && name != ".stignore" {
			extra = append(extra, name)
		}
	}
	return extra
}
