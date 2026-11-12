package runs

import (
	"fmt"

	"github.com/ganeshdipdumbare/kritrim"
)

// mockTurns turns the test script ({"tool": "name", "args": {...}} or
// {"text": "..."} per turn) into the offline model's turns.
func mockTurns(script []any) []kritrim.Turn {
	out := make([]kritrim.Turn, 0, len(script))
	for i, raw := range script {
		m, _ := raw.(map[string]any)
		if m == nil {
			continue
		}
		turn := kritrim.Turn{}
		turn.Text, _ = m["text"].(string)
		if name, _ := m["tool"].(string); name != "" {
			args, _ := m["args"].(map[string]any)
			turn.ToolCalls = []kritrim.ToolCall{{ID: fmt.Sprintf("call_%s_%d", name, i), Name: name, Args: args}}
		}
		// Several calls in one turn: {"tools": [{"tool": ..., "args": ...}, ...]}.
		calls, _ := m["tools"].([]any)
		for k, c := range calls {
			cm, _ := c.(map[string]any)
			name, _ := cm["tool"].(string)
			args, _ := cm["args"].(map[string]any)
			turn.ToolCalls = append(turn.ToolCalls, kritrim.ToolCall{ID: fmt.Sprintf("call_%s_%d_%d", name, i, k), Name: name, Args: args})
		}
		out = append(out, turn)
	}
	return out
}
