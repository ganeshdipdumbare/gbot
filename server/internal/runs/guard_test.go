package runs

import (
	"testing"

	"github.com/ganeshdipdumbare/eipagent/sdk"
)

func TestNeedsApproval(t *testing.T) {
	ro := &sdk.Tool{Meta: map[string]any{"readonly": true}}
	write := &sdk.Tool{Meta: map[string]any{"risk": "medium"}}
	danger := &sdk.Tool{Meta: map[string]any{"risk": "high"}}
	rc := func(mode string, tainted bool) *runCtx {
		return &runCtx{mode: mode, allow: map[string]bool{"allowed": true}, block: map[string]bool{}, tainted: tainted}
	}
	cases := []struct {
		name string
		rc   *runCtx
		tool string
		t    *sdk.Tool
		want bool
	}{
		{"ask: reads pass", rc("ask", false), "x", ro, false},
		{"ask: writes ask", rc("ask", false), "x", write, true},
		{"ask: memory never asks", rc("ask", false), "remember", nil, false},
		{"ask: allow list wins", rc("ask", false), "allowed", write, false},
		{"auto: writes pass", rc("auto", false), "x", write, false},
		{"auto: irreversible asks", rc("auto", false), "x", danger, true},
		{"auto: writes ask after outside text", rc("auto", true), "x", write, true},
		{"auto: reads pass after outside text", rc("auto", true), "x", ro, false},
		{"auto: routine changes ask after outside text", rc("auto", true), "routine_create", nil, true},
		{"never: nothing asks", rc("never", true), "x", danger, false},
	}
	for _, c := range cases {
		if got := needsApproval(c.rc, c.tool, c.t); got != c.want {
			t.Errorf("%s: got %v", c.name, got)
		}
	}
}
