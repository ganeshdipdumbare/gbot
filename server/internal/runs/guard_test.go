package runs

import (
	"strings"
	"testing"

	"github.com/ganeshdipdumbare/kritrim"
)

func TestNeedsApproval(t *testing.T) {
	rc := func(mode string, tainted bool) *runCtx {
		return &runCtx{mode: mode, allow: map[string]bool{"allowed": true}, block: map[string]bool{}, tainted: tainted}
	}
	cases := []struct {
		name string
		rc   *runCtx
		tool string
		want bool
	}{
		{"ask: reads pass", rc("ask", false), "browser_snapshot", false},
		{"ask: opening a page asks", rc("ask", false), "browser_navigate", true},
		{"auto: opening a page asks", rc("auto", false), "browser_navigate", true},
		{"never: opening a page passes", rc("never", false), "browser_navigate", false},
		{"ask: writes ask", rc("ask", false), "browser_click", true},
		{"ask: memory never asks", rc("ask", false), "remember", false},
		{"ask: allow list wins", rc("ask", false), "allowed", false},
		{"auto: writes pass", rc("auto", false), "browser_click", false},
		{"auto: writes ask after outside text", rc("auto", true), "browser_click", true},
		{"auto: reads pass after outside text", rc("auto", true), "browser_snapshot", false},
		{"auto: routine changes ask after outside text", rc("auto", true), "routine_create", true},
		{"auto: routine creation always asks", rc("auto", false), "routine_create", true},
		{"auto: contract always asks", rc("auto", false), "task_contract", true},
		{"never: nothing asks", rc("never", true), "computer_write_file", false},
		{"browser: clicks ask in ask mode", rc("ask", false), "browser_type", true},
		{"browser: typing asks after page text was read", rc("auto", true), "browser_press", true},
		{"browser: nothing asks in never mode", rc("never", true), "browser_check", false},
	}
	for _, c := range cases {
		if got := needsApproval(c.rc, c.tool); got != c.want {
			t.Errorf("%s: got %v, want %v", c.name, got, c.want)
		}
	}
}

func TestSourcesMarkdown(t *testing.T) {
	rc := &runCtx{}
	rc.addSource("Example", "https://example.com/a?x=1")
	rc.addSource("Example again", "https://example.com/a")
	if len(rc.sources) != 1 || rc.sources[0].URL != "https://example.com/a?x=1" {
		t.Fatalf("sources: %+v", rc.sources)
	}
	if got := rc.sourcesMarkdown("See https://example.com/a"); got != "" {
		t.Fatalf("an already cited page was repeated: %q", got)
	}
	got := rc.sourcesMarkdown("No links here")
	if !strings.Contains(got, "**Sources**") || !strings.Contains(got, "https://example.com/a?x=1") {
		t.Fatalf("missing Sources section: %q", got)
	}
}

func TestCardKind(t *testing.T) {
	rc := &runCtx{mode: "ask", allow: map[string]bool{}, block: map[string]bool{}}
	cases := []struct {
		tool string
		want string
	}{
		{"ask_user", "question"},
		{"request_takeover", "takeover"},
		{"browser_click", "approval"},
		{"browser_snapshot", ""},
		{"remember", ""},
	}
	for _, c := range cases {
		if got := cardKind(rc, kritrim.Interrupt{Tool: c.tool}); got != c.want {
			t.Errorf("%s: got %q, want %q", c.tool, got, c.want)
		}
	}
}

func TestGroupThread(t *testing.T) {
	if got := groupThread(&Job{}, "run1"); got != "grp:run1" {
		t.Errorf("new turn: %q", got)
	}
	if got := groupThread(&Job{Thread: "grp:first"}, "run2"); got != "grp:first" {
		t.Errorf("resume: %q", got)
	}
}

func TestPairedTools(t *testing.T) {
	call := func(id string) kritrim.Message {
		return kritrim.Message{Role: kritrim.RoleAssistant, ToolCalls: []kritrim.ToolCall{{ID: id, Name: "x"}}}
	}
	result := func(id string) kritrim.Message {
		return kritrim.Message{Role: kritrim.RoleTool, ToolCallID: id, Content: "r"}
	}
	user := kritrim.Message{Role: kritrim.RoleUser, Content: "hi"}

	// The trim cut between a call and its result: the orphan goes.
	got := pairedTools([]kritrim.Message{result("a"), user, call("b"), result("b")})
	if len(got) != 3 || got[0].Role != kritrim.RoleUser || got[2].ToolCallID != "b" {
		t.Fatalf("orphan not dropped: %+v", got)
	}
	// A result placed before its call is still an orphan.
	if got := pairedTools([]kritrim.Message{result("b"), call("b")}); len(got) != 1 {
		t.Fatalf("early result kept: %+v", got)
	}
	// A valid prompt is returned untouched.
	ok := []kritrim.Message{user, call("a"), result("a")}
	if got := pairedTools(ok); len(got) != 3 {
		t.Fatalf("valid prompt changed: %+v", got)
	}
}
