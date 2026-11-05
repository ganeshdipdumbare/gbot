package runs

import (
	"encoding/json"
	"io"
	"log/slog"
	"strings"
	"testing"

	"github.com/ganeshdipdumbare/kiln/sdk"

	"gbot/internal/computer"
	"gbot/internal/store"
)

// The browser tools are registered only when a computer is configured,
// and their approval metadata is what keeps clicks and typing behind a
// question after a page's text has been read.
func TestBrowserTools(t *testing.T) {
	r := &Runtime{computer: computer.New("gbot-computer:latest", "docker", slog.New(slog.NewTextHandler(io.Discard, nil)))}
	bot := &store.Bot{ID: "00000000-0000-4000-8000-000000000001", OwnerID: "00000000-0000-4000-8000-000000000002"}
	byName := map[string]sdk.Tool{}
	for _, tl := range r.runTools(&Job{Bot: bot, For: bot.OwnerID}) {
		byName[tl.Name] = tl
	}
	for _, name := range []string{"browser_navigate", "browser_back", "browser_forward", "browser_reload",
		"browser_snapshot", "browser_scroll", "browser_wait", "browser_click", "browser_type", "browser_press",
		"browser_hover", "browser_check"} {
		if _, ok := byName[name]; !ok {
			t.Fatalf("%s not registered", name)
		}
	}
	if byName["browser_snapshot"].Meta["untrusted"] != true || byName["browser_snapshot"].Meta["readonly"] != true {
		t.Errorf("snapshot must be read-only and mark the run as having read outside text: %v", byName["browser_snapshot"].Meta)
	}
	if byName["browser_navigate"].Meta["untrusted"] != true {
		t.Errorf("navigate must mark the run as having read outside text: %v", byName["browser_navigate"].Meta)
	}
	for _, name := range []string{"browser_click", "browser_type", "browser_press", "browser_check"} {
		if byName[name].Meta["readonly"] == true || byName[name].Meta["risk"] != "medium" {
			t.Errorf("%s must be a medium-risk write: %v", name, byName[name].Meta)
		}
	}
	for _, name := range []string{"browser_scroll", "browser_wait", "browser_hover"} {
		if byName[name].Meta["readonly"] != true {
			t.Errorf("%s only reads the page and should not need approval: %v", name, byName[name].Meta)
		}
	}
	for _, name := range []string{"browser_back", "browser_forward", "browser_reload"} {
		if byName[name].Meta["untrusted"] != true || byName[name].Meta["risk"] != "low" {
			t.Errorf("%s must mark outside text and stay low risk: %v", name, byName[name].Meta)
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
