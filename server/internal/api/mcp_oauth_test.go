package api

import (
	"net/http/httptest"
	"testing"
)

func TestOAuthBase(t *testing.T) {
	r := httptest.NewRequest("POST", "http://127.0.0.1:8080/api/mcp/oauth/start", nil)
	r.Header.Set("Origin", "http://127.0.0.1:8080")
	r.Host = "127.0.0.1:8080"

	// A configured public URL wins over whatever host the proxy used.
	s := &Server{PublicURL: "https://bots.example.com"}
	if got := s.oauthBase(r); got != "https://bots.example.com" {
		t.Fatalf("public url: %q", got)
	}

	// Without it, the browser's own origin is the reachable address.
	s.PublicURL = ""
	r.Header.Set("Origin", "https://phone.example.com")
	if got := s.oauthBase(r); got != "https://phone.example.com" {
		t.Fatalf("origin: %q", got)
	}

	// A proxy that strips Origin still has the forwarded scheme.
	r.Header.Del("Origin")
	r.Header.Set("X-Forwarded-Proto", "https")
	r.Host = "bots.example.com"
	if got := s.oauthBase(r); got != "https://bots.example.com" {
		t.Fatalf("forwarded: %q", got)
	}
}
