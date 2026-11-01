package computer

import (
	"bytes"
	"context"
	"io"
	"log/slog"
	"strings"
	"testing"
	"time"
)

// Runs against the real gbot-computer image on each installed backend;
// skipped where the image isn't built.
func TestComputer(t *testing.T) {
	for _, backend := range []string{"microsandbox", "docker"} {
		t.Run(backend, func(t *testing.T) { testComputer(t, backend) })
	}
}

func testComputer(t *testing.T, backend string) {
	m := New("gbot-computer:latest", backend, slog.New(slog.NewTextHandler(io.Discard, nil)))
	ctx := context.Background()
	if !m.Available(ctx) {
		t.Skip("gbot-computer image not available on " + backend + " (make computer)")
	}
	bot := "00000000-0000-4000-8000-000000000001"
	defer m.Stop(ctx, bot)
	if err := m.Ensure(ctx, bot, false); err != nil {
		t.Fatal(err)
	}
	r, err := m.Exec(ctx, bot, "whoami && echo hi > note.txt && cat note.txt", 10*time.Second)
	if err != nil || r.Code != 0 || !strings.Contains(r.Stdout, "gbot\nhi") {
		t.Fatalf("exec: %+v %v", r, err)
	}
	if r, _ := m.Exec(ctx, bot, "curl -s -m 3 -o /dev/null https://example.com && echo reachable || echo blocked", 10*time.Second); strings.TrimSpace(r.Stdout) != "blocked" {
		t.Fatalf("computer without network can reach the network: %q", r.Stdout)
	}
	if r, _ := m.Exec(ctx, bot, "cat /tmp/gbot/activity.log", 10*time.Second); !strings.Contains(r.Stdout, "echo hi > note.txt") {
		t.Fatalf("command not shown on the Activity screen: %q", r.Stdout)
	}
	if err := m.WriteFile(ctx, bot, "dir/a.md", []byte("# hello")); err != nil {
		t.Fatal(err)
	}
	if s, err := m.ReadFile(ctx, bot, "dir/a.md", 100); err != nil || s != "# hello" {
		t.Fatalf("read: %q %v", s, err)
	}
	img, err := m.Screenshot(ctx, bot)
	if err != nil || !bytes.HasPrefix(img, []byte{0xFF, 0xD8}) {
		t.Fatalf("screenshot: %d bytes, %v", len(img), err)
	}
	if err := m.Send(ctx, bot, Input{Type: "click", X: .5, Y: .5}); err == nil {
		t.Fatal("input allowed without taking control")
	}
	if err := m.TakeControl(bot, true); err != nil {
		t.Fatal(err)
	}
	if _, err := m.Exec(ctx, bot, "true", time.Second); err != ErrUserControl {
		t.Fatalf("bot used the computer during takeover: %v", err)
	}
	if err := m.Send(ctx, bot, Input{Type: "click", X: .5, Y: .5}); err != nil {
		t.Fatal(err)
	}
	if err := m.Send(ctx, bot, Input{Type: "key", Key: "ctrl+a; rm -rf /"}); err == nil {
		t.Fatal("unsafe key name accepted")
	}
}
