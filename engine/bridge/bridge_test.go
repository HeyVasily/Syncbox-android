//go:build noassets

package bridge

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/cookiejar"
	"strings"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/syncthing/syncthing/lib/config"
	"github.com/syncthing/syncthing/lib/events"
	"github.com/syncthing/syncthing/lib/protocol"
	"github.com/syncthing/syncthing/lib/syncthing"
)

// TestStartStop starts the bridge twice in one process. A folder whose gate
// was left open (as by a killed download job) is closed again by Start, and
// Stop ends the REST API.
func TestStartStop(t *testing.T) {
	home := t.TempDir()
	if err := os.MkdirAll(filepath.Join(home, "folder", ".stfolder"), 0o700); err != nil {
		t.Fatal(err)
	}
	cert, err := syncthing.GenerateCertificate(filepath.Join(home, "cert.pem"), filepath.Join(home, "key.pem"))
	if err != nil {
		t.Fatal(err)
	}
	myID := protocol.NewDeviceID(cert.Certificate[0])
	cfg := config.New(myID)
	o := &cfg.Options
	o.RawListenAddresses = []string{"tcp://127.0.0.1:0"}
	o.GlobalAnnEnabled, o.LocalAnnEnabled, o.RelaysEnabled, o.NATEnabled = false, false, false, false
	f := cfg.Defaults.Folder.Copy()
	f.ID, f.Path, f.Type = "media", filepath.Join(home, "folder"), config.FolderTypeReceiveOnly
	f.MinDiskFree = config.Size{Value: 1, Unit: "%"} // gate open
	cfg.SetFolder(f)
	w := config.Wrap(filepath.Join(home, "config.xml"), cfg, myID, events.NoopLogger)
	if err := w.Save(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = Stop() })

	for cycle := 0; cycle < 2; cycle++ {
		t0 := time.Now()
		if err := Start(home, false); err != nil {
			t.Fatalf("cycle %d: start: %v", cycle, err)
		}
		if !Running() || DeviceID() != myID.String() || APIKey() == "" {
			t.Fatalf("cycle %d: running=%v id=%q", cycle, Running(), DeviceID())
		}
		addr, key := APIAddress(), APIKey()
		var folder struct {
			MinDiskFree config.Size `json:"minDiskFree"`
		}
		get(t, addr, key, "/rest/config/folders/media", &folder)
		if folder.MinDiskFree != gateClosed {
			t.Fatalf("cycle %d: folder minDiskFree %+v, want closed %+v", cycle, folder.MinDiskFree, gateClosed)
		}
		var defaults struct {
			MinDiskFree config.Size `json:"minDiskFree"`
		}
		get(t, addr, key, "/rest/config/defaults/folder", &defaults)
		if defaults.MinDiskFree != gateClosed {
			t.Fatalf("cycle %d: default folder minDiskFree %+v", cycle, defaults.MinDiskFree)
		}
		t.Logf("cycle %d: start to REST %v", cycle, time.Since(t0))

		t0 = time.Now()
		if err := Stop(); err != nil {
			t.Fatal(err)
		}
		if Running() || APIAddress() != "" {
			t.Fatalf("cycle %d: still running after Stop", cycle)
		}
		if _, err := http.Get("http://" + addr + "/rest/noauth/health"); err == nil {
			t.Fatalf("cycle %d: REST still answers after Stop", cycle)
		}
		if d := time.Since(t0); d > 3*time.Second {
			t.Fatalf("cycle %d: stop took %v", cycle, d)
		}
	}
}

// TestRESTNeedsKey: another app on the phone can reach 127.0.0.1, so a
// request without the API key must be rejected on /rest, also with the CSRF
// cookie and header a browser session would get from "/".
func TestRESTNeedsKey(t *testing.T) {
	home := t.TempDir()
	t.Cleanup(func() { _ = Stop() })
	if err := Start(home, false); err != nil {
		t.Fatal(err)
	}
	if err := Start(home, false); !errors.Is(err, ErrRunning) {
		t.Fatalf("second Start: %v, want ErrRunning", err)
	}
	addr, key := APIAddress(), APIKey()
	var cfg struct{}
	get(t, addr, key, "/rest/config", &cfg) // the key still works

	jar, _ := cookiejar.New(nil)
	client := &http.Client{Jar: jar}
	resp, err := client.Get("http://" + addr + "/")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	csrfHeader, csrfToken := "", ""
	for _, c := range resp.Cookies() {
		if strings.HasPrefix(c.Name, "CSRF-Token-") {
			csrfHeader, csrfToken = "X-"+c.Name, c.Value
		}
	}
	t.Logf("GET /: %s, CSRF cookie: %v", resp.Status, csrfToken != "")

	for _, withCSRF := range []bool{false, true} {
		for _, m := range []struct{ method, path, body string }{
			{http.MethodGet, "/rest/config", ""},
			{http.MethodGet, "/rest/system/status", ""},
			{http.MethodPatch, "/rest/config/options", `{"urAccepted":3}`},
		} {
			req, _ := http.NewRequest(m.method, "http://"+addr+m.path, strings.NewReader(m.body))
			if withCSRF && csrfToken != "" {
				req.Header.Set(csrfHeader, csrfToken)
			}
			resp, err := client.Do(req)
			if err != nil {
				t.Fatal(err)
			}
			resp.Body.Close()
			if resp.StatusCode != http.StatusUnauthorized && resp.StatusCode != http.StatusForbidden {
				t.Errorf("%s %s without key (csrf=%v): %s, want 401 or 403", m.method, m.path, withCSRF, resp.Status)
			}
		}
	}
	var opts struct {
		URAccepted int `json:"urAccepted"`
	}
	get(t, addr, key, "/rest/config/options", &opts)
	if opts.URAccepted != -1 {
		t.Fatalf("options changed without a key: urAccepted %d", opts.URAccepted)
	}

	// A restart keeps the stored hash and still needs the key.
	if err := Stop(); err != nil {
		t.Fatal(err)
	}
	if err := Start(home, false); err != nil {
		t.Fatal(err)
	}
	req, _ := http.NewRequest(http.MethodGet, "http://"+APIAddress()+"/rest/config", nil)
	if resp, err := http.DefaultClient.Do(req); err != nil || resp.StatusCode != http.StatusUnauthorized && resp.StatusCode != http.StatusForbidden {
		t.Fatalf("after restart, keyless GET /rest/config: %v %v", resp, err)
	}
}

func get(t *testing.T, addr, key, path string, out any) {
	t.Helper()
	req, _ := http.NewRequest(http.MethodGet, "http://"+addr+path, nil)
	req.Header.Set("X-API-Key", key)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("GET %s: %s", path, resp.Status)
	}
	if err := json.NewDecoder(resp.Body).Decode(out); err != nil {
		t.Fatal(err)
	}
}
