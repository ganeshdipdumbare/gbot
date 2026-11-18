package runs

import (
	"context"
	"testing"

	"github.com/ganeshdipdumbare/kritrim"
)

// Text a run fetches or reads back from history can carry instructions from
// outside, so the run must be tainted by what those tools return, not only
// by the browser and MCP tools.
func TestOutsideTextTaintsRunWhateverTheTool(t *testing.T) {
	cases := []struct {
		name  string
		call  string
		args  map[string]any
		taint bool
	}{
		{"shell curl taints", "computer_shell", map[string]any{"command": "curl -s https://example.com"}, true},
		{"shell python fetch taints", "computer_shell", map[string]any{"command": "python3 -c 'import urllib.request'; urlopen('x')"}, true},
		{"shell local command does not", "computer_shell", map[string]any{"command": "ls -la"}, false},
		{"history search taints", "history_search", map[string]any{"query": "deploy"}, true},
		{"memory recall taints", "recall", map[string]any{"query": "notes"}, true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			rc := &runCtx{botID: "b", userID: "u", mode: "auto", allow: map[string]bool{}, block: map[string]bool{}}
			ctx := withRun(context.Background(), rc)
			r := &Runtime{}
			r.afterTool(ctx, &kritrim.ToolEvent{Call: &kritrim.ToolCall{Name: tc.call, Args: tc.args}, Args: tc.args})
			if got := rc.isTainted(); got != tc.taint {
				t.Fatalf("tainted=%v, want %v", got, tc.taint)
			}
		})
	}
}

// After a web fetch, a shell command in auto mode must still ask for approval.
func TestShellNeedsApprovalAfterWebFetch(t *testing.T) {
	rc := &runCtx{botID: "b", userID: "u", mode: "auto", allow: map[string]bool{}, block: map[string]bool{}}
	ctx := withRun(context.Background(), rc)
	r := &Runtime{}
	r.afterTool(ctx, &kritrim.ToolEvent{Call: &kritrim.ToolCall{Name: "computer_shell", Args: map[string]any{"command": "wget https://example.com/x"}},
		Args: map[string]any{"command": "wget https://example.com/x"}})
	if !needsApproval(rc, "computer_shell") {
		t.Fatal("auto-mode shell after a web fetch ran without approval")
	}
}
