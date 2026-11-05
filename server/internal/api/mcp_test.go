package api_test

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"gbot/internal/api"
	"gbot/internal/crypto"
	"gbot/internal/models"
	"gbot/internal/runs"
	"gbot/internal/store"
)

// mcpServer is a tiny streamable-HTTP MCP server that requires a token.
func mcpServer(t *testing.T, token string) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer "+token {
			http.Error(w, "no", 401)
			return
		}
		var req map[string]any
		_ = json.NewDecoder(r.Body).Decode(&req)
		id, ok := req["id"]
		if !ok {
			w.WriteHeader(202)
			return
		}
		var result any
		switch req["method"] {
		case "initialize":
			result = map[string]any{"protocolVersion": "2025-06-18", "capabilities": map[string]any{"tools": map[string]any{}}, "serverInfo": map[string]any{"name": "t", "version": "1"}}
		case "tools/list":
			result = map[string]any{"tools": []any{
				map[string]any{"name": "lookup", "description": "Look something up", "inputSchema": map[string]any{"type": "object"}, "annotations": map[string]any{"readOnlyHint": true}},
				map[string]any{"name": "delete_everything", "description": "Danger", "inputSchema": map[string]any{"type": "object"}, "annotations": map[string]any{"destructiveHint": true}},
			}}
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"jsonrpc": "2.0", "id": id, "result": result})
	}))
}

func TestMCPIntegrations(t *testing.T) {
	dsn := testDB(t)
	ctx := context.Background()
	st, err := store.Open(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	if err := st.Migrate(ctx); err != nil {
		t.Fatal(err)
	}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	cat := models.NewCatalog("http://127.0.0.1:1")
	hub := runs.NewHub()
	rt, err := runs.New(runs.Config{DatabaseURL: dsn, OllamaHost: cat.OllamaHost, MaxSteps: 4, Mock: true}, st, hub, cat, log)
	if err != nil {
		t.Fatal(err)
	}
	defer rt.Close()
	sealer, err := crypto.Load(filepath.Join(t.TempDir(), "k"))
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer((&api.Server{Store: st, Runtime: rt, Hub: hub, Models: cat, Log: log, Sealer: sealer, AllowSignup: true}).Handler())
	defer srv.Close()
	mcp := mcpServer(t, "s3cret-token-value")
	defer mcp.Close()

	a := newClient(t, srv.URL)
	a.do("POST", "/api/auth/signup", map[string]string{"email": "mcp-a@example.com", "password": "long-enough-pw"}, nil)
	var m struct {
		ID, Status string
		Tools      []string
	}
	raw := map[string]any{}
	if code := a.do("POST", "/api/mcp", map[string]string{"name": "Lookup", "url": mcp.URL, "token": "s3cret-token-value"}, &raw); code != 201 {
		t.Fatalf("add: %d %v", code, raw)
	}
	b, _ := json.Marshal(raw)
	_ = json.Unmarshal(b, &m)
	if strings.Contains(string(b), "s3cret") {
		t.Fatal("token echoed back")
	}
	if m.Status != "connected" || len(m.Tools) != 2 {
		t.Fatalf("want connected with 2 tools, got %s %v", m.Status, m.Tools)
	}
	var row []byte
	_ = st.DB.QueryRow(ctx, `SELECT headers FROM mcp_servers WHERE id=$1`, m.ID).Scan(&row)
	if strings.Contains(string(row), "s3cret") {
		t.Fatal("token stored in plain text")
	}

	if code := a.do("POST", "/api/mcp", map[string]string{"name": "x", "url": "http://example.com/mcp"}, nil); code != 400 {
		t.Fatalf("plain http to a remote host allowed: %d", code)
	}

	other := newClient(t, srv.URL)
	other.do("POST", "/api/auth/signup", map[string]string{"email": "mcp-b@example.com", "password": "long-enough-pw"}, nil)
	var list struct{ Servers []any }
	other.do("GET", "/api/mcp", nil, &list)
	if len(list.Servers) != 0 {
		t.Fatal("another account sees the server")
	}
	if code := other.do("DELETE", "/api/mcp/"+m.ID, nil, nil); code != 404 {
		t.Fatalf("another account deleted it: %d", code)
	}

	// A server that won't connect is reported as such and doesn't stop
	// the working one from serving its tools.
	broken := map[string]any{}
	if code := a.do("POST", "/api/mcp", map[string]string{"name": "Broken", "url": mcp.URL, "token": "wrong-token"}, &broken); code != 201 {
		t.Fatalf("add broken: %d %v", code, broken)
	}
	if s, _ := broken["status"].(string); s == "connected" || s == "" || s == "off" {
		t.Fatalf("broken server reported as %q", s)
	}
	var all struct {
		Servers []struct {
			ID, Status string
			Tools      []string
		}
	}
	a.do("GET", "/api/mcp", nil, &all)
	for _, s := range all.Servers {
		if s.ID == m.ID && (s.Status != "connected" || len(s.Tools) != 2) {
			t.Fatalf("working server lost when another failed: %s %v", s.Status, s.Tools)
		}
	}

	a.do("PATCH", "/api/mcp/"+m.ID, map[string]bool{"enabled": false}, &raw)
	if raw["status"] != "off" {
		t.Fatalf("disabled server still loaded: %v", raw["status"])
	}
}
