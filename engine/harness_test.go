//go:build noassets

package engine

// Test harness: two Syncthing instances on 127.0.0.1. The "phone" embeds
// lib/syncthing in the test process, as the app will. The "computer" peer is
// the same test binary re-executed as a subprocess (see TestMain), so it runs
// unmodified Syncthing from the pinned module. The phone can also run as a
// subprocess when a test needs to kill it.

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	mrand "math/rand/v2"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"

	"github.com/syncthing/syncthing/lib/config"
	"github.com/syncthing/syncthing/lib/events"
	"github.com/syncthing/syncthing/lib/locations"
	"github.com/syncthing/syncthing/lib/protocol"
	"github.com/syncthing/syncthing/lib/svcutil"
	"github.com/syncthing/syncthing/lib/syncthing"
)

const (
	folderID        = "media"
	helperHomeEnv   = "ENGINE_HELPER_HOME"
	deleteRetention = 10920 * time.Hour // what cmd/syncthing passes by default
	waitTimeout     = 90 * time.Second
)

func TestMain(m *testing.M) {
	// Syncthing logs to stdout through slog. Keep warnings and errors only,
	// unless ENGINE_LOG=1. Note: at Info level lib/syncthing also runs a
	// 450 ms CPU benchmark on every Start.
	level := slog.LevelWarn
	if os.Getenv("ENGINE_LOG") == "1" {
		level = slog.LevelDebug - 10
	}
	slog.SetDefault(slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: level})))

	if home := os.Getenv(helperHomeEnv); home != "" {
		os.Exit(runHelper(home))
	}
	os.Exit(m.Run())
}

// runHelper runs one embedded instance until SIGTERM or SIGINT.
func runHelper(home string) int {
	e, err := startEmbedded(home)
	if err != nil {
		fmt.Fprintln(os.Stderr, "helper start:", err)
		return 1
	}
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGTERM, syscall.SIGINT)
	<-sig
	e.stop()
	return 0
}

// embedded is one Syncthing instance running in this process.
type embedded struct {
	app    *syncthing.App
	cancel context.CancelFunc
	wg     sync.WaitGroup
}

// startEmbedded starts Syncthing from the config.xml, cert.pem and key.pem in
// home, the way the app's bridge will. Returns once the REST API is up.
func startEmbedded(home string) (*embedded, error) {
	for _, base := range []locations.BaseDirEnum{locations.ConfigBaseDir, locations.DataBaseDir} {
		if err := locations.SetBaseDir(base, home); err != nil {
			return nil, err
		}
	}
	cert, err := syncthing.LoadOrGenerateCertificate(locations.Get(locations.CertFile), locations.Get(locations.KeyFile))
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	e := &embedded{cancel: cancel}
	evLogger := events.NewLogger()
	e.serve(ctx, evLogger)
	cfg, err := syncthing.LoadConfigAtStartup(locations.Get(locations.ConfigFile), cert, evLogger, false, true)
	if err != nil {
		e.shutdown()
		return nil, err
	}
	e.serve(ctx, cfg)
	sdb, err := syncthing.OpenDatabase(locations.Get(locations.Database), deleteRetention)
	if err != nil {
		e.shutdown()
		return nil, err
	}
	app, err := syncthing.New(cfg, sdb, evLogger, cert, syncthing.Options{NoUpgrade: true})
	if err != nil {
		sdb.Close()
		e.shutdown()
		return nil, err
	}
	e.app = app
	if err := app.Start(); err != nil {
		e.shutdown()
		return nil, err
	}
	return e, nil
}

func (e *embedded) serve(ctx context.Context, s interface{ Serve(context.Context) error }) {
	e.wg.Add(1)
	go func() {
		defer e.wg.Done()
		_ = s.Serve(ctx)
	}()
}

func (e *embedded) shutdown() {
	e.cancel()
	e.wg.Wait()
}

// stop stops the app; lib/syncthing closes the database itself (App.wait).
func (e *embedded) stop() {
	e.app.Stop(svcutil.ExitSuccess)
	e.app.Wait()
	e.shutdown()
}

// node is one Syncthing instance plus what the tests need to talk to it.
type node struct {
	t       *testing.T
	name    string
	home    string
	dir     string // folder root
	id      protocol.DeviceID
	apiAddr string
	apiKey  string
	bepAddr string

	emb *embedded // in-process
	cmd *exec.Cmd // subprocess
	// exited is closed when the subprocess has been reaped.
	exited chan struct{}
}

var httpClient = &http.Client{
	// No keep-alive: idle client connections would show up as goroutines and
	// descriptors in the leak measurements.
	Transport: &http.Transport{DisableKeepAlives: true},
}

// requestTimeout bounds every REST call, so a stalled response cannot hang a
// test. Synchronous scans get longer (scanTimeout): on a 100k-file folder
// they legitimately take minutes.
const (
	requestTimeout = 30 * time.Second
	scanTimeout    = 15 * time.Minute
)

func newNode(t *testing.T, name string) *node {
	t.Helper()
	root := t.TempDir()
	n := &node{
		t:       t,
		name:    name,
		home:    filepath.Join(root, "home"),
		dir:     filepath.Join(root, "folder"),
		apiAddr: freeAddr(t),
		apiKey:  randomString(32),
		bepAddr: freeAddr(t),
	}
	for _, d := range []string{n.home, filepath.Join(n.dir, ".stfolder")} {
		if err := os.MkdirAll(d, 0o700); err != nil {
			t.Fatal(err)
		}
	}
	cert, err := syncthing.GenerateCertificate(filepath.Join(n.home, "cert.pem"), filepath.Join(n.home, "key.pem"))
	if err != nil {
		t.Fatal(err)
	}
	n.id = protocol.NewDeviceID(cert.Certificate[0])
	// Registered before any start, so a failed readiness wait still stops
	// the instance. Runs before the TempDir removal (cleanups are LIFO).
	t.Cleanup(func() {
		if n.running() {
			n.stop()
		}
	})
	return n
}

// baseConfig is the configuration forced on every start: loopback only, no
// discovery, relays, NAT, usage or crash reporting, upgrades or browser.
func (n *node) baseConfig(peers ...*node) config.Configuration {
	cfg := config.New(n.id)
	cfg.GUI.Enabled = true
	cfg.GUI.RawAddress = n.apiAddr
	cfg.GUI.APIKey = n.apiKey
	o := &cfg.Options
	o.RawListenAddresses = []string{"tcp://" + n.bepAddr}
	o.GlobalAnnEnabled = false
	o.LocalAnnEnabled = false
	o.RelaysEnabled = false
	o.NATEnabled = false
	o.RawStunServers = []string{}
	o.URAccepted = -1
	o.CREnabled = false
	o.AutoUpgradeIntervalH = 0
	o.StartBrowser = false
	o.AnnounceLANAddresses = false
	for _, p := range peers {
		d := cfg.Defaults.Device.Copy()
		d.DeviceID = p.id
		d.Name = p.name
		d.Addresses = []string{"tcp://" + p.bepAddr}
		cfg.SetDevice(d)
	}
	return cfg
}

func (n *node) folderConfig(cfg *config.Configuration, typ config.FolderType, peers ...*node) *config.FolderConfiguration {
	f := cfg.Defaults.Folder.Copy()
	f.ID = folderID
	f.Label = folderID
	f.Path = n.dir
	f.Type = typ
	f.FSWatcherEnabled = false
	f.RescanIntervalS = 0
	for _, p := range peers {
		f.Devices = append(f.Devices, config.FolderDeviceConfiguration{DeviceID: p.id})
	}
	cfg.SetFolder(f)
	for i := range cfg.Folders {
		if cfg.Folders[i].ID == folderID {
			return &cfg.Folders[i]
		}
	}
	panic("folder not set")
}

func (n *node) writeConfig(cfg config.Configuration) {
	n.t.Helper()
	var buf bytes.Buffer
	if err := cfg.WriteXML(&buf); err != nil {
		n.t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(n.home, "config.xml"), buf.Bytes(), 0o600); err != nil {
		n.t.Fatal(err)
	}
}

// startInProcess starts the node inside the test process.
func (n *node) startInProcess() time.Duration {
	n.t.Helper()
	t0 := time.Now()
	e, err := startEmbedded(n.home)
	if err != nil {
		n.t.Fatalf("%s: start: %v", n.name, err)
	}
	n.emb = e
	n.waitAPI()
	return time.Since(t0)
}

// startSubprocess starts the node as a child process running this test
// binary in helper mode.
func (n *node) startSubprocess() {
	n.t.Helper()
	exe, err := os.Executable()
	if err != nil {
		n.t.Fatal(err)
	}
	logf, err := os.OpenFile(filepath.Join(n.home, "process.log"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		n.t.Fatal(err)
	}
	cmd := exec.Command(exe, "-test.run=^$")
	cmd.Env = append(os.Environ(), helperHomeEnv+"="+n.home)
	cmd.Stdout = logf
	cmd.Stderr = logf
	cmd.SysProcAttr = &syscall.SysProcAttr{Pdeathsig: syscall.SIGKILL}
	if err := cmd.Start(); err != nil {
		n.t.Fatal(err)
	}
	n.cmd = cmd
	n.exited = make(chan struct{})
	go func(c *exec.Cmd, done chan struct{}) {
		_ = c.Wait()
		logf.Close()
		close(done)
	}(cmd, n.exited)
	n.waitAPI()
}

// stop stops the node cleanly and returns how long that took.
func (n *node) stop() time.Duration {
	n.t.Helper()
	t0 := time.Now()
	switch {
	case n.emb != nil:
		n.emb.stop()
		n.emb = nil
	case n.cmd != nil:
		_ = n.cmd.Process.Signal(syscall.SIGTERM)
		select {
		case <-n.exited:
			n.cmd = nil
		case <-time.After(60 * time.Second):
			n.kill()
			n.t.Fatalf("%s: did not stop within 60s; killed", n.name)
		}
	}
	return time.Since(t0)
}

// kill sends SIGKILL to a subprocess node.
func (n *node) kill() {
	n.t.Helper()
	if n.cmd == nil {
		n.t.Fatalf("%s: kill needs a subprocess", n.name)
	}
	_ = n.cmd.Process.Kill()
	<-n.exited
	n.cmd = nil
}

func (n *node) running() bool { return n.emb != nil || n.cmd != nil }

func (n *node) waitAPI() {
	n.t.Helper()
	waitFor(n.t, waitTimeout, n.name+" REST API", func() bool {
		return n.do("GET", "/rest/system/ping", nil, nil) == nil
	})
}

// do performs one REST call with requestTimeout. out may be nil.
func (n *node) do(method, path string, body, out any) error {
	return n.doTimeout(requestTimeout, method, path, body, out)
}

func (n *node) doTimeout(timeout time.Duration, method, path string, body, out any) error {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	var rd io.Reader
	if body != nil {
		bs, err := json.Marshal(body)
		if err != nil {
			return err
		}
		rd = bytes.NewReader(bs)
	}
	req, err := http.NewRequestWithContext(ctx, method, "http://"+n.apiAddr+path, rd)
	if err != nil {
		return err
	}
	req.Header.Set("X-API-Key", n.apiKey)
	resp, err := httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	bs, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("%s %s: %s: %s", method, path, resp.Status, strings.TrimSpace(string(bs)))
	}
	if out != nil {
		return json.Unmarshal(bs, out)
	}
	return nil
}

func (n *node) get(path string, out any) {
	n.t.Helper()
	if err := n.do("GET", path, nil, out); err != nil {
		n.t.Fatalf("%s: %v", n.name, err)
	}
}

func (n *node) post(path string, body any) {
	n.t.Helper()
	if err := n.do("POST", path, body, nil); err != nil {
		n.t.Fatalf("%s: %v", n.name, err)
	}
}

func (n *node) patchFolder(fields map[string]any) {
	n.t.Helper()
	if err := n.do("PATCH", "/rest/config/folders/"+folderID, fields, nil); err != nil {
		n.t.Fatalf("%s: %v", n.name, err)
	}
}

func (n *node) setIgnores(lines []string) {
	n.t.Helper()
	n.post("/rest/db/ignores?folder="+folderID, map[string]any{"ignore": lines})
}

// scan runs a full scan; the REST call returns when the scan is done.
func (n *node) scan() {
	n.t.Helper()
	if err := n.doTimeout(scanTimeout, "POST", "/rest/db/scan?folder="+folderID, nil, nil); err != nil {
		n.t.Fatalf("%s: %v", n.name, err)
	}
}

type folderStatus struct {
	State                         string `json:"state"`
	Error                         string `json:"error"`
	PullErrors                    int    `json:"pullErrors"`
	GlobalFiles                   int    `json:"globalFiles"`
	GlobalTotalItems              int    `json:"globalTotalItems"`
	LocalFiles                    int    `json:"localFiles"`
	LocalTotalItems               int    `json:"localTotalItems"`
	NeedTotalItems                int    `json:"needTotalItems"`
	NeedBytes                     int64  `json:"needBytes"`
	ReceiveOnlyChangedFiles       int    `json:"receiveOnlyChangedFiles"`
	ReceiveOnlyChangedDirectories int    `json:"receiveOnlyChangedDirectories"`
	ReceiveOnlyChangedDeletes     int    `json:"receiveOnlyChangedDeletes"`
	ReceiveOnlyTotalItems         int    `json:"receiveOnlyTotalItems"`
	Sequence                      int64  `json:"sequence"`
}

func (n *node) status() folderStatus {
	n.t.Helper()
	var s folderStatus
	n.get("/rest/db/status?folder="+folderID, &s)
	return s
}

type completion struct {
	Completion  float64 `json:"completion"`
	NeedBytes   int64   `json:"needBytes"`
	NeedItems   int     `json:"needItems"`
	NeedDeletes int     `json:"needDeletes"`
	RemoteState string  `json:"remoteState"`
	Sequence    int64   `json:"sequence"` // the device's sequence as known here
}

// completionOf returns how complete this node considers device d.
func (n *node) completionOf(d *node) completion {
	n.t.Helper()
	var c completion
	n.get("/rest/db/completion?folder="+folderID+"&device="+d.id.String(), &c)
	return c
}

// waitPeerHasPhoneIndex waits until the computer holds the phone's index up
// to the phone's current local sequence, so that the computer's completion
// reflects the phone's latest state. Call it when the phone is idle.
func waitPeerHasPhoneIndex(t *testing.T, p *pair) (completion, time.Duration) {
	t.Helper()
	var c completion
	d := waitFor(t, 30*time.Second, "computer has the phone's latest index", func() bool {
		var s folderStatus
		if p.phone.do("GET", "/rest/db/status?folder="+folderID, nil, &s) != nil {
			return false
		}
		if p.peer.do("GET", "/rest/db/completion?folder="+folderID+"&device="+p.phone.id.String(), nil, &c) != nil {
			return false
		}
		return c.Sequence == s.Sequence && c.RemoteState == "valid"
	})
	return c, d
}

func (n *node) connectedTo(d *node) bool {
	var resp struct {
		Connections map[string]struct {
			Connected     bool  `json:"connected"`
			InBytesTotal  int64 `json:"inBytesTotal"`
			OutBytesTotal int64 `json:"outBytesTotal"`
		} `json:"connections"`
	}
	if err := n.do("GET", "/rest/system/connections", nil, &resp); err != nil {
		return false
	}
	return resp.Connections[d.id.String()].Connected
}

// bytesIn returns the total bytes received from d over the current
// connections.
func (n *node) bytesIn(d *node) int64 {
	n.t.Helper()
	var resp struct {
		Connections map[string]struct {
			InBytesTotal int64 `json:"inBytesTotal"`
		} `json:"connections"`
	}
	n.get("/rest/system/connections", &resp)
	return resp.Connections[d.id.String()].InBytesTotal
}

type localChanged struct {
	Files []struct {
		Name string `json:"name"`
	} `json:"files"`
}

func (n *node) localChanged() []string {
	n.t.Helper()
	var lc localChanged
	n.get("/rest/db/localchanged?folder="+folderID, &lc)
	var names []string
	for _, f := range lc.Files {
		names = append(names, f.Name)
	}
	return names
}

type folderErrors struct {
	Errors []struct {
		Path  string `json:"path"`
		Error string `json:"error"`
	} `json:"errors"`
}

func (n *node) folderErrors() int {
	n.t.Helper()
	var fe folderErrors
	n.get("/rest/folder/errors?folder="+folderID, &fe)
	return len(fe.Errors)
}

type dbFile struct {
	Global fileInfo `json:"global"`
	Local  fileInfo `json:"local"`
}

type fileInfo struct {
	Name     string `json:"name"`
	Sequence int64  `json:"sequence"`
	Ignored  bool   `json:"ignored"`
	Invalid  bool   `json:"invalid"`
	Deleted  bool   `json:"deleted"`
	Size     int64  `json:"size"`
	Version  []any  `json:"version"`
	ModTime  string `json:"modified"`
	LocalF   any    `json:"localFlags"`
}

// file returns the database view of one file, or ok=false if unknown.
func (n *node) file(name string) (dbFile, bool) {
	n.t.Helper()
	var f dbFile
	err := n.do("GET", "/rest/db/file?folder="+folderID+"&file="+url.QueryEscape(name), nil, &f)
	if err != nil {
		if strings.Contains(err.Error(), "404") {
			return f, false
		}
		n.t.Fatalf("%s: %v", n.name, err)
	}
	return f, true
}

type event struct {
	ID   int             `json:"id"`
	Type string          `json:"type"`
	Time time.Time       `json:"time"`
	Data json.RawMessage `json:"data"`
}

// events returns events after since, waiting up to timeout for the first.
func (n *node) events(since int, timeout time.Duration) []event {
	n.t.Helper()
	var evs []event
	path := fmt.Sprintf("/rest/events?since=%d&timeout=%d", since, int(timeout.Seconds()))
	n.get(path, &evs)
	return evs
}

// lastEventID returns the current last event ID without waiting.
func (n *node) lastEventID() int {
	n.t.Helper()
	var evs []event
	n.get("/rest/events?limit=1&timeout=0", &evs)
	if len(evs) == 0 {
		return 0
	}
	return evs[len(evs)-1].ID
}

// synced is the "sync finished" rule the app is expected to use after it has
// changed ignores and run a scan: folder idle, nothing needed, no errors.
func (n *node) synced() bool {
	s := folderStatus{}
	if err := n.do("GET", "/rest/db/status?folder="+folderID, nil, &s); err != nil {
		return false
	}
	if s.State != "idle" || s.NeedTotalItems != 0 || s.PullErrors != 0 || s.Error != "" {
		return false
	}
	var fe folderErrors
	if err := n.do("GET", "/rest/folder/errors?folder="+folderID, nil, &fe); err != nil {
		return false
	}
	return len(fe.Errors) == 0
}

// applyIgnores is the app's selection step: write ignores, run a scan so the
// new patterns are loaded, then wait for the "finished" rule.
func (n *node) applyIgnores(lines []string) {
	n.t.Helper()
	n.setIgnores(lines)
	n.scan()
	waitFor(n.t, waitTimeout, n.name+" synced after ignore change", n.synced)
}

// pair is a phone and a computer sharing folderID.
type pair struct {
	phone, peer *node
}

type pairOpts struct {
	// files to create on the computer before it starts, relative path to
	// content.
	files map[string][]byte
	// phoneSubprocess runs the phone as a child process so it can be killed.
	phoneSubprocess bool
	phoneCfg        func(*config.Configuration, *config.FolderConfiguration)
	peerCfg         func(*config.Configuration, *config.FolderConfiguration)
}

// newPair starts a computer and a phone, connects them and waits until the
// phone has the computer's index. The phone folder is receive-only and is
// created the way the app accepts a folder: paused, ignores set to "*",
// then unpaused, so nothing downloads.
func newPair(t *testing.T, o pairOpts) *pair {
	t.Helper()
	p := &pair{phone: newNode(t, "phone"), peer: newNode(t, "computer")}

	for name, data := range o.files {
		p.peer.writeFile(name, data)
	}

	pc := p.peer.baseConfig(p.phone)
	pf := p.peer.folderConfig(&pc, config.FolderTypeSendReceive, p.phone)
	if o.peerCfg != nil {
		o.peerCfg(&pc, pf)
	}
	p.peer.writeConfig(pc)

	fc := p.phone.baseConfig(p.peer)
	ff := p.phone.folderConfig(&fc, config.FolderTypeReceiveOnly, p.peer)
	ff.Paused = true
	ff.IgnorePerms = true
	ff.MaxConflicts = 0
	if o.phoneCfg != nil {
		o.phoneCfg(&fc, ff)
	}
	p.phone.writeConfig(fc)

	p.peer.startSubprocess()
	p.peer.scan()

	p.startPhone(o.phoneSubprocess)
	p.phone.setIgnores([]string{"*"})
	p.phone.patchFolder(map[string]any{"paused": false})
	p.waitIndex()
	return p
}

func (p *pair) startPhone(subprocess bool) {
	p.phone.t.Helper()
	if subprocess {
		p.phone.startSubprocess()
	} else {
		p.phone.startInProcess()
	}
}

// waitIndex waits until the phone is connected and its global view matches
// the computer's local view.
func (p *pair) waitIndex() {
	p.phone.t.Helper()
	waitFor(p.phone.t, waitTimeout, "phone connected", func() bool { return p.phone.connectedTo(p.peer) })
	want := p.peer.status()
	waitFor(p.phone.t, waitTimeout, "phone has the computer's index", func() bool {
		var s folderStatus
		if err := p.phone.do("GET", "/rest/db/status?folder="+folderID, nil, &s); err != nil {
			return false
		}
		return s.GlobalTotalItems == want.LocalTotalItems
	})
}

// peerUpdate writes files on the computer and rescans it.
func (p *pair) peerUpdate(files map[string][]byte) {
	p.peer.t.Helper()
	for name, data := range files {
		p.peer.writeFile(name, data)
	}
	p.peer.scan()
}

func (n *node) path(name string) string { return filepath.Join(n.dir, filepath.FromSlash(name)) }

func (n *node) writeFile(name string, data []byte) {
	n.t.Helper()
	full := n.path(name)
	if err := os.MkdirAll(filepath.Dir(full), 0o700); err != nil {
		n.t.Fatal(err)
	}
	if err := os.WriteFile(full, data, 0o600); err != nil {
		n.t.Fatal(err)
	}
}

func (n *node) has(name string) bool {
	_, err := os.Lstat(n.path(name))
	return err == nil
}

func (n *node) content(name string) []byte {
	bs, err := os.ReadFile(n.path(name))
	if err != nil {
		return nil
	}
	return bs
}

// tempFiles lists Syncthing partial files in the folder with their allocated
// size in bytes (temp files are sparse). Allocation is st_blocks * 512, which
// tracks written data on ext4 and similar filesystems; a filesystem that
// preallocates or compresses would make these numbers meaningless.
func (n *node) tempFiles() map[string]int64 {
	n.t.Helper()
	out := map[string]int64{}
	_ = filepath.WalkDir(n.dir, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		if strings.HasPrefix(d.Name(), ".syncthing.") && strings.HasSuffix(d.Name(), ".tmp") {
			var st syscall.Stat_t
			if syscall.Stat(path, &st) == nil {
				rel, _ := filepath.Rel(n.dir, path)
				out[filepath.ToSlash(rel)] = st.Blocks * 512
			}
		}
		return nil
	})
	return out
}

// randomData returns n bytes of seeded pseudo-random data. Random content
// matters: Syncthing copies identical blocks locally instead of downloading.
func randomData(seed uint64, n int) []byte {
	r := mrand.New(mrand.NewChaCha8([32]byte{byte(seed), byte(seed >> 8), byte(seed >> 16), 1}))
	bs := make([]byte, n)
	for i := range bs {
		bs[i] = byte(r.Uint32())
	}
	return bs
}

func sum(bs []byte) string {
	h := sha256.Sum256(bs)
	return hex.EncodeToString(h[:8])
}

func randomString(n int) string {
	bs := make([]byte, n/2)
	_, _ = rand.Read(bs)
	return hex.EncodeToString(bs)
}

func freeAddr(t *testing.T) string {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	return l.Addr().String()
}

func waitFor(t *testing.T, timeout time.Duration, what string, cond func() bool) time.Duration {
	t.Helper()
	t0 := time.Now()
	for {
		if cond() {
			return time.Since(t0)
		}
		if time.Since(t0) > timeout {
			t.Fatalf("timed out after %v waiting for %s", timeout, what)
		}
		time.Sleep(50 * time.Millisecond)
	}
}

// holds checks that cond stays true for the whole duration.
func holds(d time.Duration, cond func() bool) bool {
	end := time.Now().Add(d)
	for time.Now().Before(end) {
		if !cond() {
			return false
		}
		time.Sleep(100 * time.Millisecond)
	}
	return cond()
}

func envInt(name string, def int) int {
	if v, err := strconv.Atoi(os.Getenv(name)); err == nil && v > 0 {
		return v
	}
	return def
}

func envInts(name string, def []int) []int {
	v := os.Getenv(name)
	if v == "" {
		return def
	}
	var out []int
	for _, f := range strings.Split(v, ",") {
		if i, err := strconv.Atoi(strings.TrimSpace(f)); err == nil && i > 0 {
			out = append(out, i)
		}
	}
	return out
}

// result logs a measurement in a greppable form for the findings report.
func result(t *testing.T, format string, args ...any) {
	t.Helper()
	t.Logf("RESULT %s: "+format, append([]any{t.Name()}, args...)...)
}

// walkFiles returns the regular files under dir, relative with "/"
// separators, skipping Syncthing's marker directory and partial files.
func walkFiles(dir string) map[string]bool {
	out := map[string]bool{}
	_ = filepath.WalkDir(dir, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if d.IsDir() && d.Name() == ".stfolder" {
			return filepath.SkipDir
		}
		if d.Type().IsRegular() && !strings.HasPrefix(d.Name(), ".syncthing.") {
			rel, _ := filepath.Rel(dir, path)
			out[filepath.ToSlash(rel)] = true
		}
		return nil
	})
	return out
}
