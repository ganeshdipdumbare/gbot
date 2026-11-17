package api_test

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"gbot/internal/api"
	"gbot/internal/crypto"
	"gbot/internal/models"
	"gbot/internal/runs"
	"gbot/internal/store"
)

// onePNG is the smallest real picture: one transparent pixel.
var onePNG, _ = base64.StdEncoding.DecodeString(
	"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")

// drawServer is an MCP server whose one tool hands back a picture, the
// way Excalidraw and other drawing integrations do.
func drawServer(t *testing.T, extra func() []any) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
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
			result = map[string]any{"protocolVersion": "2025-06-18", "capabilities": map[string]any{"tools": map[string]any{}}, "serverInfo": map[string]any{"name": "draw", "version": "1"}}
		case "tools/list":
			result = map[string]any{"tools": []any{
				map[string]any{"name": "draw", "description": "Draw a diagram", "inputSchema": map[string]any{"type": "object"}, "annotations": map[string]any{"readOnlyHint": true}},
			}}
		case "tools/call":
			content := []any{
				map[string]any{"type": "text", "text": "Here is the diagram."},
				map[string]any{"type": "image", "data": base64.StdEncoding.EncodeToString(onePNG), "mimeType": "image/png"},
			}
			if extra != nil {
				content = append(content, extra()...)
			}
			result = map[string]any{"content": content}
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"jsonrpc": "2.0", "id": id, "result": result})
	}))
}

// A picture an integration hands back reaches the person: it goes in the
// chat as its own row, it can be fetched back byte for byte, nobody
// else's account can read it, and the model is told it is there.
func TestToolPictureReachesTheChat(t *testing.T) {
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
	cat.Extra = []models.Model{{ID: "mockscript:x", Provider: "mockscript", Tools: true}}
	hub := runs.NewHub()
	// The tool's name carries the server's id, so the test fixes the id
	// and asks for that tool by name (mcpPrefix: "m" + the first eight
	// characters of the id, without dashes).
	const serverID = "11111111-2222-3333-4444-555555555555"
	script := []any{
		map[string]any{"tool": "m11111111__draw", "args": map[string]any{"what": "a box"}},
		map[string]any{"text": "That's the shape of it."},
	}
	rt, err := runs.New(runs.Config{DatabaseURL: dsn, OllamaHost: cat.OllamaHost, MaxSteps: 4, MockScript: script, SessionKey: []byte("test-session-key")}, st, hub, cat, log)
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
	mcp := drawServer(t, nil)
	defer mcp.Close()

	c := newClient(t, srv.URL)
	var me struct{ ID string }
	c.do("POST", "/api/auth/signup", map[string]string{"email": "pics@example.com", "password": "long-enough-pw"}, &me)
	if _, err := st.DB.Exec(ctx, `INSERT INTO mcp_servers (id, user_id, name, url) VALUES ($1, $2, $3, $4)`,
		serverID, me.ID, "Draw", mcp.URL); err != nil {
		t.Fatal(err)
	}
	rt.SyncMCP(ctx, sealer)
	waitMCP(t, c, serverID, func(status string, tools []string) bool { return status == "connected" && len(tools) == 1 })

	var bot store.Bot
	c.do("POST", "/api/bots", map[string]any{"name": "Pip", "model": "mockscript:x"}, &bot)
	c.do("POST", "/api/bots/"+bot.ID+"/messages", map[string]string{"text": "draw me a box"}, nil)

	picture := func() *store.Message {
		var res struct{ Messages []store.Message }
		c.do("GET", "/api/bots/"+bot.ID+"/messages", nil, &res)
		for i, m := range res.Messages {
			if m.Data["kind"] == "picture" {
				return &res.Messages[i]
			}
		}
		return nil
	}
	deadline := time.Now().Add(20 * time.Second)
	for picture() == nil {
		if time.Now().After(deadline) {
			var res struct{ Messages []store.Message }
			c.do("GET", "/api/bots/"+bot.ID+"/messages", nil, &res)
			t.Fatalf("no picture in the chat: %+v", res.Messages)
		}
		time.Sleep(100 * time.Millisecond)
	}
	m := picture()
	if m.Data["mime"] != "image/png" || m.Content != "Picture from Draw" {
		t.Fatalf("picture row: %q %+v", m.Content, m.Data)
	}
	blob, _ := m.Data["blob"].(string)
	if blob == "" {
		t.Fatal("picture has no blob id")
	}

	// The bytes come back as they went in.
	req, _ := http.NewRequest("GET", srv.URL+"/api/blobs/"+blob, nil)
	res, err := c.http.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	body, _ := io.ReadAll(res.Body)
	if res.StatusCode != 200 || !bytes.Equal(body, onePNG) {
		t.Fatalf("fetching the picture: %d, %d bytes", res.StatusCode, len(body))
	}
	if got := res.Header.Get("Content-Type"); got != "image/png" {
		t.Fatalf("content type %q", got)
	}

	// Opening the picture on its own can't run anything.
	if got := res.Header.Get("Content-Security-Policy"); !strings.Contains(got, "sandbox") {
		t.Fatalf("picture served without a sandbox: %q", got)
	}

	// Another account can't read it.
	other := newClient(t, srv.URL)
	other.do("POST", "/api/auth/signup", map[string]string{"email": "nosy@example.com", "password": "long-enough-pw"}, nil)
	req2, _ := http.NewRequest("GET", srv.URL+"/api/blobs/"+blob, nil)
	res2, err := other.http.Do(req2)
	if err != nil {
		t.Fatal(err)
	}
	defer res2.Body.Close()
	if res2.StatusCode != 404 {
		t.Fatalf("another account read the picture: %d", res2.StatusCode)
	}
}
