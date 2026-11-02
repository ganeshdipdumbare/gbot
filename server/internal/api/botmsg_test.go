package api_test

import (
	"context"
	"io"
	"log/slog"
	"net/http/httptest"
	"testing"
	"time"

	"gbot/internal/api"
	"gbot/internal/models"
	"gbot/internal/runs"
	"gbot/internal/store"
)

// A bot asked to talk to another bot messages it in its chat and gets
// the reply back.
func TestBotMessagesBot(t *testing.T) {
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
	script := []any{
		map[string]any{"tool": "message_bot", "args": map[string]any{"to": "@grok bot", "message": "What's 6 times 7?"}},
		map[string]any{"text": "42."},
		map[string]any{"text": "Grok Bot says 42."},
	}
	rt, err := runs.New(runs.Config{DatabaseURL: dsn, OllamaHost: cat.OllamaHost, MaxSteps: 6, MockScript: script}, st, hub, cat, log)
	if err != nil {
		t.Fatal(err)
	}
	defer rt.Close()
	srv := httptest.NewServer((&api.Server{Store: st, Runtime: rt, Hub: hub, Models: cat, Log: log, AllowSignup: true}).Handler())
	defer srv.Close()

	c := newClient(t, srv.URL)
	c.do("POST", "/api/auth/signup", map[string]string{"email": "botmsg@example.com", "password": "long-enough-pw"}, nil)
	c.do("PATCH", "/api/me", map[string]string{"approvalMode": "ask"}, nil)
	var pip, grok store.Bot
	c.do("POST", "/api/bots", map[string]any{"name": "Pip", "model": "mockscript:x"}, &pip)
	c.do("POST", "/api/bots", map[string]any{"name": "Grok Bot", "model": "mockscript:x"}, &grok)

	messages := func(id string) []store.Message {
		var res struct{ Messages []store.Message }
		c.do("GET", "/api/bots/"+id+"/messages", nil, &res)
		return res.Messages
	}
	c.do("POST", "/api/bots/"+pip.ID+"/messages", map[string]string{"text": "ask Grok Bot what 6 times 7 is"}, nil)
	deadline := time.Now().Add(15 * time.Second)
	for {
		ms := messages(pip.ID)
		if len(ms) > 0 && ms[len(ms)-1].Role == "bot" && ms[len(ms)-1].Content == "Grok Bot says 42." {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("Pip never replied; have %+v", ms)
		}
		time.Sleep(100 * time.Millisecond)
	}

	ms := messages(grok.ID)
	if len(ms) != 2 || ms[0].Role != "user" || ms[0].Content != "What's 6 times 7?" || ms[0].Data["from"] != pip.ID {
		t.Fatalf("Grok Bot's chat should start with Pip's message; have %+v", ms)
	}
	if ms[1].Role != "bot" || ms[1].Content != "42." {
		t.Fatalf("Grok Bot should reply in its chat; have %+v", ms[1])
	}
	var messaged bool
	for _, m := range messages(pip.ID) {
		if m.Data["kind"] == "activity" && m.Content == "✓ Messaged → @grok bot" {
			messaged = true
		}
	}
	if !messaged {
		t.Fatalf("Pip's activity should say it messaged Grok Bot; have %+v", messages(pip.ID))
	}
}
