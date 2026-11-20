package computer

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// A driver runs the computer image somewhere: a microVM (microsandbox)
// or a container (Docker). The manager only ever starts, stops, lists
// and runs commands; everything on screen happens inside the image.
type driver interface {
	name() string
	available(ctx context.Context, image string) bool
	start(ctx context.Context, botID, image string, network bool) (string, error)
	// exec runs args inside the computer. env is KEY=VALUE.
	exec(ctx context.Context, id, user string, env []string, stdin []byte, detach bool, args ...string) (string, string, int, error)
	stop(ctx context.Context, id string)
	// pause freezes a running computer without discarding it; resume
	// unfreezes it. The guest keeps its windows and processes.
	pause(ctx context.Context, id string) error
	resume(ctx context.Context, id string) error
	list(ctx context.Context) []found
}

type found struct{ botID, id string }

// maxOutput caps what the server keeps of one command's stdout or stderr.
// A guest command can print without end (yes, cat /dev/zero); the guest's
// memory limit does not cover this side. Past the cap the output is still
// read and dropped, so the command is never blocked or cut short.
const maxOutput = 32 << 20

// capped keeps the first maxOutput bytes written to it. The buffer is a
// field, not embedded: an embedded bytes.Buffer brings its ReadFrom along,
// and exec's io.Copy would use that and skip the cap.
type capped struct{ buf bytes.Buffer }

func (c *capped) Write(p []byte) (int, error) {
	if room := maxOutput - c.buf.Len(); room > 0 {
		c.buf.Write(p[:min(len(p), room)])
	}
	return len(p), nil
}

func (c *capped) Len() int       { return c.buf.Len() }
func (c *capped) String() string { return c.buf.String() }

func run(ctx context.Context, bin string, stdin []byte, args ...string) (string, string, int, error) {
	cmd := exec.CommandContext(ctx, bin, args...)
	var out, errb capped
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

func name(botID string) string { return "gbot-computer-" + botID }

// hostTZ is this machine's time zone (e.g. "Europe/Berlin"), so the
// computer's wallpaper follows its owner's day. Empty when unknown.
func hostTZ() string {
	if tz := os.Getenv("TZ"); tz != "" {
		return strings.TrimPrefix(tz, ":")
	}
	if p, err := filepath.EvalSymlinks("/etc/localtime"); err == nil {
		if _, zone, ok := strings.Cut(p, "zoneinfo/"); ok {
			return zone
		}
	}
	return ""
}

// ---- microsandbox: one lightweight microVM per bot ----

type msbDriver struct{}

func (msbDriver) name() string { return "microsandbox" }

func (msbDriver) available(ctx context.Context, image string) bool {
	out, _, code, err := run(ctx, "msb", nil, "image", "ls", "--format", "json")
	if err != nil || code != 0 {
		return false
	}
	var imgs []struct {
		Reference string `json:"reference"`
	}
	_ = json.Unmarshal([]byte(out), &imgs)
	for _, i := range imgs {
		if i.Reference == image {
			return true
		}
	}
	return false
}

func (msbDriver) start(ctx context.Context, botID, image string, network bool) (string, error) {
	n := name(botID)
	args := []string{"create", image, "--name", n, "--label", "gbot.bot=" + botID,
		"-c", "2", "-m", "1536M", "-v", "gbot-home-" + botID + ":/home/gbot",
		"-u", "gbot", "--replace", "-q"}
	if !network {
		args = append(args, "--no-net")
	}
	if _, errOut, code, err := run(ctx, "msb", nil, args...); err != nil || code != 0 {
		return "", fmt.Errorf("microsandbox: %s%v", strings.TrimSpace(errOut), errOrNil(err))
	}
	// create returns before the guest agent accepts connections.
	ready := false
	for i := 0; i < 100; i++ {
		if _, _, code, err := run(ctx, "msb", nil, "exec", n, "--no-stdin", "--", "true"); err == nil && code == 0 {
			ready = true
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	if !ready {
		_, _, _, _ = run(ctx, "msb", nil, "rm", "--force", n)
		return "", errors.New("microsandbox: the computer's agent didn't start")
	}
	// create only boots the VM; the desktop runs as a managed job.
	desktop := []string{"exec", "-d", n, "-u", "gbot"}
	if tz := hostTZ(); tz != "" {
		desktop = append(desktop, "-e", "TZ="+tz)
	}
	if _, errOut, code, err := msbRun(ctx, nil, append(desktop, "--", "/usr/local/bin/gbot-desktop")...); err != nil || code != 0 {
		return "", fmt.Errorf("microsandbox: starting the desktop: %s%v", strings.TrimSpace(errOut), errOrNil(err))
	}
	return n, nil
}

// msbRun retries a command the guest agent refused while it was still
// coming up ("agent client error: handshake: early eof").
func msbRun(ctx context.Context, stdin []byte, args ...string) (string, string, int, error) {
	for attempt := 0; ; attempt++ {
		out, errOut, code, err := run(ctx, "msb", stdin, args...)
		why := errOut
		if err != nil {
			why += err.Error()
		}
		if attempt < 5 && (err != nil || code != 0) && transientAgent(why) {
			time.Sleep(time.Duration(attempt+1) * 150 * time.Millisecond)
			continue
		}
		return out, errOut, code, err
	}
}

func transientAgent(s string) bool {
	return strings.Contains(s, "agent client error") || strings.Contains(s, "handshake") || strings.Contains(s, "early eof")
}

func (msbDriver) exec(ctx context.Context, id, user string, env []string, stdin []byte, detach bool, args ...string) (string, string, int, error) {
	a := []string{"exec", id}
	if detach {
		a = append(a, "-d")
	}
	if stdin == nil {
		a = append(a, "--no-stdin")
	}
	if user != "" {
		a = append(a, "-u", user)
	}
	for _, e := range env {
		a = append(a, "-e", e)
	}
	return msbRun(ctx, stdin, append(append(a, "--"), args...)...)
}

func (msbDriver) stop(ctx context.Context, id string) {
	_, _, _, _ = run(ctx, "msb", nil, "rm", "--force", id)
}

func (msbDriver) pause(ctx context.Context, id string) error {
	return commandErr(run(ctx, "msb", nil, "pause", "-q", id))
}

func (msbDriver) resume(ctx context.Context, id string) error {
	return commandErr(run(ctx, "msb", nil, "resume", "-q", id))
}

func (msbDriver) list(ctx context.Context) []found {
	out, _, code, err := run(ctx, "msb", nil, "ls", "--running", "--format", "json")
	if err != nil || code != 0 {
		return nil
	}
	var boxes []struct {
		Name string `json:"name"`
	}
	_ = json.Unmarshal([]byte(out), &boxes)
	var fs []found
	for _, b := range boxes {
		if id := strings.TrimPrefix(b.Name, "gbot-computer-"); id != b.Name && safeID.MatchString(id) {
			fs = append(fs, found{id, b.Name})
		}
	}
	return fs
}

// ---- Docker: a hardened container per bot ----

type dockerDriver struct{}

func (dockerDriver) name() string { return "docker" }

func (dockerDriver) available(ctx context.Context, image string) bool {
	_, _, code, err := run(ctx, "docker", nil, "image", "inspect", "--format", "{{.Id}}", image)
	return err == nil && code == 0
}

func (dockerDriver) start(ctx context.Context, botID, image string, network bool) (string, error) {
	_, _, _, _ = run(ctx, "docker", nil, "rm", "-f", name(botID))
	args := []string{"run", "-d", "--name", name(botID), "--label", "gbot.bot=" + botID,
		"--init", "--cap-drop=ALL", "--security-opt", "no-new-privileges",
		"--pids-limit=512", "--memory=1536m", "--cpus=2", "--shm-size=512m",
		"--tmpfs", "/tmp:rw,nosuid,size=512m",
		"-v", "gbot-home-" + botID + ":/home/gbot"}
	if tz := hostTZ(); tz != "" {
		args = append(args, "-e", "TZ="+tz)
	}
	if !network {
		args = append(args, "--network", "none")
	}
	out, errOut, code, err := run(ctx, "docker", nil, append(args, image)...)
	if err != nil {
		return "", fmt.Errorf("docker: %w (is Docker running?)", err)
	}
	if code != 0 {
		return "", fmt.Errorf("docker run: %s", strings.TrimSpace(errOut))
	}
	return strings.TrimSpace(out), nil
}

func (dockerDriver) exec(ctx context.Context, id, user string, env []string, stdin []byte, detach bool, args ...string) (string, string, int, error) {
	a := []string{"exec"}
	if detach {
		a = append(a, "-d")
	}
	if stdin != nil {
		a = append(a, "-i")
	}
	if user != "" {
		a = append(a, "-u", user)
	}
	for _, e := range env {
		a = append(a, "-e", e)
	}
	return run(ctx, "docker", stdin, append(append(a, id), args...)...)
}

func (dockerDriver) stop(ctx context.Context, id string) {
	_, _, _, _ = run(ctx, "docker", nil, "rm", "-f", id)
}

func (dockerDriver) pause(ctx context.Context, id string) error {
	return commandErr(run(ctx, "docker", nil, "pause", id))
}

func (dockerDriver) resume(ctx context.Context, id string) error {
	return commandErr(run(ctx, "docker", nil, "unpause", id))
}

// commandErr turns a failed backend command into an error with its stderr.
func commandErr(out, errOut string, code int, err error) error {
	if err != nil {
		return err
	}
	if code != 0 {
		return errors.New(strings.TrimSpace(errOut))
	}
	return nil
}

func (dockerDriver) list(ctx context.Context) []found {
	out, _, code, err := run(ctx, "docker", nil, "ps", "--filter", "label=gbot.bot", "--format", `{{.ID}} {{.Label "gbot.bot"}}`)
	if err != nil || code != 0 {
		return nil
	}
	var fs []found
	for _, line := range strings.Split(strings.TrimSpace(out), "\n") {
		f := strings.Fields(line)
		if len(f) == 2 && safeID.MatchString(f[1]) {
			fs = append(fs, found{f[1], f[0]})
		}
	}
	return fs
}

func errOrNil(err error) string {
	if err == nil {
		return ""
	}
	return " (" + err.Error() + ")"
}

// pick chooses the driver: GBOT_COMPUTER_BACKEND, else microsandbox when
// it's installed, else Docker.
func pick(want string) driver {
	switch want {
	case "docker":
		return dockerDriver{}
	case "microsandbox", "msb":
		return msbDriver{}
	}
	if _, err := exec.LookPath("msb"); err == nil {
		return msbDriver{}
	}
	return dockerDriver{}
}
