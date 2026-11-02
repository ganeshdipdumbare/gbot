// Package computer gives each bot its own Linux desktop, in the style of
// Rakazo's: a wallpaper and a dock with a browser and a terminal, both
// closed until the bot needs them; the Terminal shows everything the bot
// runs. It runs as a microVM (microsandbox) or a container (Docker). gbot only runs commands inside
// it, so a computer opens no ports and has no network at all unless its
// owner turns network on.
package computer

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	ScreenW = 1280
	ScreenH = 800
)

var ErrNotRunning = errors.New("the computer is not running")
var ErrUserControl = errors.New("the person has control of the computer right now; wait for them to hand it back")

type Status struct {
	State      string `json:"state"`      // off | starting | on
	Controller string `json:"controller"` // bot | user
	Network    bool   `json:"network"`
	Image      string `json:"image"`
	Backend    string `json:"backend"`   // microsandbox | docker
	Available  bool   `json:"available"` // the backend and the image are present
}

// Owner is whose computer it is: one person's computer with one bot. A
// shared bot gets a separate computer for each person who uses it, so
// one person's files and sign-ins are never visible to another.
type Owner struct{ Bot, User string }

// Key names the computer in the backend. It's a UUID-shaped hash of the
// pair, so the same person and bot always get the same computer.
func (o Owner) Key() string {
	h := sha256.Sum256([]byte(o.Bot + "/" + o.User))
	x := hex.EncodeToString(h[:16])
	return x[0:8] + "-" + x[8:12] + "-" + x[12:16] + "-" + x[16:20] + "-" + x[20:32]
}

type box struct {
	owner      Owner
	id         string
	network    bool
	starting   bool
	controller string
	lastUsed   time.Time
}

type Manager struct {
	Image   string
	IdleTTL time.Duration
	log     *slog.Logger
	d       driver

	mu    sync.Mutex
	boxes map[string]*box
	// OnChange is told when a computer starts, stops or changes hands.
	OnChange func(o Owner, st Status)
}

// New picks a backend: "microsandbox", "docker", or "" for microsandbox
// when it's installed and Docker otherwise.
func New(image, backend string, log *slog.Logger) *Manager {
	m := &Manager{Image: image, IdleTTL: 20 * time.Minute, log: log, d: pick(backend), boxes: map[string]*box{}}
	go m.reap()
	return m
}

func (m *Manager) Backend() string { return m.d.name() }

var safeID = regexp.MustCompile(`^[a-f0-9-]{36}$`)

// Available reports whether the backend runs and has the computer image.
func (m *Manager) Available(ctx context.Context) bool {
	c, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	return m.d.available(c, m.Image)
}

func (m *Manager) Status(ctx context.Context, o Owner) Status {
	k := o.Key()
	m.mu.Lock()
	b := m.boxes[k]
	st := Status{State: "off", Controller: "bot", Image: m.Image, Backend: m.d.name(), Available: true}
	if b != nil {
		st.State, st.Controller, st.Network = "on", b.controller, b.network
		if b.starting {
			st.State = "starting"
		}
	}
	m.mu.Unlock()
	if st.State == "off" {
		st.Available = m.Available(ctx)
	}
	return st
}

func (m *Manager) changed(o Owner) {
	if m.OnChange != nil {
		m.OnChange(o, m.Status(context.Background(), o))
	}
}

// Ensure starts the bot's computer if needed. network=false gives it no
// network at all.
func (m *Manager) Ensure(ctx context.Context, o Owner, network bool) error {
	k := o.Key()
	if !safeID.MatchString(k) {
		return errors.New("bad bot id")
	}
	m.mu.Lock()
	if b := m.boxes[k]; b != nil && !b.starting {
		if b.network == network {
			b.lastUsed = time.Now()
			m.mu.Unlock()
			return nil
		}
		m.mu.Unlock()
		m.stopKey(context.Background(), k) // network setting changed: recreate
		m.mu.Lock()
	}
	if b := m.boxes[k]; b != nil && b.starting {
		m.mu.Unlock()
		for i := 0; i < 300; i++ {
			time.Sleep(100 * time.Millisecond)
			m.mu.Lock()
			ok := m.boxes[k] != nil && !m.boxes[k].starting
			gone := m.boxes[k] == nil
			m.mu.Unlock()
			if ok {
				return nil
			}
			if gone {
				return errors.New("the computer failed to start")
			}
		}
		return errors.New("the computer is taking too long to start")
	}
	m.boxes[k] = &box{owner: o, starting: true, network: network, controller: "bot", lastUsed: time.Now()}
	m.mu.Unlock()
	m.changed(o)

	id, err := m.start(ctx, k, network)
	m.mu.Lock()
	if err != nil {
		delete(m.boxes, k)
	} else {
		m.boxes[k].id, m.boxes[k].starting = id, false
	}
	m.mu.Unlock()
	m.changed(o)
	return err
}

func (m *Manager) start(ctx context.Context, k string, network bool) (string, error) {
	if !m.Available(ctx) {
		return "", fmt.Errorf("the computer image %s isn't built for %s yet: run `make computer`", m.Image, m.d.name())
	}
	begin := time.Now()
	id, err := m.d.start(ctx, k, m.Image, network)
	if err != nil {
		return "", err
	}
	// Wait for the virtual screen.
	for i := 0; i < 100; i++ {
		_, _, c, _ := m.d.exec(ctx, id, "gbot", []string{"DISPLAY=:1"}, nil, false, "xdpyinfo")
		if c == 0 {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	m.log.Info("computer started", "computer", k, "backend", m.d.name(), "network", network, "took", time.Since(begin).Round(time.Millisecond))
	return id, nil
}

// Stop turns one person's computer with a bot off.
func (m *Manager) Stop(ctx context.Context, o Owner) { m.stopKey(ctx, o.Key()) }

// StopBot turns off every person's computer with a bot, e.g. when the bot
// is deleted.
func (m *Manager) StopBot(ctx context.Context, botID string) {
	m.mu.Lock()
	var keys []string
	for k, b := range m.boxes {
		if b.owner.Bot == botID {
			keys = append(keys, k)
		}
	}
	m.mu.Unlock()
	for _, k := range keys {
		m.stopKey(ctx, k)
	}
}

func (m *Manager) stopKey(ctx context.Context, k string) {
	m.mu.Lock()
	b := m.boxes[k]
	delete(m.boxes, k)
	m.mu.Unlock()
	if b == nil {
		return
	}
	if b.id != "" {
		m.d.stop(ctx, b.id)
	}
	m.log.Info("computer stopped", "computer", k)
	m.changed(b.owner)
}

func (m *Manager) container(o Owner, forAgent bool) (string, error) {
	k := o.Key()
	m.mu.Lock()
	defer m.mu.Unlock()
	b := m.boxes[k]
	if b == nil || b.starting {
		return "", ErrNotRunning
	}
	if forAgent && b.controller == "user" {
		return "", ErrUserControl
	}
	b.lastUsed = time.Now()
	return b.id, nil
}

// Active reports whether one person's computer with a bot is on or starting.
func (m *Manager) Active(o Owner) bool {
	k := o.Key()
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.boxes[k] != nil
}

// ActiveBot reports whether any person's computer with the bot is on.
func (m *Manager) ActiveBot(botID string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, b := range m.boxes {
		if b.owner.Bot == botID {
			return true
		}
	}
	return false
}

// Running lists the computers that are on or starting. Computers adopted
// from a previous server process have no known owner until used again;
// their Bot is empty.
func (m *Manager) Running() []Owner {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]Owner, 0, len(m.boxes))
	for _, b := range m.boxes {
		out = append(out, b.owner)
	}
	return out
}

type ExecResult struct {
	Stdout, Stderr string
	Code           int
}

// Exec runs a shell command as the computer's unprivileged user. It also
// appears in the Terminal on the computer's screen, which opens for it.
func (m *Manager) Exec(ctx context.Context, o Owner, command string, timeout time.Duration) (*ExecResult, error) {
	id, err := m.container(o, true)
	if err != nil {
		return nil, err
	}
	c, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	out, errOut, code, err := m.d.exec(c, id, "gbot", []string{"GBOT_CMD=" + command}, nil, false, "/usr/local/bin/gbot-run")
	if c.Err() != nil {
		return &ExecResult{out, errOut + "\n(timed out)", 124}, nil
	}
	return &ExecResult{out, errOut, code}, err
}

// note adds a line to the Terminal's activity stream.
func (m *Manager) note(ctx context.Context, id, symbol, color, text string) {
	line := fmt.Sprintf("\n\033[38;5;%sm%s\033[0m %s\n", color, symbol, text)
	_, _, _, _ = m.d.exec(ctx, id, "gbot", nil, []byte(line), false, "sh", "-c", "cat >> /tmp/gbot/activity.log")
}

func (m *Manager) WriteFile(ctx context.Context, o Owner, path string, content []byte) error {
	id, err := m.container(o, true)
	if err != nil {
		return err
	}
	_, errOut, code, err := m.d.exec(ctx, id, "gbot", nil, content, false, "sh", "-c", `cd "$HOME" && mkdir -p "$(dirname "$1")" && cat > "$1"`, "sh", path)
	if err == nil && code != 0 {
		err = errors.New(strings.TrimSpace(errOut))
	}
	if err == nil {
		m.note(ctx, id, "✎", "114", fmt.Sprintf("Wrote %s (%d bytes)", path, len(content)))
		m.surface(ctx, id)
	}
	return err
}

func (m *Manager) ReadFile(ctx context.Context, o Owner, path string, max int) (string, error) {
	id, err := m.container(o, true)
	if err != nil {
		return "", err
	}
	out, errOut, code, err := m.d.exec(ctx, id, "gbot", nil, nil, false, "sh", "-c", `cd "$HOME" && head -c "$1" -- "$2"`, "sh", strconv.Itoa(max), path)
	if err == nil && code != 0 {
		err = errors.New(strings.TrimSpace(errOut))
	}
	if err == nil {
		m.note(ctx, id, "◇", "110", "Read "+path)
		m.surface(ctx, id)
	}
	return out, err
}

// surface opens the Terminal (or brings it forward) so the person
// watching sees the bot's file work as it happens.
func (m *Manager) surface(ctx context.Context, id string) {
	_, _, _, _ = m.d.exec(ctx, id, "gbot", []string{"DISPLAY=:1"}, nil, true, "/usr/local/bin/gbot-terminal")
}

// Show opens one app on the screen, or brings it forward: the browser or the Terminal.
func (m *Manager) Show(ctx context.Context, o Owner, app string) error {
	script, ok := map[string]string{
		"browser":  "/usr/local/bin/gbot-browser",
		"terminal": "/usr/local/bin/gbot-terminal",
	}[app]
	if !ok {
		return errors.New("unknown app")
	}
	id, err := m.container(o, false)
	if err != nil {
		return err
	}
	_, errOut, code, err := m.d.exec(ctx, id, "gbot", []string{"DISPLAY=:1"}, nil, true, script)
	if err == nil && code != 0 {
		err = errors.New(strings.TrimSpace(errOut))
	}
	return err
}

// OpenBrowser shows a page on the computer's screen, maximized.
func (m *Manager) OpenBrowser(ctx context.Context, o Owner, url string) error {
	id, err := m.container(o, true)
	if err != nil {
		return err
	}
	_, errOut, code, err := m.d.exec(ctx, id, "gbot", []string{"DISPLAY=:1"}, nil, true, "/usr/local/bin/gbot-browser", url)
	if err == nil && code != 0 {
		err = errors.New(strings.TrimSpace(errOut))
	}
	return err
}

// ReadPage fetches a page with headless Chromium and returns its text.
func (m *Manager) ReadPage(ctx context.Context, o Owner, url string) (string, error) {
	id, err := m.container(o, true)
	if err != nil {
		return "", err
	}
	m.note(ctx, id, "◍", "111", "Reading "+url)
	c, cancel := context.WithTimeout(ctx, 45*time.Second)
	defer cancel()
	out, _, _, err := m.d.exec(c, id, "gbot", []string{"GBOT_URL=" + url}, nil, false, "bash", "-c", `chromium --headless --no-sandbox --disable-gpu --dump-dom "$GBOT_URL" 2>/dev/null | python3 -c '
import sys, html, re
s = sys.stdin.read()
s = re.sub(r"(?is)<(script|style|noscript|svg)[^>]*>.*?</\1>", " ", s)
s = re.sub(r"(?s)<[^>]+>", " ", s)
print(re.sub(r"\s+", " ", html.unescape(s)).strip()[:12000])'`)
	return out, err
}

// Screenshot returns the screen as a JPEG.
func (m *Manager) Screenshot(ctx context.Context, o Owner) ([]byte, error) {
	id, err := m.container(o, false)
	if err != nil {
		return nil, err
	}
	c, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	out, errOut, code, err := m.d.exec(c, id, "gbot", []string{"DISPLAY=:1"}, nil, false, "/usr/local/bin/gbot-capture", "72")
	if err != nil || code != 0 {
		return nil, fmt.Errorf("screenshot: %s%s", strings.TrimSpace(errOut), errOrNil(err))
	}
	return []byte(out), nil
}

// TakeControl hands the screen to the person (true) or back to the bot.
func (m *Manager) TakeControl(o Owner, user bool) error {
	k := o.Key()
	m.mu.Lock()
	b := m.boxes[k]
	if b == nil || b.starting {
		m.mu.Unlock()
		return ErrNotRunning
	}
	if user {
		b.controller = "user"
	} else {
		b.controller = "bot"
	}
	id := b.id
	m.mu.Unlock()
	if user {
		m.note(context.Background(), id, "◐", "214", "You have control")
	} else {
		m.note(context.Background(), id, "◑", "141", "Control handed back")
	}
	m.changed(o)
	return nil
}

type Input struct {
	Type   string  `json:"type"` // click | dblclick | rightclick | move | type | key | scroll
	X      float64 `json:"x"`    // 0..1 of the screen
	Y      float64 `json:"y"`
	Text   string  `json:"text,omitempty"`
	Key    string  `json:"key,omitempty"`
	DeltaY float64 `json:"deltaY,omitempty"`
}

var keyName = regexp.MustCompile(`^[A-Za-z0-9_+]{1,40}$`)

// Send replays the person's input on the screen. Only allowed while the
// person has control.
func (m *Manager) Send(ctx context.Context, o Owner, in Input) error {
	k := o.Key()
	m.mu.Lock()
	b := m.boxes[k]
	if b == nil || b.starting {
		m.mu.Unlock()
		return ErrNotRunning
	}
	if b.controller != "user" {
		m.mu.Unlock()
		return errors.New("take control first")
	}
	id := b.id
	b.lastUsed = time.Now()
	m.mu.Unlock()

	x := strconv.Itoa(int(min(max(in.X, 0), 1) * ScreenW))
	y := strconv.Itoa(int(min(max(in.Y, 0), 1) * ScreenH))
	var args []string
	switch in.Type {
	case "click":
		args = []string{"mousemove", x, y, "click", "1"}
	case "dblclick":
		args = []string{"mousemove", x, y, "click", "--repeat", "2", "1"}
	case "rightclick":
		args = []string{"mousemove", x, y, "click", "3"}
	case "move":
		args = []string{"mousemove", x, y}
	case "scroll":
		btn := "5"
		if in.DeltaY < 0 {
			btn = "4"
		}
		args = []string{"mousemove", x, y, "click", "--repeat", "3", btn}
	case "type":
		if len(in.Text) > 2000 {
			return errors.New("text too long")
		}
		args = []string{"type", "--delay", "8", "--", in.Text}
	case "key":
		if !keyName.MatchString(in.Key) {
			return errors.New("bad key")
		}
		args = []string{"key", "--", in.Key}
	default:
		return errors.New("unknown input")
	}
	_, errOut, code, err := m.d.exec(ctx, id, "gbot", []string{"DISPLAY=:1"}, nil, false, append([]string{"xdotool"}, args...)...)
	if err == nil && code != 0 {
		err = errors.New(strings.TrimSpace(errOut))
	}
	return err
}

// Adopt finds computers left running by a previous server process.
func (m *Manager) Adopt(ctx context.Context) {
	fs := m.d.list(ctx)
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, f := range fs {
		m.boxes[f.botID] = &box{id: f.id, controller: "bot", lastUsed: time.Now()}
	}
}

// reap stops computers nobody has used for IdleTTL. Files stay in the
// bot's home volume for next time.
func (m *Manager) reap() {
	for range time.Tick(time.Minute) {
		m.mu.Lock()
		var idle []string
		for k, b := range m.boxes {
			if !b.starting && b.controller == "bot" && time.Since(b.lastUsed) > m.IdleTTL {
				idle = append(idle, k)
			}
		}
		m.mu.Unlock()
		for _, k := range idle {
			m.stopKey(context.Background(), k)
		}
	}
}
