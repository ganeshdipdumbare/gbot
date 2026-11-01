package runs

import (
	"context"
	"encoding/json"
	"strings"

	"github.com/ganeshdipdumbare/eipagent/engine"
	"github.com/ganeshdipdumbare/eipagent/sdk"

	"gbot/internal/crypto"
	"gbot/internal/store"
)

// MCP servers are loaded into the shared agent as tool_mcp plugins.
// Each server's tools are prefixed with its own short name, and the
// guard shows them only to runs of the account that added it.

type mcpState struct {
	owner  string
	prefix string
	status string // connected | error message
}

func mcpPrefix(id string) string { return "m" + strings.ReplaceAll(id, "-", "")[:8] }

// SyncMCP loads enabled servers and unloads removed or disabled ones.
func (r *Runtime) SyncMCP(ctx context.Context, sealer *crypto.Sealer) {
	servers, err := r.store.AllEnabledMCP(ctx)
	if err != nil {
		r.log.Error("mcp list", "err", err)
		return
	}
	want := map[string]*store.MCPServer{}
	for _, s := range servers {
		want[s.ID] = s
	}
	eng := r.agent.Engine()
	r.mu.Lock()
	var drop []string
	for id := range r.mcp {
		if want[id] == nil {
			drop = append(drop, id)
			delete(r.mcp, id)
		}
	}
	r.mu.Unlock()
	for _, id := range drop {
		_ = eng.Unload("mcp_" + mcpPrefix(id))
	}
	for id, s := range want {
		r.mu.Lock()
		_, loaded := r.mcp[id]
		r.mu.Unlock()
		if loaded {
			continue
		}
		headers := map[string]any{}
		if len(s.Sealed) > 0 {
			if raw, err := sealer.Open(s.Sealed); err == nil {
				var h map[string]string
				_ = json.Unmarshal(raw, &h)
				for k, v := range h {
					headers[k] = v
					sdk.AddRedaction(v)
				}
			}
		}
		p := mcpPrefix(id)
		status := "connected"
		err := eng.Load(engine.PluginSpec{Use: "tool_mcp", As: "mcp_" + p, Config: map[string]any{
			"servers": []any{map[string]any{"name": p, "url": s.URL, "headers": headers, "timeout_seconds": 30}},
		}})
		if err != nil {
			status = sdk.Redact(err.Error())
			r.log.Warn("mcp load", "server", s.Name, "err", status)
			_ = eng.Unload("mcp_" + p)
		}
		r.mu.Lock()
		r.mcp[id] = mcpState{owner: s.UserID, prefix: p, status: status}
		r.mu.Unlock()
	}
}

// ForgetMCP drops a server so the next sync reloads it fresh.
func (r *Runtime) ForgetMCP(id string) {
	r.mu.Lock()
	_, ok := r.mcp[id]
	delete(r.mcp, id)
	r.mu.Unlock()
	if ok {
		_ = r.agent.Engine().Unload("mcp_" + mcpPrefix(id))
	}
}

func (r *Runtime) MCPStatus(id string) string {
	r.mu.Lock()
	defer r.mu.Unlock()
	if st, ok := r.mcp[id]; ok {
		return st.status
	}
	return "off"
}

// MCPTools lists the tool names a server registered.
func (r *Runtime) MCPTools(id string) []string {
	p := mcpPrefix(id) + "__"
	var out []string
	for _, t := range r.agent.Engine().Root().Snapshot().Tools {
		if strings.HasPrefix(t.Name, p) {
			out = append(out, t.Name)
		}
	}
	return out
}

func (r *Runtime) mcpPrefixes(owner string) map[string]bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := map[string]bool{}
	for _, st := range r.mcp {
		if st.owner == owner && st.status == "connected" {
			out[st.prefix] = true
		}
	}
	return out
}
