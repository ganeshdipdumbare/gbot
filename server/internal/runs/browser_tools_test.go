package runs

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"strings"
	"testing"

	"github.com/ganeshdipdumbare/kritrim"

	"gbot/internal/computer"
	"gbot/internal/store"
)

// The browser tools are registered only when a computer is configured,
// and their read-only and taint classification is what keeps clicks and
// typing behind a question after a page's text has been read.
func TestBrowserTools(t *testing.T) {
	r := &Runtime{computer: computer.New("gbot-computer:latest", "docker", slog.New(slog.NewTextHandler(io.Discard, nil)))}
	bot := &store.Bot{ID: "00000000-0000-4000-8000-000000000001", OwnerID: "00000000-0000-4000-8000-000000000002"}
	byName := map[string]kritrim.Tool{}
	for _, tl := range r.runTools(context.Background(), &Job{Bot: bot, For: bot.OwnerID}) {
		byName[tl.Name] = tl
	}
	for _, name := range []string{"browser_navigate", "browser_back", "browser_forward", "browser_reload", "browser_cookies",
		"browser_snapshot", "browser_scroll", "browser_wait", "browser_click", "browser_type", "browser_press",
		"browser_hover", "browser_check"} {
		if _, ok := byName[name]; !ok {
			t.Fatalf("%s not registered", name)
		}
	}
	for _, name := range []string{"browser_snapshot", "browser_scroll", "browser_wait", "browser_hover"} {
		if !readonlyTool(name) {
			t.Errorf("%s only reads the page and should not need approval", name)
		}
	}
	// One browser, one session: the old open/headless-read pair would
	// browse with a session that has none of the person's cookies.
	for _, name := range []string{"browser_open", "browser_read"} {
		if _, ok := byName[name]; ok {
			t.Fatalf("%s should not be registered", name)
		}
	}
	for _, name := range []string{"browser_navigate", "browser_back", "browser_forward", "browser_reload", "browser_snapshot"} {
		if !untrustedTool(name) {
			t.Errorf("%s marks the run as having read outside text", name)
		}
		if !readonlyTool(name) {
			t.Errorf("%s only loads or reads a page: it must be read-only", name)
		}
	}
	for _, name := range []string{"browser_click", "browser_type", "browser_press", "browser_check", "browser_cookies"} {
		if readonlyTool(name) {
			t.Errorf("%s changes the page and must need approval", name)
		}
	}
	schema := func(name string) string {
		b, _ := json.Marshal(byName[name].Schema)
		return string(b)
	}
	if s := schema("browser_type"); !strings.Contains(s, `"required":["ref","text"]`) {
		t.Errorf("browser_type should require ref and text, schema: %s", s)
	}
	if s := schema("browser_click"); !strings.Contains(s, `"required":["ref"]`) {
		t.Errorf("browser_click should require ref, schema: %s", s)
	}
}
