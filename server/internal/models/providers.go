package models

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"time"

	"gbot/internal/codex"
)

// Provider is an account's model provider, without its key. Prefix is the
// generated, globally unique model prefix (for example "mp1a2b3c4").
type Provider struct {
	ID      string
	Name    string
	Kind    string // openai | anthropic | gemini | codex
	Prefix  string
	BaseURL string
	Local   bool
	Models  []string
}

// DefaultBaseURL is where each protocol lives when the person leaves the
// endpoint blank.
func DefaultBaseURL(kind string) string {
	switch kind {
	case "anthropic":
		return "https://api.anthropic.com"
	case "gemini":
		return "https://generativelanguage.googleapis.com/v1beta"
	case "codex":
		return codex.BaseURL
	default:
		return "https://api.openai.com/v1"
	}
}

// IsLocalHost reports whether a host is this machine (or the Docker host).
func IsLocalHost(host string) bool {
	switch host {
	case "localhost", "127.0.0.1", "::1", "host.docker.internal":
		return true
	}
	return false
}

// IsLocalURL reports whether a provider URL points at this machine.
func IsLocalURL(raw string) bool {
	u, err := url.Parse(raw)
	return err == nil && u.Scheme == "http" && IsLocalHost(u.Hostname())
}

// ValidProviderURL accepts https anywhere, and http only on this machine.
func ValidProviderURL(raw string) error {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || u.Host == "" || (u.Scheme != "https" && u.Scheme != "http") {
		return fmt.Errorf("the endpoint must be an http(s) URL")
	}
	if u.User != nil {
		return fmt.Errorf("put credentials in the API key, not the URL")
	}
	if u.Scheme == "http" && !IsLocalHost(u.Hostname()) {
		return fmt.Errorf("use https, or http only for localhost")
	}
	return nil
}

// Discover lists the models a provider offers. The caller decides whether
// an empty or failed result falls back to a manual list.
func Discover(ctx context.Context, kind, baseURL, apiKey string) ([]string, error) {
	if kind == "codex" {
		return codex.Models()
	}
	base := strings.TrimRight(strings.TrimSpace(baseURL), "/")
	if base == "" {
		base = DefaultBaseURL(kind)
	}
	if err := ValidProviderURL(base); err != nil {
		return nil, err
	}
	client := &http.Client{Timeout: 10 * time.Second}
	switch kind {
	case "openai":
		return discoverOpenAI(ctx, client, base, apiKey)
	case "anthropic":
		return discoverAnthropic(ctx, client, base, apiKey)
	case "gemini":
		return discoverGemini(ctx, client, base, apiKey)
	}
	return nil, fmt.Errorf("unknown provider kind %q", kind)
}

func discoverOpenAI(ctx context.Context, client *http.Client, base, key string) ([]string, error) {
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, base+"/models", nil)
	if key != "" {
		req.Header.Set("Authorization", "Bearer "+key)
	}
	var body struct {
		Data []struct {
			ID string `json:"id"`
		} `json:"data"`
		Models []struct {
			ID   string `json:"id"`
			Name string `json:"name"`
		} `json:"models"`
	}
	if err := getJSON(client, req, &body); err != nil {
		return nil, err
	}
	var out []string
	for _, m := range body.Data {
		out = append(out, m.ID)
	}
	for _, m := range body.Models {
		if m.ID != "" {
			out = append(out, m.ID)
		} else {
			out = append(out, m.Name)
		}
	}
	return cleanModels(out), nil
}

func discoverAnthropic(ctx context.Context, client *http.Client, base, key string) ([]string, error) {
	if key == "" {
		return nil, fmt.Errorf("an API key is required to list models")
	}
	var out []string
	after := ""
	for page := 0; page < 10; page++ {
		u := base + "/v1/models?limit=1000"
		if after != "" {
			u += "&after_id=" + url.QueryEscape(after)
		}
		req, _ := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
		req.Header.Set("x-api-key", key)
		req.Header.Set("anthropic-version", "2023-06-01")
		var body struct {
			Data []struct {
				ID string `json:"id"`
			} `json:"data"`
			HasMore bool   `json:"has_more"`
			LastID  string `json:"last_id"`
		}
		if err := getJSON(client, req, &body); err != nil {
			return nil, err
		}
		for _, m := range body.Data {
			out = append(out, m.ID)
		}
		if !body.HasMore || body.LastID == "" || body.LastID == after {
			break
		}
		after = body.LastID
	}
	return cleanModels(out), nil
}

func discoverGemini(ctx context.Context, client *http.Client, base, key string) ([]string, error) {
	if key == "" {
		return nil, fmt.Errorf("an API key is required to list models")
	}
	var out []string
	token := ""
	for page := 0; page < 10; page++ {
		u := base + "/models?key=" + url.QueryEscape(key)
		if token != "" {
			u += "&pageToken=" + url.QueryEscape(token)
		}
		req, _ := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
		var body struct {
			Models []struct {
				Name                       string   `json:"name"`
				SupportedGenerationMethods []string `json:"supportedGenerationMethods"`
			} `json:"models"`
			NextPageToken string `json:"nextPageToken"`
		}
		if err := getJSON(client, req, &body); err != nil {
			return nil, err
		}
		for _, m := range body.Models {
			if !supportsGenerate(m.SupportedGenerationMethods) {
				continue
			}
			out = append(out, strings.TrimPrefix(m.Name, "models/"))
		}
		if body.NextPageToken == "" || body.NextPageToken == token {
			break
		}
		token = body.NextPageToken
	}
	return cleanModels(out), nil
}

func supportsGenerate(methods []string) bool {
	if len(methods) == 0 {
		return true
	}
	for _, m := range methods {
		if m == "generateContent" {
			return true
		}
	}
	return false
}

func getJSON(client *http.Client, req *http.Request, v any) error {
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("%s returned %s", req.URL.Host, resp.Status)
	}
	return json.NewDecoder(resp.Body).Decode(v)
}

func cleanModels(in []string) []string {
	seen := make(map[string]bool, len(in))
	out := make([]string, 0, len(in))
	for _, m := range in {
		m = strings.TrimSpace(m)
		if m == "" || seen[m] || !IsChat(m) {
			continue
		}
		seen[m] = true
		out = append(out, m)
	}
	sort.Strings(out)
	return out
}
