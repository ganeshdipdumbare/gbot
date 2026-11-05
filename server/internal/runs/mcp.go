package runs

import (
	"context"
	"encoding/json"
	"strings"

	"github.com/ganeshdipdumbare/kiln"
	"github.com/ganeshdipdumbare/kiln/engine"
	"github.com/ganeshdipdumbare/kiln/sdk"

	"gbot/internal/crypto"
	"gbot/internal/store"
)

// MCP servers are tool_mcp modules in the shared agent, one instance per
// server. kiln v0.5 loads modules only when an agent is built, so when
// the set of servers changes the agent is rebuilt and swapped in. Runs
// already in progress finish on the old agent. Each server is probed on
// its own first, so one unreachable server can't stop the others.
//
// Each server's tools are prefixed with its own short name, and the guard
// shows them only to runs of the account that added the server.

// mcpServer is one enabled server, with its headers decrypted.
type mcpServer struct {
	id      string
	owner   string
	name    string
	prefix  string
	url     string
	headers map[string]any
	sig     string // url and headers; a change means reconnect
}

// spec is the tool_mcp module that serves this server.
func (s mcpServer) spec() engine.ModuleSpec {
	return engine.ModuleSpec{Use: "tool_mcp", As: "mcp_" + s.prefix, Config: map[string]any{
		"servers": []any{map[string]any{"name": s.prefix, "url": s.url, "headers": s.headers, "timeout_seconds": 30}},
	}}
}

type mcpState struct {
	srv    mcpServer
	status string // connected | error message
	loaded bool   // the live agent serves this server
}

func mcpPrefix(id string) string { return "m" + strings.ReplaceAll(id, "-", "")[:8] }

// decodeMCP reads a stored server, opening its sealed headers.
func decodeMCP(s *store.MCPServer, sealer *crypto.Sealer) mcpServer {
	headers := map[string]any{}
	if len(s.Sealed) > 0 && sealer != nil {
		if raw, err := sealer.Open(s.Sealed); err == nil {
			var h map[string]string
			_ = json.Unmarshal(raw, &h)
			for k, v := range h {
				headers[k] = v
				sdk.AddRedaction(v)
			}
		}
	}
	sig, _ := json.Marshal(headers)
	return mcpServer{id: s.ID, owner: s.UserID, name: s.Name, prefix: mcpPrefix(s.ID), url: s.URL,
		headers: headers, sig: s.URL + "\n" + string(sig)}
}

// SyncMCP makes the live agent serve exactly the enabled servers.
func (r *Runtime) SyncMCP(ctx context.Context, sealer *crypto.Sealer) {
	r.syncMu.Lock()
	defer r.syncMu.Unlock()
	servers, err := r.store.AllEnabledMCP(ctx)
	if err != nil {
		r.log.Error("mcp list", "err", err)
		return
	}

	r.mu.Lock()
	prev := make(map[string]mcpState, len(r.mcp))
	for id, st := range r.mcp {
		prev[id] = st
	}
	r.mu.Unlock()

	next := map[string]mcpState{}
	for _, s := range servers {
		srv := decodeMCP(s, sealer)
		if st, ok := prev[srv.id]; ok && st.loaded && st.srv.sig == srv.sig {
			next[srv.id] = st // already live, unchanged
			continue
		}
		if err := r.probe(srv); err != nil {
			status := sdk.Redact(err.Error())
			r.log.Warn("mcp load", "server", srv.name, "err", status)
			next[srv.id] = mcpState{srv: srv, status: status}
			continue
		}
		next[srv.id] = mcpState{srv: srv, status: "connected", loaded: true}
	}

	// Compare the live set, not the probes: a server that keeps failing
	// must not rebuild the agent on every sync.
	if sameLive(prev, next) {
		r.mu.Lock()
		r.mcp = next
		r.mu.Unlock()
		return
	}

	live := liveMCPServers(next)
	r.mu.Lock()
	provs := liveProviderServers(r.providers)
	r.mu.Unlock()
	a, err := r.build(live, provs)
	if err != nil {
		// The agent stays as it was. Servers that only just probed fine
		// are reported as not loaded.
		r.log.Error("mcp rebuild", "err", sdk.Redact(err.Error()))
		for id, st := range next {
			if st.loaded && !prev[id].loaded {
				next[id] = mcpState{srv: st.srv, status: "couldn't load: " + sdk.Redact(err.Error())}
			}
		}
		r.mu.Lock()
		r.mcp = next
		r.mu.Unlock()
		return
	}
	r.swap(&agentSlot{a: a})
	r.mu.Lock()
	r.mcp = next
	r.mu.Unlock()
}

// sameLive reports whether two syncs leave the same servers live, with
// the same connection settings.
func sameLive(prev, next map[string]mcpState) bool {
	a, b := liveSigs(prev), liveSigs(next)
	if len(a) != len(b) {
		return false
	}
	for id, sig := range a {
		if other, ok := b[id]; !ok || other != sig {
			return false
		}
	}
	return true
}

func liveSigs(m map[string]mcpState) map[string]string {
	out := map[string]string{}
	for id, st := range m {
		if st.loaded {
			out[id] = st.srv.sig
		}
	}
	return out
}

// probe connects one server in a throwaway agent. It fails with the
// server's own error, which is what the person needs to see.
func (r *Runtime) probe(s mcpServer) error {
	a, err := kiln.New(kiln.WithModel("ollama:default"), kiln.WithModuleSpec(s.spec()))
	if err != nil {
		return err
	}
	return a.Close()
}

// ForgetMCP drops a server's state so the next sync connects it fresh.
func (r *Runtime) ForgetMCP(id string) {
	r.mu.Lock()
	delete(r.mcp, id)
	r.mu.Unlock()
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
	ag, release := r.hold()
	defer release()
	p := mcpPrefix(id) + "__"
	var out []string
	for _, t := range ag.Engine().Root().Snapshot().Tools {
		if strings.HasPrefix(t.Name, p) {
			out = append(out, t.Name)
		}
	}
	return out
}

func (r *Runtime) mcpPrefixes(user string) map[string]bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := map[string]bool{}
	for _, st := range r.mcp {
		if st.srv.owner == user && st.loaded {
			out[st.srv.prefix] = true
		}
	}
	return out
}

// mcpName is the name the person gave the server behind a tool prefix.
func (r *Runtime) mcpName(prefix string) string {
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, st := range r.mcp {
		if st.srv.prefix == prefix {
			return st.srv.name
		}
	}
	return ""
}
