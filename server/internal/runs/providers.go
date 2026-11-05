package runs

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"sort"
	"strings"

	"github.com/ganeshdipdumbare/kiln"
	"github.com/ganeshdipdumbare/kiln/engine"
	"github.com/ganeshdipdumbare/kiln/sdk"

	"gbot/internal/crypto"
	"gbot/internal/models"
	"gbot/internal/store"
)

// Account providers are model modules in the shared agent, one instance per
// provider. Like MCP servers, kiln only loads modules at build time, so the
// agent is rebuilt and swapped when the set of providers changes. The model
// prefix is generated per provider and globally unique, so two accounts can
// never resolve each other's models.

type providerServer struct {
	id      string
	owner   string
	name    string
	kind    string // openai | anthropic | gemini
	prefix  string
	baseURL string
	key     string
	sig     string // kind, endpoint, prefix and a key hash
}

func (p providerServer) spec() engine.ModuleSpec {
	use := "model_openai"
	switch p.kind {
	case "anthropic":
		use = "model_anthropic"
	case "gemini":
		use = "model_gemini"
	}
	cfg := map[string]any{"name": p.prefix, "base_url": p.baseURL, "api_key": p.key}
	// Only local http endpoints get here (the API validates URLs); kiln
	// still needs this to allow a key over a non-loopback Docker host.
	if strings.HasPrefix(p.baseURL, "http://") {
		cfg["allow_insecure"] = true
	}
	return engine.ModuleSpec{Use: use, As: "llm_" + p.prefix, Config: cfg}
}

type providerState struct {
	srv    providerServer
	status string // connected | error message
	loaded bool   // the live agent serves this provider
}

// decodeProvider reads a stored provider, opening its sealed key.
func decodeProvider(p *store.Provider, sealer *crypto.Sealer) providerServer {
	key := ""
	if len(p.Sealed) > 0 && sealer != nil {
		if raw, err := sealer.Open(p.Sealed); err == nil {
			key = string(raw)
		}
	}
	sum := sha256.Sum256([]byte(key))
	return providerServer{
		id: p.ID, owner: p.UserID, name: p.Name, kind: p.Kind, prefix: p.Prefix,
		baseURL: p.BaseURL, key: key,
		sig: p.Kind + "\n" + p.BaseURL + "\n" + p.Prefix + "\n" + hex.EncodeToString(sum[:]),
	}
}

// ProviderModels maps stored providers to the catalog's view, dropping
// disabled ones and never exposing keys.
func ProviderModels(list []*store.Provider) []models.Provider {
	out := make([]models.Provider, 0, len(list))
	for _, p := range list {
		if !p.Enabled {
			continue
		}
		out = append(out, models.Provider{
			ID: p.ID, Name: p.Name, Kind: p.Kind, Prefix: p.Prefix,
			BaseURL: p.BaseURL, Local: p.Local, Models: p.Models(),
		})
	}
	return out
}

// SyncProviders makes the live agent serve exactly the enabled providers.
func (r *Runtime) SyncProviders(ctx context.Context, sealer *crypto.Sealer) {
	r.syncMu.Lock()
	defer r.syncMu.Unlock()

	list, err := r.store.AllEnabledProviders(ctx)
	if err != nil {
		r.log.Error("provider list", "err", err)
		return
	}

	r.mu.Lock()
	prev := make(map[string]providerState, len(r.providers))
	for id, st := range r.providers {
		prev[id] = st
	}
	r.mu.Unlock()

	next := map[string]providerState{}
	for _, p := range list {
		srv := decodeProvider(p, sealer)
		if srv.key != "" {
			sdk.AddRedaction(srv.key)
		}
		if st, ok := prev[srv.id]; ok && st.loaded && st.srv.sig == srv.sig {
			next[srv.id] = st // already live, unchanged
			continue
		}
		if err := probeProvider(srv); err != nil {
			status := sdk.Redact(err.Error())
			r.log.Warn("provider load", "provider", srv.name, "err", status)
			next[srv.id] = providerState{srv: srv, status: status}
			continue
		}
		next[srv.id] = providerState{srv: srv, status: "connected", loaded: true}
	}

	// Compare the live set, not the probes: a provider that keeps failing
	// must not rebuild the agent on every sync.
	if sameLiveProviders(prev, next) {
		r.mu.Lock()
		r.providers = next
		r.mu.Unlock()
		return
	}

	r.mu.Lock()
	mcp := liveMCPServers(r.mcp)
	r.mu.Unlock()
	a, err := r.build(mcp, liveProviderServers(next))
	if err != nil {
		// The agent stays as it was. Providers that only just probed fine
		// are reported as not loaded.
		r.log.Error("provider rebuild", "err", sdk.Redact(err.Error()))
		for id, st := range next {
			if st.loaded && !prev[id].loaded {
				next[id] = providerState{srv: st.srv, status: "couldn't load: " + sdk.Redact(err.Error())}
			}
		}
		r.mu.Lock()
		r.providers = next
		r.mu.Unlock()
		return
	}
	r.swap(&agentSlot{a: a})
	r.mu.Lock()
	r.providers = next
	r.mu.Unlock()
}

func sameLiveProviders(prev, next map[string]providerState) bool {
	a, b := liveProviderSigs(prev), liveProviderSigs(next)
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

func liveProviderSigs(m map[string]providerState) map[string]string {
	out := map[string]string{}
	for id, st := range m {
		if st.loaded {
			out[id] = st.srv.sig
		}
	}
	return out
}

func liveProviderServers(m map[string]providerState) []providerServer {
	var out []providerServer
	for _, st := range m {
		if st.loaded {
			out = append(out, st.srv)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].prefix < out[j].prefix })
	return out
}

func liveMCPServers(m map[string]mcpState) []mcpServer {
	var out []mcpServer
	for _, st := range m {
		if st.loaded {
			out = append(out, st.srv)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].prefix < out[j].prefix })
	return out
}

// probeProvider builds the provider module in a throwaway agent. It checks
// the endpoint and key configuration without calling the provider.
func probeProvider(p providerServer) error {
	a, err := kiln.New(kiln.WithModel(p.prefix+":probe"), kiln.WithModuleSpec(p.spec()))
	if err != nil {
		return err
	}
	return a.Close()
}

// ForgetProvider drops a provider's state so the next sync loads it fresh.
func (r *Runtime) ForgetProvider(id string) {
	r.mu.Lock()
	delete(r.providers, id)
	r.mu.Unlock()
}

func (r *Runtime) ProviderStatus(id string) string {
	r.mu.Lock()
	defer r.mu.Unlock()
	if st, ok := r.providers[id]; ok {
		return st.status
	}
	return "off"
}

// providerName is the display name behind a model prefix.
func (r *Runtime) providerName(prefix string) string {
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, st := range r.providers {
		if st.srv.prefix == prefix {
			return st.srv.name
		}
	}
	return ""
}
