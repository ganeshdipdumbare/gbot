package api_test

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"

	"gbot/internal/api"
	"gbot/internal/models"
	"gbot/internal/runs"
	"gbot/internal/store"
)

// Runs against a throwaway database on the local Postgres from
// infra/compose. Set GBOT_TEST_ADMIN_URL to point elsewhere.
func testDB(t *testing.T) string {
	admin := os.Getenv("GBOT_TEST_ADMIN_URL")
	if admin == "" {
		admin = "postgres://gbot:gbot-local@127.0.0.1:55432/gbot?sslmode=disable"
	}
	ctx := context.Background()
	conn, err := pgx.Connect(ctx, admin)
	if err != nil {
		t.Skipf("no Postgres for integration tests: %v", err)
	}
	defer conn.Close(ctx)
	_, _ = conn.Exec(ctx, `DROP DATABASE IF EXISTS gbot_test WITH (FORCE)`)
	if _, err := conn.Exec(ctx, `CREATE DATABASE gbot_test`); err != nil {
		t.Fatal(err)
	}
	return strings.Replace(admin, "/gbot?", "/gbot_test?", 1)
}

type client struct {
	t    *testing.T
	base string
	http *http.Client
}

func newClient(t *testing.T, base string) *client {
	jar, _ := cookiejar.New(nil)
	return &client{t, base, &http.Client{Jar: jar}}
}

func (c *client) do(method, path string, body any, out any) int {
	var r io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		r = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, c.base+path, r)
	req.Header.Set("Content-Type", "application/json")
	if method != http.MethodGet {
		req.Header.Set("X-GBot", "1")
	}
	res, err := c.http.Do(req)
	if err != nil {
		c.t.Fatal(err)
	}
	defer res.Body.Close()
	if out != nil {
		_ = json.NewDecoder(res.Body).Decode(out)
	}
	return res.StatusCode
}

func TestEndToEnd(t *testing.T) {
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
	cat := models.NewCatalog("http://127.0.0.1:1") // no Ollama: fully offline
	cat.Extra = []models.Model{{ID: "mock:test", Label: "mock", Provider: "mock", Tools: true}}
	hub := runs.NewHub()
	rt, err := runs.New(runs.Config{DatabaseURL: dsn, OllamaHost: cat.OllamaHost, MaxSteps: 6, Mock: true}, st, hub, cat, log)
	if err != nil {
		t.Fatal(err)
	}
	defer rt.Close()
	srv := httptest.NewServer((&api.Server{Store: st, Runtime: rt, Hub: hub, Models: cat, Log: log, AllowSignup: true}).Handler())
	defer srv.Close()

	alice := newClient(t, srv.URL)
	var me store.User
	if code := alice.do("POST", "/api/auth/signup", map[string]string{"email": "alice@example.com", "password": "long-enough-pw"}, &me); code != 200 {
		t.Fatalf("signup: %d", code)
	}

	t.Run("first bot is created on signup", func(t *testing.T) {
		var res struct{ Bots []store.Bot }
		alice.do("GET", "/api/bots", nil, &res)
		if len(res.Bots) != 1 || res.Bots[0].Name == "" {
			t.Fatalf("want one starter bot, got %+v", res.Bots)
		}
	})

	t.Run("writes without the CSRF header are refused", func(t *testing.T) {
		req, _ := http.NewRequest("POST", srv.URL+"/api/bots", strings.NewReader(`{"name":"x"}`))
		res, _ := alice.http.Do(req)
		if res.StatusCode != 403 {
			t.Fatalf("want 403, got %d", res.StatusCode)
		}
	})

	t.Run("wrong password is rejected", func(t *testing.T) {
		if code := newClient(t, srv.URL).do("POST", "/api/auth/login", map[string]string{"email": "alice@example.com", "password": "nope-nope-nope"}, nil); code != 401 {
			t.Fatalf("want 401, got %d", code)
		}
	})

	var bot store.Bot
	if code := alice.do("POST", "/api/bots", map[string]any{"name": "Pip", "model": "mock:test", "look": map[string]string{"shape": "ghost"}}, &bot); code != 201 {
		t.Fatalf("create bot: %d", code)
	}

	t.Run("spaces are isolated", func(t *testing.T) {
		bob := newClient(t, srv.URL)
		bob.do("POST", "/api/auth/signup", map[string]string{"email": "bob@example.com", "password": "long-enough-pw"}, nil)
		for _, p := range []string{"/api/bots/" + bot.ID, "/api/bots/" + bot.ID + "/messages"} {
			if code := bob.do("GET", p, nil, nil); code != 404 {
				t.Fatalf("%s: other space got %d, want 404", p, code)
			}
		}
		if code := bob.do("POST", "/api/bots/"+bot.ID+"/messages", map[string]string{"text": "hi"}, nil); code != 404 {
			t.Fatalf("other space could message the bot: %d", code)
		}
	})

	t.Run("a message streams a reply and uses memory", func(t *testing.T) {
		events, unsub := hub.Subscribe(me.SpaceID, me.ID)
		defer unsub()
		if code := alice.do("POST", "/api/bots/"+bot.ID+"/messages", map[string]string{"text": "remember I like jasmine tea"}, nil); code != 201 {
			t.Fatalf("send: %d", code)
		}
		seen := map[string]bool{}
		deadline := time.After(20 * time.Second)
		for !seen["done"] {
			select {
			case b := <-events:
				var e struct {
					Type string
					Data map[string]any
				}
				_ = json.Unmarshal(b, &e)
				seen[e.Type] = true
				if e.Type == "presence" {
					seen[e.Data["state"].(string)] = true
				}
			case <-deadline:
				t.Fatalf("no finished run; saw %v", seen)
			}
		}
		for _, want := range []string{"thinking", "working", "message", "delta", "message_done"} {
			if !seen[want] {
				t.Errorf("missing %q event; saw %v", want, seen)
			}
		}
		var res struct{ Messages []store.Message }
		alice.do("GET", "/api/bots/"+bot.ID+"/messages", nil, &res)
		if len(res.Messages) < 2 || res.Messages[len(res.Messages)-1].Role != "bot" {
			t.Fatalf("want a bot reply, got %+v", res.Messages)
		}
	})

	t.Run("unknown model is refused", func(t *testing.T) {
		if code := alice.do("PATCH", "/api/bots/"+bot.ID, map[string]string{"model": "ollama:does-not-exist"}, nil); code != 400 {
			t.Fatalf("want 400, got %d", code)
		}
	})
}
