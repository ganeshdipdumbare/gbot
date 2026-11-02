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
	list(ctx context.Context) []found
}

type found struct{ botID, id string }

func run(ctx context.Context, bin string, stdin []byte, args ...string) (string, string, int, error) {
	cmd := exec.CommandContext(ctx, bin, args...)
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
	// create only boots the VM; the desktop runs as a managed job.
	desktop := []string{"exec", "-d", n, "-u", "gbot"}
	if tz := hostTZ(); tz != "" {
		desktop = append(desktop, "-e", "TZ="+tz)
	}
	if _, errOut, code, err := run(ctx, "msb", nil, append(desktop, "--", "/usr/local/bin/gbot-desktop")...); err != nil || code != 0 {
		return "", fmt.Errorf("microsandbox: starting the desktop: %s%v", strings.TrimSpace(errOut), errOrNil(err))
	}
	return n, nil
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
	return run(ctx, "msb", stdin, append(append(a, "--"), args...)...)
}

func (msbDriver) stop(ctx context.Context, id string) {
	_, _, _, _ = run(ctx, "msb", nil, "rm", "--force", id)
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
