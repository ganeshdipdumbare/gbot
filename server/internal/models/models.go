// Package models finds the models this server can use: local ones first
// (Ollama or any OpenAI-compatible server), then paid providers whose
// keys the operator has set. Nothing external is required.
package models

import (
	"context"
	"encoding/json"
	"net/http"
	"os"
	"sort"
	"strings"
	"sync"
	"time"
)

type Model struct {
	ID       string   `json:"id"` // model string, e.g. "ollama:lfm3-omni"
	Label    string   `json:"label"`
	Provider string   `json:"provider"`
	Local    bool     `json:"local"`
	Tools    bool     `json:"tools"`
	Caps     []string `json:"caps,omitempty"`
	// Best marks what "best available" picks right now.
	Best bool `json:"best,omitempty"`
}

type Catalog struct {
	OllamaHost string
	// Extra models are always listed (tests use the offline mock).
	Extra  []Model
	client *http.Client

	mu      sync.Mutex
	cached  []Model
	fetched time.Time
}

func NewCatalog(ollamaHost string) *Catalog {
	return &Catalog{OllamaHost: strings.TrimRight(ollamaHost, "/"), client: &http.Client{Timeout: 2 * time.Second}}
}

// paid lists providers enabled only when their key is in the environment.
var paid = []struct {
	provider, env string
	models        []string
}{
	{"anthropic", "ANTHROPIC_API_KEY", []string{"claude-sonnet-5-5", "claude-haiku-5-5", "claude-opus-5-5"}},
	{"openai", "OPENAI_API_KEY", []string{"gpt-4.1", "gpt-4.1-mini"}},
	{"gemini", "GEMINI_API_KEY", []string{"gemini-2.5-pro", "gemini-2.5-flash"}},
	{"deepseek", "DEEPSEEK_API_KEY", []string{"deepseek-chat"}},
	{"groq", "GROQ_API_KEY", []string{"llama-3.3-70b-versatile"}},
	{"openrouter", "OPENROUTER_API_KEY", []string{"openrouter/auto"}},
}

// List returns available models, local first. Results are cached briefly
// so the roster and settings stay snappy.
func (c *Catalog) List(ctx context.Context) []Model {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.cached != nil && time.Since(c.fetched) < 15*time.Second {
		return c.cached
	}
	out := append(c.ollama(ctx), c.Extra...)
	for _, p := range paid {
		if os.Getenv(p.env) == "" {
			continue
		}
		for _, m := range p.models {
			caps := Capabilities(m)
			out = append(out, Model{ID: p.provider + ":" + m, Label: m, Provider: p.provider, Tools: hasCap(caps, "tools"), Caps: caps})
		}
	}
	c.cached, c.fetched = out, time.Now()
	return out
}

func (c *Catalog) Invalidate() { c.mu.Lock(); c.cached = nil; c.mu.Unlock() }

func (c *Catalog) ollama(ctx context.Context) []Model {
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, c.OllamaHost+"/api/tags", nil)
	resp, err := c.client.Do(req)
	if err != nil {
		return nil
	}
	defer resp.Body.Close()
	var body struct {
		Models []struct {
			Name         string   `json:"name"`
			Capabilities []string `json:"capabilities"`
		} `json:"models"`
	}
	if json.NewDecoder(resp.Body).Decode(&body) != nil {
		return nil
	}
	var out []Model
	for _, m := range body.Models {
		name := strings.TrimSuffix(m.Name, ":latest")
		tools := false
		chat := len(m.Capabilities) == 0
		var caps []string
		for _, cap := range m.Capabilities {
			tools = tools || cap == "tools"
			chat = chat || cap == "completion"
			switch cap {
			case "tools", "vision":
				caps = append(caps, cap)
			case "thinking":
				caps = append(caps, "reasoning")
			}
		}
		if !chat { // embedding-only models can't chat
			continue
		}
		out = append(out, Model{ID: "ollama:" + name, Label: name, Provider: "ollama", Local: true, Tools: tools, Caps: caps})
	}
	// Tool-capable models first: they can use memory and other tools.
	sort.SliceStable(out, func(i, j int) bool { return out[i].Tools && !out[j].Tools })
	return out
}

// ListFor is List plus the models of the account's own providers. Provider
// models are namespaced by their generated prefix, so they can never collide
// with the server-wide providers above.
func (c *Catalog) ListFor(ctx context.Context, providers []Provider) []Model {
	base := c.List(ctx)
	out := make([]Model, 0, len(base)+8)
	out = append(out, base...)
	for _, p := range providers {
		for _, m := range p.Models {
			if !IsChat(m) {
				continue
			}
			caps := Capabilities(m)
			out = append(out, Model{ID: p.Prefix + ":" + m, Label: m, Provider: p.Name, Local: p.Local, Tools: hasCap(caps, "tools"), Caps: caps})
		}
	}
	return out
}

// MarkBest flags the model "best available" resolves to.
func MarkBest(list []Model) []Model {
	best := pick(list, nil)
	out := make([]Model, len(list))
	for i, m := range list {
		m.Best = m.ID == best
		out[i] = m
	}
	return out
}

// Pick returns want when it is available, else the best default: the
// first local tool-capable model, then any model at all.
func (c *Catalog) Pick(ctx context.Context, want ...string) string {
	return pick(c.List(ctx), want)
}

// PickFor is Pick over the account's own catalog.
func (c *Catalog) PickFor(ctx context.Context, providers []Provider, want ...string) string {
	return pick(c.ListFor(ctx, providers), want)
}

// preferredLocal is the small, image-capable model gbot expects on a
// machine. Other local models can be far larger than a laptop can hold,
// so an explicit choice still wins, but "best available" lands here.
var preferredLocal = []string{"ollama:lfm3-omni", "ollama:lfm-omni"}

func pick(list []Model, want []string) string {
	for _, w := range want {
		for _, m := range list {
			if w != "" && m.ID == w {
				return w
			}
		}
	}
	for _, p := range preferredLocal {
		for _, m := range list {
			if m.ID == p {
				return p
			}
		}
	}
	for _, m := range list {
		if strings.Contains(m.ID, "lfm") && strings.Contains(m.ID, "omni") {
			return m.ID
		}
	}
	if len(list) > 0 {
		return list[0].ID
	}
	return ""
}
