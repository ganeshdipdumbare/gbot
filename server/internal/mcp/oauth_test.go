package mcp

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
	"time"
)

// A tiny provider that speaks just enough of the MCP OAuth profile.
func provider(t *testing.T) *httptest.Server {
	t.Helper()
	var srv *httptest.Server
	mux := http.NewServeMux()
	mux.HandleFunc("/mcp", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("WWW-Authenticate", `Bearer resource_metadata="`+srv.URL+`/.well-known/oauth-protected-resource/mcp"`)
		w.WriteHeader(http.StatusUnauthorized)
	})
	mux.HandleFunc("/.well-known/oauth-protected-resource/mcp", func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]any{
			"resource":              srv.URL + "/mcp",
			"authorization_servers": []string{srv.URL},
			"scopes_supported":      []string{"read", "write"},
		})
	})
	mux.HandleFunc("/.well-known/oauth-authorization-server", func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]any{
			"authorization_endpoint": srv.URL + "/authorize",
			"token_endpoint":         srv.URL + "/token",
			"registration_endpoint":  srv.URL + "/register",
		})
	})
	mux.HandleFunc("/register", func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		if body["redirect_uris"] == nil || body["client_name"] != "gbot" {
			http.Error(w, "bad registration", http.StatusBadRequest)
			return
		}
		w.WriteHeader(http.StatusCreated)
		json.NewEncoder(w).Encode(map[string]any{"client_id": "client-1"})
	})
	mux.HandleFunc("/token", func(w http.ResponseWriter, r *http.Request) {
		_ = r.ParseForm()
		if r.Form.Get("grant_type") == "refresh_token" && r.Form.Get("refresh_token") != "refresh-1" {
			http.Error(w, "bad refresh", http.StatusBadRequest)
			return
		}
		json.NewEncoder(w).Encode(map[string]any{
			"access_token":  "access-" + r.Form.Get("grant_type"),
			"refresh_token": "refresh-1",
			"expires_in":    3600,
		})
	})
	srv = httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv
}

func TestOAuthFlow(t *testing.T) {
	srv := provider(t)
	ctx := context.Background()
	cfg, err := Discover(ctx, srv.URL+"/mcp")
	if err != nil {
		t.Fatalf("discover: %v", err)
	}
	if cfg.AuthorizationEndpoint != srv.URL+"/authorize" || cfg.Resource != srv.URL+"/mcp" || len(cfg.Scopes) != 2 {
		t.Fatalf("config: %+v", cfg)
	}
	client, err := Register(ctx, cfg, "https://example.test/api/mcp/oauth/callback")
	if err != nil || client.ID != "client-1" {
		t.Fatalf("register: %+v %v", client, err)
	}
	verifier := "verifier-1234567890"
	auth := cfg.AuthURL(client.ID, "https://example.test/cb", "state-1", verifier)
	u, _ := url.Parse(auth)
	q := u.Query()
	if q.Get("code_challenge") != Challenge(verifier) || q.Get("code_challenge_method") != "S256" ||
		q.Get("resource") != srv.URL+"/mcp" || q.Get("scope") != "read write" || q.Get("state") != "state-1" {
		t.Fatalf("auth url: %s", auth)
	}
	if Challenge(verifier) != Challenge(verifier) || Challenge(verifier) == Challenge(verifier+"x") {
		t.Fatal("challenge is not a stable hash")
	}

	tokens, err := Exchange(ctx, cfg, client.ID, "", "code-1", verifier, "https://example.test/cb")
	if err != nil {
		t.Fatalf("exchange: %v", err)
	}
	if tokens.Access != "access-authorization_code" || tokens.Refresh != "refresh-1" || tokens.Expired() {
		t.Fatalf("tokens: %+v", tokens)
	}
	tokens.ExpiresAt = time.Now().Add(-time.Minute).Unix()
	if !tokens.Expired() {
		t.Fatal("expired token not detected")
	}
	next, err := Refresh(ctx, tokens)
	if err != nil || next.Access != "access-refresh_token" || next.Refresh != "refresh-1" {
		t.Fatalf("refresh: %+v %v", next, err)
	}
	if next.ClientID != client.ID || next.TokenEndpoint != cfg.TokenEndpoint {
		t.Fatalf("refresh lost the client: %+v", next)
	}
}

func TestHeaderValue(t *testing.T) {
	cases := map[string]string{
		`Bearer realm="OAuth", resource_metadata="https://x/.well-known/a"`: "https://x/.well-known/a",
		`Bearer resource_metadata=https://x/meta, scope="read"`:             "https://x/meta",
		`Bearer realm="OAuth"`: "",
	}
	for h, want := range cases {
		if got := headerValue(h, "resource_metadata"); got != want {
			t.Fatalf("%q: got %q, want %q", h, got, want)
		}
	}
}
