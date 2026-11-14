package api_test

import (
	"context"
	"io"
	"log/slog"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"gbot/internal/api"
	"gbot/internal/models"
	"gbot/internal/runs"
	"gbot/internal/store"
)

// When the model provider blocks a reply, kritrim ends the run with
// StopGuardrail and no text. The chat has to say so: before this was
// handled the run settled as "done" and the person saw nothing at all.
func TestGuardrailStopTellsThePerson(t *testing.T) {
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
	rt, err := runs.New(runs.Config{DatabaseURL: dsn, OllamaHost: cat.OllamaHost, MaxSteps: 4,
		MockScript: []any{map[string]any{"stop": "guardrail"}}}, st, hub, cat, log)
	if err != nil {
		t.Fatal(err)
	}
	defer rt.Close()
	srv := httptest.NewServer((&api.Server{Store: st, Runtime: rt, Hub: hub, Models: cat, Log: log, AllowSignup: true}).Handler())
	defer srv.Close()

	c := newClient(t, srv.URL)
	c.do("POST", "/api/auth/signup", map[string]string{"email": "guard@example.com", "password": "long-enough-pw"}, nil)
	var pip store.Bot
	c.do("POST", "/api/bots", map[string]any{"name": "Pip", "model": "mockscript:x"}, &pip)
	c.do("POST", "/api/bots/"+pip.ID+"/messages", map[string]string{"text": "something the provider refuses"}, nil)

	deadline := time.Now().Add(15 * time.Second)
	for {
		var res struct{ Messages []store.Message }
		c.do("GET", "/api/bots/"+pip.ID+"/messages", nil, &res)
		for _, m := range res.Messages {
			if m.Role == "event" && m.Data["kind"] == "error" && strings.Contains(m.Content, "blocked") {
				return // the person was told
			}
			if m.Role == "bot" {
				t.Fatalf("a blocked reply became a bot message: %q", m.Content)
			}
		}
		if time.Now().After(deadline) {
			t.Fatalf("no blocked-reply notice in the chat: %+v", res.Messages)
		}
		time.Sleep(100 * time.Millisecond)
	}
}
