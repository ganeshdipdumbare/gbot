// Package computer gives each bot its own Linux desktop in a Docker
// container: a browser, a terminal and files. gbot talks to it only
// through `docker exec`, so a computer needs no open ports and has no
// network at all unless its owner turns network on.
package computer

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os/exec"
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
	Available  bool   `json:"available"` // Docker and the image are present
}

type box struct {
	id         string
	network    bool
	starting   bool
	controller string
	lastUsed   time.Time
	lastAgent  time.Time
}

type Manager struct {
	Image   string
	IdleTTL time.Duration
	log     *slog.Logger

	mu    sync.Mutex
	boxes map[string]*box
	// OnChange is told when a computer starts, stops or changes hands.
	OnChange func(botID string, st Status)
}

func New(image string, log *slog.Logger) *Manager {
	m := &Manager{Image: image, IdleTTL: 20 * time.Minute, log: log, boxes: map[string]*box{}}
	go m.reap()
	return m
}

var safeID = regexp.MustCompile(`^[a-f0-9-]{36}$`)

func name(botID string) string { return "gbot-computer-" + botID }

func docker(ctx context.Context, stdin []byte, args ...string) (string, string, int, error) {
	cmd := exec.CommandContext(ctx, "docker", args...)
	var out, errb bytes.Buffer
	cmd.Stdout, cmd.Stderr = &out, &errb
	if stdin != nil {
		cmd.Stdin = bytes.NewReader(stdin)
	}
	err := cmd.Run()
	code := 0
	var ee *exec.ExitError
	if errors.As(err, &ee) {
		code, err = ee.ExitCode(), nil
	}
	return out.String(), errb.String(), code, err
}

// Available reports whether Docker runs and the computer image exists.
func (m *Manager) Available(ctx context.Context) bool {
	c, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	_, _, code, err := docker(c, nil, "image", "inspect", "--format", "{{.Id}}", m.Image)
	return err == nil && code == 0
}

func (m *Manager) Status(ctx context.Context, botID string) Status {
	m.mu.Lock()
	b := m.boxes[botID]
	st := Status{State: "off", Controller: "bot", Image: m.Image}
	if b != nil {
		st.State, st.Controller, st.Network = "on", b.controller, b.network
		if b.starting {
			st.State = "starting"
		}
	}
	m.mu.Unlock()
	if st.State == "off" {
		st.Available = m.Available(ctx)
	} else {
		st.Available = true
	}
	return st
}

func (m *Manager) changed(botID string) {
	if m.OnChange != nil {
		m.OnChange(botID, m.Status(context.Background(), botID))
	}
}

// Ensure starts the bot's computer if needed. network=false gives the
// container no network interface at all.
func (m *Manager) Ensure(ctx context.Context, botID string, network bool) error {
	if !safeID.MatchString(botID) {
		return errors.New("bad bot id")
	}
	m.mu.Lock()
	if b := m.boxes[botID]; b != nil && !b.starting {
		if b.network == network {
			b.lastUsed = time.Now()
			m.mu.Unlock()
			return nil
		}
		m.mu.Unlock()
		m.Stop(context.Background(), botID) // network setting changed: recreate
		m.mu.Lock()
	}
	if b := m.boxes[botID]; b != nil && b.starting {
		m.mu.Unlock()
		for i := 0; i < 100; i++ {
			time.Sleep(100 * time.Millisecond)
			m.mu.Lock()
			ok := m.boxes[botID] != nil && !m.boxes[botID].starting
			m.mu.Unlock()
			if ok {
				return nil
			}
		}
		return errors.New("the computer is taking too long to start")
	}
	m.boxes[botID] = &box{starting: true, network: network, controller: "bot", lastUsed: time.Now()}
	m.mu.Unlock()
	m.changed(botID)

	id, err := m.start(ctx, botID, network)
	m.mu.Lock()
	if err != nil {
		delete(m.boxes, botID)
	} else {
		m.boxes[botID].id, m.boxes[botID].starting = id, false
	}
	m.mu.Unlock()
	m.changed(botID)
	return err
}

func (m *Manager) start(ctx context.Context, botID string, network bool) (string, error) {
	// A stopped container from before keeps nothing we need; the home
	// volume holds the bot's files.
	_, _, _, _ = docker(ctx, nil, "rm", "-f", name(botID))
	args := []string{"run", "-d", "--name", name(botID),
		"--label", "gbot.bot=" + botID,
		"--init", "--cap-drop=ALL", "--security-opt", "no-new-privileges",
		"--pids-limit=512", "--memory=1536m", "--cpus=1.5", "--shm-size=512m",
		"--tmpfs", "/tmp:rw,nosuid,size=512m",
		"-v", "gbot-home-" + botID + ":/home/gbot",
	}
	if !network {
		args = append(args, "--network", "none")
	}
	args = append(args, m.Image)
	out, errOut, code, err := docker(ctx, nil, args...)
	if err != nil {
		return "", fmt.Errorf("docker: %w (is Docker running?)", err)
	}
	if code != 0 {
		if strings.Contains(errOut, "Unable to find image") || strings.Contains(errOut, "pull access denied") {
			return "", fmt.Errorf("the computer image %s isn't built yet: run `make computer`", m.Image)
		}
		return "", fmt.Errorf("docker run: %s", strings.TrimSpace(errOut))
	}
	id := strings.TrimSpace(out)
	// Wait for the virtual screen.
	for i := 0; i < 50; i++ {
		_, _, c, _ := docker(ctx, nil, "exec", id, "xdpyinfo", "-display", ":1")
		if c == 0 {
			break
		}
		time.Sleep(150 * time.Millisecond)
	}
	m.log.Info("computer started", "bot", botID, "network", network)
	return id, nil
}

func (m *Manager) Stop(ctx context.Context, botID string) {
	m.mu.Lock()
	b := m.boxes[botID]
	delete(m.boxes, botID)
	m.mu.Unlock()
	if b == nil {
		return
	}
	_, _, _, _ = docker(ctx, nil, "rm", "-f", name(botID))
	m.log.Info("computer stopped", "bot", botID)
	m.changed(botID)
}

func (m *Manager) container(botID string, forAgent bool) (string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	b := m.boxes[botID]
	if b == nil || b.starting {
		return "", ErrNotRunning
	}
	if forAgent && b.controller == "user" {
		return "", ErrUserControl
	}
	b.lastUsed = time.Now()
	if forAgent {
		b.lastAgent = time.Now()
	}
	return b.id, nil
}

// Active reports whether the bot used its computer in the last minute.
func (m *Manager) Active(botID string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	b := m.boxes[botID]
	return b != nil && (b.starting || time.Since(b.lastAgent) < time.Minute || b.controller == "user")
}

type ExecResult struct {
	Stdout, Stderr string
	Code           int
}

// Exec runs a shell command as the computer's unprivileged user.
func (m *Manager) Exec(ctx context.Context, botID, command string, timeout time.Duration) (*ExecResult, error) {
	return m.exec(ctx, botID, command, timeout, nil)
}

func (m *Manager) exec(ctx context.Context, botID, command string, timeout time.Duration, env []string) (*ExecResult, error) {
	id, err := m.container(botID, true)
	if err != nil {
		return nil, err
	}
	c, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	args := []string{"exec", "-w", "/home/gbot"}
	for _, e := range env {
		args = append(args, "-e", e)
	}
	out, errOut, code, err := docker(c, nil, append(args, id, "bash", "-lc", command)...)
	if c.Err() != nil {
		return &ExecResult{out, errOut + "\n(timed out)", 124}, nil
	}
	return &ExecResult{out, errOut, code}, err
}

func (m *Manager) WriteFile(ctx context.Context, botID, path string, content []byte) error {
	id, err := m.container(botID, true)
	if err != nil {
		return err
	}
	_, errOut, code, err := docker(ctx, content, "exec", "-i", "-w", "/home/gbot", id, "sh", "-c", `mkdir -p "$(dirname "$1")" && cat > "$1"`, "sh", path)
	if err == nil && code != 0 {
		err = errors.New(strings.TrimSpace(errOut))
	}
	return err
}

func (m *Manager) ReadFile(ctx context.Context, botID, path string, max int) (string, error) {
	id, err := m.container(botID, true)
	if err != nil {
		return "", err
	}
	out, errOut, code, err := docker(ctx, nil, "exec", "-w", "/home/gbot", id, "head", "-c", strconv.Itoa(max), "--", path)
	if err == nil && code != 0 {
		err = errors.New(strings.TrimSpace(errOut))
	}
	return out, err
}

// OpenBrowser shows a page on the computer's screen.
func (m *Manager) OpenBrowser(ctx context.Context, botID, url string) error {
	id, err := m.container(botID, true)
	if err != nil {
		return err
	}
	_, _, _, _ = docker(ctx, nil, "exec", id, "pkill", "-f", "chromium")
	_, errOut, code, err := docker(ctx, nil, "exec", "-d", id, "chromium", "--no-sandbox", "--no-first-run",
		"--disable-dev-shm-usage", "--no-default-browser-check", "--window-position=0,0",
		fmt.Sprintf("--window-size=%d,%d", ScreenW, ScreenH), "--user-data-dir=/home/gbot/.browser", url)
	if err == nil && code != 0 {
		err = errors.New(strings.TrimSpace(errOut))
	}
	return err
}

// ReadPage fetches a page with headless Chromium and returns its text.
func (m *Manager) ReadPage(ctx context.Context, botID, url string) (string, error) {
	r, err := m.exec(ctx, botID, `chromium --headless --no-sandbox --disable-gpu --dump-dom "$GBOT_URL" 2>/dev/null | python3 -c '
import sys, html, re
s = sys.stdin.read()
s = re.sub(r"(?is)<(script|style|noscript|svg)[^>]*>.*?</\1>", " ", s)
s = re.sub(r"(?s)<[^>]+>", " ", s)
print(re.sub(r"\s+", " ", html.unescape(s)).strip()[:12000])'`, 45*time.Second, []string{"GBOT_URL=" + url})
	return pageText(r), err
}

func pageText(r *ExecResult) string {
	if r == nil {
		return ""
	}
	return r.Stdout
}

// Screenshot returns the screen as a JPEG.
func (m *Manager) Screenshot(ctx context.Context, botID string) ([]byte, error) {
	id, err := m.container(botID, false)
	if err != nil {
		return nil, err
	}
	c, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	cmd := exec.CommandContext(c, "docker", "exec", "-e", "DISPLAY=:1", id, "import", "-silent", "-window", "root", "-quality", "72", "jpg:-")
	var out, errb bytes.Buffer
	cmd.Stdout, cmd.Stderr = &out, &errb
	if err := cmd.Run(); err != nil {
		return nil, fmt.Errorf("screenshot: %v %s", err, strings.TrimSpace(errb.String()))
	}
	return out.Bytes(), nil
}

// TakeControl hands the screen to the person (true) or back to the bot.
func (m *Manager) TakeControl(botID string, user bool) error {
	m.mu.Lock()
	b := m.boxes[botID]
	if b == nil || b.starting {
		m.mu.Unlock()
		return ErrNotRunning
	}
	if user {
		b.controller = "user"
	} else {
		b.controller = "bot"
	}
	m.mu.Unlock()
	m.changed(botID)
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
func (m *Manager) Send(ctx context.Context, botID string, in Input) error {
	m.mu.Lock()
	b := m.boxes[botID]
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
	_, errOut, code, err := docker(ctx, nil, append([]string{"exec", "-e", "DISPLAY=:1", id, "xdotool"}, args...)...)
	if err == nil && code != 0 {
		err = errors.New(strings.TrimSpace(errOut))
	}
	return err
}

// Adopt finds computers left running by a previous server process.
func (m *Manager) Adopt(ctx context.Context) {
	out, _, code, err := docker(ctx, nil, "ps", "--filter", "label=gbot.bot", "--format", `{{.ID}} {{.Label "gbot.bot"}} {{.Networks}}`)
	if err != nil || code != 0 {
		return
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, line := range strings.Split(strings.TrimSpace(out), "\n") {
		f := strings.Fields(line)
		if len(f) < 2 || !safeID.MatchString(f[1]) {
			continue
		}
		m.boxes[f[1]] = &box{id: f[0], network: len(f) > 2 && f[2] != "none", controller: "bot", lastUsed: time.Now()}
	}
}

// reap stops computers nobody has used for IdleTTL. Files stay in the
// bot's home volume for next time.
func (m *Manager) reap() {
	for range time.Tick(time.Minute) {
		m.mu.Lock()
		var idle []string
		for id, b := range m.boxes {
			if !b.starting && b.controller == "bot" && time.Since(b.lastUsed) > m.IdleTTL {
				idle = append(idle, id)
			}
		}
		m.mu.Unlock()
		for _, id := range idle {
			m.Stop(context.Background(), id)
		}
	}
}
