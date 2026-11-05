package models

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestDiscoverOpenAI(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/models" {
			http.NotFound(w, r)
			return
		}
		if r.Header.Get("Authorization") != "Bearer secret" {
			http.Error(w, "no", 401)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"data": []map[string]string{{"id": "llama-3"}, {"id": "qwen-3"}}})
	}))
	defer srv.Close()

	got, err := Discover(context.Background(), "openai", srv.URL+"/v1", "secret")
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 2 || got[0] != "llama-3" || got[1] != "qwen-3" {
		t.Fatalf("got %v", got)
	}
}

func TestDiscoverAnthropic(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/models" || r.Header.Get("x-api-key") != "secret" {
			http.Error(w, "no", 401)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"data": []map[string]string{{"id": "claude-sonnet"}}})
	}))
	defer srv.Close()

	got, err := Discover(context.Background(), "anthropic", srv.URL, "secret")
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 1 || got[0] != "claude-sonnet" {
		t.Fatalf("got %v", got)
	}
}

func TestDiscoverGemini(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1beta/models" || r.URL.Query().Get("key") != "secret" {
			http.Error(w, "no", 401)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"models": []map[string]any{
			{"name": "models/gemini-2.5-pro", "supportedGenerationMethods": []string{"generateContent"}},
			{"name": "models/embedding-001", "supportedGenerationMethods": []string{"embedContent"}},
		}})
	}))
	defer srv.Close()

	got, err := Discover(context.Background(), "gemini", srv.URL+"/v1beta", "secret")
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 1 || got[0] != "gemini-2.5-pro" {
		t.Fatalf("got %v", got)
	}
}

func TestValidProviderURL(t *testing.T) {
	for _, tc := range []struct {
		url string
		ok  bool
	}{
		{"https://api.example.com/v1", true},
		{"http://localhost:1234/v1", true},
		{"http://127.0.0.1:11434/v1", true},
		{"http://example.com/v1", false},
		{"https://user:pass@example.com/v1", false},
		{"ftp://example.com", false},
	} {
		if err := ValidProviderURL(tc.url); (err == nil) != tc.ok {
			t.Errorf("%s: ok=%v err=%v", tc.url, tc.ok, err)
		}
	}
}
