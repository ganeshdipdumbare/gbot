package runs

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/ganeshdipdumbare/kritrim"
)

// mockTurns turns the test script ({"tool": "name", "args": {...}},
// {"text": "..."} or {"stop": "guardrail"} per turn) into the offline
// model's turns.
func mockTurns(script []any) []kritrim.Turn {
	out := make([]kritrim.Turn, 0, len(script))
	for i, raw := range script {
		m, _ := raw.(map[string]any)
		if m == nil {
			continue
		}
		turn := kritrim.Turn{}
		turn.Text, _ = m["text"].(string)
		// A scripted stop reason, so tests can cover a provider that
		// blocks a reply ("guardrail") or runs out of steps.
		turn.Stop, _ = m["stop"].(string)
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

// mockWaits reads each turn's "wait_ms": how long the offline model takes
// to answer it, so a test can act while a turn is under way.
func mockWaits(script []any) []time.Duration {
	out := make([]time.Duration, 0, len(script))
	for _, raw := range script {
		m, _ := raw.(map[string]any)
		if m == nil {
			continue
		}
		ms, _ := m["wait_ms"].(float64)
		if n, ok := m["wait_ms"].(int); ok {
			ms = float64(n)
		}
		out = append(out, time.Duration(ms)*time.Millisecond)
	}
	return out
}

// slowMock answers turn i of a script after waits[i].
type slowMock struct {
	kritrim.Model
	mu    sync.Mutex
	n     int
	waits []time.Duration
}

func (m *slowMock) wait(ctx context.Context) error {
	m.mu.Lock()
	var d time.Duration
	if m.n < len(m.waits) {
		d = m.waits[m.n]
	}
	m.n++
	m.mu.Unlock()
	if d <= 0 {
		return nil
	}
	select {
	case <-time.After(d):
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (m *slowMock) Generate(ctx context.Context, req kritrim.Request) (*kritrim.Response, error) {
	if err := m.wait(ctx); err != nil {
		return nil, err
	}
	return m.Model.Generate(ctx, req)
}

func (m *slowMock) Stream(ctx context.Context, req kritrim.Request, emit func(kritrim.Delta)) (*kritrim.Response, error) {
	if err := m.wait(ctx); err != nil {
		return nil, err
	}
	return m.Model.Stream(ctx, req, emit)
}
