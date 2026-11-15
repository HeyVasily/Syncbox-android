//go:build noassets

// Package bridge is the app's only Go code: it starts and stops unmodified
// Syncthing in the app process, for gomobile bind. Everything else goes
// through Syncthing's REST API on 127.0.0.1, so no Syncthing type crosses the
// boundary: the exported functions take and return strings and bools only.
//
// Exported functions recover panics on their own goroutine and return them as
// errors. A panic inside one of Syncthing's goroutines still ends the process.
package bridge

import (
	"context"
	"crypto/rand"
	"encoding/base32"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/syncthing/syncthing/lib/config"
	"github.com/syncthing/syncthing/lib/events"
	"github.com/syncthing/syncthing/lib/locations"
	"github.com/syncthing/syncthing/lib/protocol"
	"github.com/syncthing/syncthing/lib/svcutil"
	"github.com/syncthing/syncthing/lib/syncthing"
)

// deleteRetention is what cmd/syncthing passes by default (15 months).
const deleteRetention = 10920 * time.Hour

// closeTimeout replaces protocol.CloseTimeout (10 s): a stop right after a
// new connection otherwise waits that long (engine-host-findings.md, e).
const closeTimeout = time.Second

// gateClosed is the folder minDiskFree that holds every download while the
// index keeps refreshing (sync-download-gate.md). The bridge writes it into
// every folder, and into the folder defaults, before each start outside a
// download job.
var gateClosed = config.Size{Value: 1000000, Unit: "TB"}

// guiUser is the GUI user force sets; see force.
const guiUser = "avoska"

var (
	mu       sync.Mutex
	instance *running
	// lastErr is why the last instance ended by itself, if it did.
	lastErr string
)

type running struct {
	app     *syncthing.App
	cancel  context.CancelFunc
	wg      sync.WaitGroup
	apiAddr string
	apiKey  string
	myID    string
}

func init() {
	// Warnings and errors only: at Info level every start also runs a 450 ms
	// hashing benchmark. gomobile sends stderr to logcat.
	slog.SetDefault(slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelWarn})))
	protocol.CloseTimeout = closeTimeout
}

// ErrRunning is returned by Start while an instance runs: the caller must
// Stop it first, so a session never inherits the other one's gate mode.
var ErrRunning = errors.New("syncthing is already running")

// Start runs Syncthing with its config, keys and database in home and returns
// once the REST API listens. It fails with ErrRunning if Syncthing already
// runs. Unless downloadJob is true, every folder's download gate is written
// closed before Syncthing starts. Only a download job may pass true.
func Start(home string, downloadJob bool) (err error) {
	defer recoverTo(&err)
	mu.Lock()
	defer mu.Unlock()
	if instance != nil {
		return ErrRunning
	}
	r, err := start(home, downloadJob)
	if err != nil {
		return err
	}
	instance = r
	lastErr = ""
	go watch(r)
	return nil
}

// Stop stops Syncthing and waits until it has exited. Partial downloads stay
// on disk. It does nothing if Syncthing is not running. The lock is held
// until the old instance is gone, so a Start cannot overlap it.
func Stop() (err error) {
	defer recoverTo(&err)
	mu.Lock()
	defer mu.Unlock()
	if r := instance; r != nil {
		instance = nil
		r.stop()
	}
	return nil
}

// Running reports whether Syncthing runs. It turns false when Syncthing
// stops by itself; Failure then says why.
func Running() bool {
	mu.Lock()
	defer mu.Unlock()
	return instance != nil
}

// Failure is why Syncthing last stopped by itself, or "".
func Failure() string {
	mu.Lock()
	defer mu.Unlock()
	return lastErr
}

// APIAddress is the REST API's host:port on 127.0.0.1, or "" when stopped.
func APIAddress() string { return field(func(r *running) string { return r.apiAddr }) }

// APIKey is the REST API key of the running instance, or "" when stopped.
// It is new on every start.
func APIKey() string { return field(func(r *running) string { return r.apiKey }) }

// DeviceID is this device's Syncthing ID, or "" when stopped.
func DeviceID() string { return field(func(r *running) string { return r.myID }) }

func field(get func(*running) string) string {
	mu.Lock()
	defer mu.Unlock()
	if instance == nil {
		return ""
	}
	return get(instance)
}

func recoverTo(err *error) {
	if p := recover(); p != nil {
		*err = fmt.Errorf("bridge panic: %v", p)
	}
}

// watch clears the instance when Syncthing exits without Stop, for example
// after a fatal error or a restart request over REST.
func watch(r *running) {
	status := r.app.Wait()
	mu.Lock()
	defer mu.Unlock()
	if instance != r {
		return // stopped through Stop
	}
	instance = nil
	if e := r.app.Error(); e != nil {
		lastErr = e.Error()
	} else {
		lastErr = fmt.Sprintf("Syncthing exited (%v)", status)
	}
	go r.shutdown()
}

func start(home string, downloadJob bool) (*running, error) {
	if home == "" {
		return nil, errors.New("empty home directory")
	}
	if err := os.MkdirAll(home, 0o700); err != nil {
		return nil, err
	}
	// locations is process-global, which is why only one instance can run.
	for _, base := range []locations.BaseDirEnum{locations.ConfigBaseDir, locations.DataBaseDir} {
		if err := locations.SetBaseDir(base, home); err != nil {
			return nil, err
		}
	}
	cert, err := syncthing.LoadOrGenerateCertificate(locations.Get(locations.CertFile), locations.Get(locations.KeyFile))
	if err != nil {
		return nil, fmt.Errorf("certificate: %w", err)
	}
	apiAddr, err := freeLoopbackAddr()
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	r := &running{
		cancel:  cancel,
		apiAddr: apiAddr,
		apiKey:  randomKey(),
		myID:    protocol.NewDeviceID(cert.Certificate[0]).String(),
	}
	evLogger := events.NewLogger()
	r.serve(ctx, evLogger)
	cfg, err := syncthing.LoadConfigAtStartup(locations.Get(locations.ConfigFile), cert, evLogger, false, true)
	if err != nil {
		r.shutdown()
		return nil, fmt.Errorf("config: %w", err)
	}
	r.serve(ctx, cfg)
	if err := force(cfg, r.apiAddr, r.apiKey, !downloadJob); err != nil {
		r.shutdown()
		return nil, fmt.Errorf("config: %w", err)
	}
	sdb, err := syncthing.OpenDatabase(locations.Get(locations.Database), deleteRetention)
	if err != nil {
		r.shutdown()
		return nil, fmt.Errorf("database: %w", err)
	}
	app, err := syncthing.New(cfg, sdb, evLogger, cert, syncthing.Options{NoUpgrade: true})
	if err != nil {
		sdb.Close()
		r.shutdown()
		return nil, err
	}
	r.app = app
	if err := app.Start(); err != nil {
		r.shutdown()
		return nil, err
	}
	return r, nil
}

// force writes the settings every start needs and saves them to config.xml,
// so the gate stays closed even if the process is killed later.
func force(cfg config.Wrapper, apiAddr, apiKey string, closeGate bool) error {
	// Without a GUI user and password Syncthing's auth is off, and any app on
	// the phone could get a CSRF cookie from "/" and use /rest. A user with a
	// random password nobody knows turns auth on, so the API key is the only
	// way in. The bcrypt hash is made once and kept in config.xml.
	var password string
	if gui := cfg.GUI(); gui.User != guiUser || gui.Password == "" || gui.AuthMode != config.AuthModeStatic {
		password = randomKey() + randomKey()
	}
	var hashErr error
	w, err := cfg.Modify(func(c *config.Configuration) {
		c.GUI.Enabled = true // the REST API runs only with the GUI enabled
		c.GUI.RawAddress = apiAddr
		c.GUI.RawUseTLS = false
		c.GUI.APIKey = apiKey
		if password != "" {
			c.GUI.User = guiUser
			c.GUI.AuthMode = config.AuthModeStatic
			hashErr = c.GUI.SetPassword(password)
		}
		o := &c.Options
		o.URAccepted = -1
		o.CREnabled = false
		o.AutoUpgradeIntervalH = 0
		o.StartBrowser = false
		if closeGate {
			c.Defaults.Folder.MinDiskFree = gateClosed
			for i := range c.Folders {
				c.Folders[i].MinDiskFree = gateClosed
			}
		}
	})
	if err != nil {
		return err
	}
	if hashErr != nil {
		return hashErr
	}
	w.Wait()
	return cfg.Save()
}

func (r *running) serve(ctx context.Context, s interface{ Serve(context.Context) error }) {
	r.wg.Add(1)
	go func() {
		defer r.wg.Done()
		_ = s.Serve(ctx)
	}()
}

// shutdown stops the event logger and the config wrapper.
func (r *running) shutdown() {
	r.cancel()
	r.wg.Wait()
}

// stop stops the app; lib/syncthing closes the database itself.
func (r *running) stop() {
	r.app.Stop(svcutil.ExitSuccess)
	r.app.Wait()
	r.shutdown()
}

func freeLoopbackAddr() (string, error) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return "", err
	}
	defer l.Close()
	return l.Addr().String(), nil
}

func randomKey() string {
	b := make([]byte, 20)
	_, _ = rand.Read(b)
	return strings.ToLower(base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(b))
}
