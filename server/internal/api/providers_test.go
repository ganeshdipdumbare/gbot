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

type providerJSON struct {
	ID, Name, Kind, BaseURL, Status string
	HasKey, Local, Enabled          bool
	Models                          []string
}

// openAIProvider is a minimal OpenAI-compatible server with a model list.
func openAIProvider(t *testing.T, key string) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/models" {
			http.NotFound(w, r)
			return
		}
		if r.Header.Get("Authorization") != "Bearer "+key {
			http.Error(w, "no", 401)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"data": []map[string]string{
			{"id": "llama-3"}, {"id": "qwen-3"},
		}})
	}))
}

func TestModelProviders(t *testing.T) {
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
	llm := openAIProvider(t, "provider-secret")
	defer llm.Close()

	alice := newClient(t, srv.URL)
	alice.do("POST", "/api/auth/signup", map[string]string{"email": "prov-a@example.com", "password": "long-enough-pw"}, nil)

	var raw map[string]any
	code := alice.do("POST", "/api/providers", map[string]any{
		"name": "GPU box", "kind": "openai", "baseUrl": llm.URL + "/v1", "apiKey": "provider-secret", "local": true,
	}, &raw)
	if code != 201 {
		t.Fatalf("add provider: %d %v", code, raw)
	}
	b, _ := json.Marshal(raw)
	if strings.Contains(string(b), "provider-secret") {
		t.Fatal("API key echoed back")
	}
	var p providerJSON
	_ = json.Unmarshal(b, &p)
	if p.Status != "connected" || len(p.Models) != 2 {
		t.Fatalf("want connected with 2 models, got %s %v", p.Status, p.Models)
	}

	var sealed []byte
	if err := st.DB.QueryRow(ctx, `SELECT api_key FROM model_providers WHERE id=$1`, p.ID).Scan(&sealed); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(sealed), "provider-secret") {
		t.Fatal("key stored in plain text")
	}

	var ml struct{ Models []models.Model }
	alice.do("GET", "/api/models", nil, &ml)
	var modelID, prefix string
	for _, m := range ml.Models {
		if strings.HasSuffix(m.ID, ":llama-3") && m.Provider == "GPU box" {
			modelID = m.ID
			prefix, _, _ = strings.Cut(m.ID, ":")
		}
	}
	if modelID == "" || prefix == "" {
		t.Fatalf("provider model missing from catalog: %v", ml.Models)
	}

	bob := newClient(t, srv.URL)
	bob.do("POST", "/api/auth/signup", map[string]string{"email": "prov-b@example.com", "password": "long-enough-pw"}, nil)
	var bp struct{ Providers []providerJSON }
	bob.do("GET", "/api/providers", nil, &bp)
	if len(bp.Providers) != 0 {
		t.Fatalf("another account sees providers: %v", bp.Providers)
	}
	var bm struct{ Models []models.Model }
	bob.do("GET", "/api/models", nil, &bm)
	for _, m := range bm.Models {
		if strings.HasPrefix(m.ID, prefix+":") {
			t.Fatalf("another account sees provider model %s", m.ID)
		}
	}
	if code := bob.do("PATCH", "/api/me", map[string]string{"defaultModel": modelID}, nil); code != 400 {
		t.Fatalf("another account could select the model: %d", code)
	}

	var upd providerJSON
	if code := alice.do("PATCH", "/api/providers/"+p.ID, map[string]any{"enabled": false}, &upd); code != 200 || upd.Enabled {
		t.Fatalf("disable: %d %+v", code, upd)
	}
	var ref providerJSON
	if code := alice.do("POST", "/api/providers/"+p.ID+"/refresh", nil, &ref); code != 200 || len(ref.Models) != 2 {
		t.Fatalf("refresh: %d %+v", code, ref)
	}
	if code := alice.do("DELETE", "/api/providers/"+p.ID, nil, nil); code != 200 {
		t.Fatalf("delete: %d", code)
	}
}
