package runs

import (
	"testing"

	"github.com/ganeshdipdumbare/kritrim"
)

func TestDescribeStep(t *testing.T) {
	call := func(name string, args map[string]any) *kritrim.ToolCall {
		return &kritrim.ToolCall{Name: name, Args: args}
	}
	connector := func(prefix string) string { return map[string]string{"m1a2b3c4d": "GitHub"}[prefix] }
	cases := []struct {
		call   *kritrim.ToolCall
		result string
		want   step
		keep   bool
	}{
		{call("computer_shell", map[string]any{"command": "python3 fib.py"}), "exit 0\n1 1 2", step{"Ran", "python3 fib.py · exit 0", true}, true},
		{call("computer_shell", map[string]any{"command": "false"}), "exit 1\n", step{"Ran", "false · exit 1", false}, true},
		{call("computer_write_file", map[string]any{"path": "notes.md"}), "Saved notes.md", step{"Wrote", "notes.md", true}, true},
		{call("m1a2b3c4d__search_issues", nil), "[]", step{"Used search issues · GitHub", "", true}, true},
		{call("m9z8y7x6w__web_search", nil), "[]", step{"Used web search", "", true}, true},
		{call("web_search", nil), "error: unknown tool web_search", step{}, false},
		{call("ask_user", nil), "", step{}, false},
		{call("browser_navigate", map[string]any{"url": "https://www.example.com/docs/"}), "Opened example.com/docs. Title: Docs.", step{"Opened", "example.com/docs", true}, true},
		{call("browser_snapshot", nil), "Title: Shop\n[1] link \"Pricing\"", step{"Read the page", "", true}, true},
		{call("browser_click", map[string]any{"ref": 5.0}), "Clicked [5]. Now on example.com.", step{"Clicked", "[5]", true}, true},
		{call("browser_click", map[string]any{"ref": 9.0}), "error: No element [9] on this page.", step{"Clicked", "[9]", false}, true},
		{call("browser_type", map[string]any{"ref": 3.0, "text": "hunter2", "submit": true}), "Typed into [3] and submitted it.", step{"Typed", "into [3] and submitted", true}, true},
		{call("browser_press", map[string]any{"key": "Enter"}), "Pressed Enter.", step{"Pressed", "Enter", true}, true},
	}
	for _, c := range cases {
		got, keep := describeStep(c.call, c.result, connector)
		if keep != c.keep || got != c.want {
			t.Errorf("%s: got %+v %v, want %+v %v", c.call.Name, got, keep, c.want, c.keep)
		}
	}
	if s := stepsText([]step{{"Ran", "ls · exit 0", true}, {"Wrote", "a.md", true}}); s != "✓ Ran → ls · exit 0\n✓ Wrote → a.md" {
		t.Fatalf("text: %q", s)
	}
}
