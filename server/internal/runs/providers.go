package runs

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"net/url"
	"strings"
	"time"

	"github.com/ganeshdipdumbare/kritrim"

	"gbot/internal/codex"
	"gbot/internal/crypto"
	"gbot/internal/models"
	"gbot/internal/store"
)

// Account providers are model sources owned by one account. Their keys are
// decrypted in memory only, and the model prefix is generated per
// provider and globally unique, so two accounts can never resolve each
// other's models. Changing the live set closes the cached agents; each is
// rebuilt lazily with the new model on its next run.

type providerServer struct {
	id      string
	owner   string
	name    string
	kind    string // openai | anthropic | gemini | codex
	prefix  string
	baseURL string
	key     string
	// keyBroken is set when a key is stored but cannot be opened, for
	// example after GBOT_SECRET_KEY changed. Such a provider must not connect.
	keyBroken bool
	sig       string // kind, endpoint, prefix and a key hash
}

type providerState struct {
	srv    providerServer
	status string // connected | error message
	loaded bool   // the live agents serve this provider
}

// decodeProvider reads a stored provider, opening its sealed key.
func decodeProvider(p *store.Provider, sealer *crypto.Sealer) providerServer {
	key := ""
	broken := false
	if len(p.Sealed) > 0 {
		raw, err := sealerOpen(sealer, p.Sealed, crypto.Owner("provider-key", p.UserID))
		if err != nil {
			broken = true
		} else {
			key = string(raw)
		}
	}
	sum := sha256.Sum256([]byte(key))
	return providerServer{
		id: p.ID, owner: p.UserID, name: p.Name, kind: p.Kind, prefix: p.Prefix,
		baseURL: p.BaseURL, key: key, keyBroken: broken,
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

// SyncProviders makes the live agents serve exactly the enabled providers.
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
			r.addSecret(srv.key)
		}
		if st, ok := prev[srv.id]; ok && st.loaded && st.srv.sig == srv.sig {
			next[srv.id] = st // already live, unchanged
			continue
		}
		if srv.kind == "codex" {
			if u, err := r.store.UserByID(ctx, srv.owner); err != nil || !codex.Allowed(u.Email) {
				next[srv.id] = providerState{srv: srv, status: "this account can't use the server's Codex login"}
				continue
			}
		}
		if err := validateProvider(srv); err != nil {
			status := r.redact(err.Error())
			r.log.Warn("provider load", "provider", srv.name, "err", status)
			next[srv.id] = providerState{srv: srv, status: status}
			continue
		}
		next[srv.id] = providerState{srv: srv, status: "connected", loaded: true}
	}

	// Compare the live set, not the probes: a provider that keeps failing
	// must not rebuild the agents on every sync.
	if !sameLiveProviders(prev, next) {
		r.invalidateAgents()
	}
	r.mu.Lock()
	r.providers = next
	r.mu.Unlock()
}

// validateProvider checks a provider's configuration. The endpoint is
// exercised on the first run, not here.
func validateProvider(p providerServer) error {
	if p.prefix == "" {
		return fmt.Errorf("no model prefix")
	}
	if p.keyBroken {
		return fmt.Errorf("the saved API key can't be read; enter it again")
	}
	if _, err := url.Parse(p.baseURL); err != nil {
		return fmt.Errorf("bad endpoint: %w", err)
	}
	switch p.kind {
	case "openai", "anthropic", "gemini":
		return nil
	case "codex":
		if !codex.Available() {
			return fmt.Errorf("Codex isn't signed in with ChatGPT on this server")
		}
		return nil
	}
	return fmt.Errorf("unsupported provider kind %q", p.kind)
}

// modelFor builds the model behind a "prefix:name" id, using the account's
// key when the prefix belongs to one of its providers.
func (r *Runtime) modelFor(model string) (kritrim.Model, error) {
	prefix, name, ok := strings.Cut(model, ":")
	if !ok || name == "" {
		return nil, fmt.Errorf("no model is selected")
	}
	switch prefix {
	case "ollama":
		return kritrim.Ollama(name, kritrim.ModelConfig{BaseURL: strings.TrimRight(r.cfg.OllamaHost, "/") + "/v1"}), nil
	case "mock":
		return kritrim.Mock(), nil
	case "mockscript":
		turns := mockTurns(r.cfg.MockScript)
		return kritrim.Mock(turns...), nil
	case "anthropic":
		return kritrim.Anthropic(name), nil
	case "gemini":
		return kritrim.Gemini(name), nil
	case "openai":
		return kritrim.OpenAI(name), nil
	case "deepseek", "groq", "openrouter":
		return kritrim.Compatible(prefix, paidURLs[prefix], name), nil
	}

	r.mu.Lock()
	var st providerState
	found := false
	for _, p := range r.providers {
		if p.srv.prefix == prefix {
			st, found = p, true
			break
		}
	}
	r.mu.Unlock()
	if !found || !st.loaded {
		return nil, fmt.Errorf("the model %s is not connected", model)
	}
	p := st.srv
	cfg := kritrim.ModelConfig{APIKey: p.key, BaseURL: p.baseURL}
	switch p.kind {
	case "anthropic":
		return kritrim.Anthropic(name, cfg), nil
	case "gemini":
		// kritrim adds /v1beta itself; the stored endpoint includes it.
		cfg.BaseURL = geminiRoot(p.baseURL)
		return kritrim.Gemini(name, cfg), nil
	case "codex":
		return kritrim.OpenAIResponses(name, kritrim.ModelConfig{BaseURL: codex.BaseURL, Client: codex.Client(10 * time.Minute)}), nil
	default:
		return kritrim.Compatible(p.kind, p.baseURL, name, cfg), nil
	}
}

// reasoningFor asks a reasoning model to think before it answers. Only
// providers whose wire format kritrim maps natively get it: an arbitrary
// OpenAI-compatible endpoint may reject reasoning_effort.
func (r *Runtime) reasoningFor(model string) *kritrim.Reasoning {
	prefix, name, ok := strings.Cut(model, ":")
	if !ok {
		return nil
	}
	kind, baseURL := prefix, ""
	switch prefix {
	case "anthropic", "gemini", "openai":
	default:
		r.mu.Lock()
		kind = ""
		for _, st := range r.providers {
			if st.srv.prefix == prefix {
				kind, baseURL = st.srv.kind, st.srv.baseURL
				break
			}
		}
		r.mu.Unlock()
	}
	if !reasons(kind, baseURL, name) {
		return nil
	}
	return &kritrim.Reasoning{Effort: "medium"}
}

func reasons(kind, baseURL, name string) bool {
	switch kind {
	case "anthropic", "gemini", "codex":
	case "openai":
		if baseURL != "" && !strings.Contains(baseURL, "api.openai.com") {
			return false
		}
	default:
		return false
	}
	for _, c := range models.Capabilities(name) {
		if c == "reasoning" {
			return true
		}
	}
	return false
}

var paidURLs = map[string]string{
	"deepseek":   "https://api.deepseek.com",
	"groq":       "https://api.groq.com/openai/v1",
	"openrouter": "https://openrouter.ai/api/v1",
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

// geminiRoot trims the API version off a Gemini endpoint, since the client
// appends "/v1beta/models/..." on its own.
func geminiRoot(base string) string {
	base = strings.TrimRight(base, "/")
	for _, v := range []string{"/v1beta", "/v1"} {
		base = strings.TrimSuffix(base, v)
	}
	return base
}

// sealerOpen opens a sealed value, failing when there is no sealer.
func sealerOpen(sealer *crypto.Sealer, sealed, aad []byte) ([]byte, error) {
	if sealer == nil {
		return nil, fmt.Errorf("secret storage is not configured")
	}
	return sealer.Open(sealed, aad)
}
