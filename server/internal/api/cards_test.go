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
	"gbot/internal/routines"
	"gbot/internal/runs"
	"gbot/internal/store"
)

// A scripted model walks through approvals and questions, offline.
func TestCards(t *testing.T) {
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
		map[string]any{"tool": "routine_create", "args": map[string]any{"name": "Daily hello", "prompt": "Say hi", "schedule": "0 9 * * *"}},
		map[string]any{"text": "Routine is set."},
		map[string]any{"tool": "ask_user", "args": map[string]any{"question": "Tea or coffee?", "options": []any{"Tea", "Coffee"}}},
		map[string]any{"text": "Tea it is."},
		map[string]any{"tool": "ask_user", "args": map[string]any{"question": "Which city?"}},
		map[string]any{"text": "Got it, moving on."},
	}
	rt, err := runs.New(runs.Config{DatabaseURL: dsn, OllamaHost: cat.OllamaHost, MaxSteps: 6, MockScript: script}, st, hub, cat, log)
	if err != nil {
		t.Fatal(err)
	}
	defer rt.Close()
	rs := &routines.Service{Store: st, Log: log}
	rt.Use(nil, rs)
	srv := httptest.NewServer((&api.Server{Store: st, Runtime: rt, Hub: hub, Models: cat, Log: log, Routines: rs, AllowSignup: true}).Handler())
	defer srv.Close()

	c := newClient(t, srv.URL)
	var me store.User
	c.do("POST", "/api/auth/signup", map[string]string{"email": "cards@example.com", "password": "long-enough-pw"}, &me)
	c.do("PATCH", "/api/me", map[string]string{"approvalMode": "ask"}, nil)
	var bot store.Bot
	c.do("POST", "/api/bots", map[string]any{"name": "Pip", "model": "mockscript:x"}, &bot)

	messages := func() []store.Message {
		var res struct{ Messages []store.Message }
		c.do("GET", "/api/bots/"+bot.ID+"/messages", nil, &res)
		return res.Messages
	}
	waitFor := func(what string, ok func([]store.Message) bool) []store.Message {
		deadline := time.Now().Add(15 * time.Second)
		for time.Now().Before(deadline) {
			if m := messages(); ok(m) {
				return m
			}
			time.Sleep(100 * time.Millisecond)
		}
		t.Fatalf("timed out waiting for %s; have %+v", what, messages())
		return nil
	}
	pendingCard := func(ms []store.Message) *store.Message {
		for i := range ms {
			if ms[i].Role == "card" && ms[i].Data["status"] == "pending" {
				return &ms[i]
			}
		}
		return nil
	}
	lastBot := func(text string) func([]store.Message) bool {
		return func(ms []store.Message) bool {
			return len(ms) > 0 && ms[len(ms)-1].Role == "bot" && ms[len(ms)-1].Content == text
		}
	}

	t.Run("an approval card pauses and resumes the run", func(t *testing.T) {
		c.do("POST", "/api/bots/"+bot.ID+"/messages", map[string]string{"text": "say hi every morning"}, nil)
		ms := waitFor("approval card", func(m []store.Message) bool { return pendingCard(m) != nil })
		card := pendingCard(ms)
		if card.Data["kind"] != "approval" || card.Data["tool"] != "routine_create" {
			t.Fatalf("unexpected card %+v", card)
		}
		var res struct{ Routines []any }
		c.do("GET", "/api/bots/"+bot.ID+"/routines", nil, &res)
		if len(res.Routines) != 0 {
			t.Fatal("routine was created before approval")
		}
		if code := c.do("POST", "/api/cards/"+card.ID, map[string]any{"decision": "approve", "always": true}, nil); code != 200 {
			t.Fatalf("approve: %d", code)
		}
		waitFor("reply after approval", lastBot("Routine is set."))
		c.do("GET", "/api/bots/"+bot.ID+"/routines", nil, &res)
		if len(res.Routines) != 1 {
			t.Fatalf("want 1 routine, got %d", len(res.Routines))
		}
		if code := c.do("POST", "/api/cards/"+card.ID, map[string]any{"decision": "approve"}, nil); code != 409 {
			t.Fatalf("answering twice: %d", code)
		}
		var rules struct{ Rules []store.ToolRule }
		c.do("GET", "/api/settings/rules", nil, &rules)
		if len(rules.Rules) != 1 || rules.Rules[0].Tool != "routine_create" || rules.Rules[0].Rule != "allow" {
			t.Fatalf("always-allow not saved: %+v", rules)
		}
	})

	t.Run("a question card takes an answer", func(t *testing.T) {
		c.do("POST", "/api/bots/"+bot.ID+"/messages", map[string]string{"text": "make me a drink"}, nil)
		ms := waitFor("question card", func(m []store.Message) bool { return pendingCard(m) != nil })
		card := pendingCard(ms)
		if card.Data["kind"] != "question" || card.Content != "Tea or coffee?" {
			t.Fatalf("unexpected card %+v", card)
		}
		c.do("POST", "/api/cards/"+card.ID, map[string]any{"decision": "answer", "text": "Tea"}, nil)
		waitFor("reply after answer", lastBot("Tea it is."))
	})

	t.Run("a new message moves on from an open card", func(t *testing.T) {
		c.do("POST", "/api/bots/"+bot.ID+"/messages", map[string]string{"text": "plan a trip"}, nil)
		waitFor("question card", func(m []store.Message) bool { return pendingCard(m) != nil })
		c.do("POST", "/api/bots/"+bot.ID+"/messages", map[string]string{"text": "never mind, Lisbon"}, nil)
		ms := waitFor("reply", lastBot("Got it, moving on."))
		if pendingCard(ms) != nil {
			t.Fatal("card still pending after moving on")
		}
	})

	t.Run("another account can't answer a card", func(t *testing.T) {
		other := newClient(t, srv.URL)
		other.do("POST", "/api/auth/signup", map[string]string{"email": "other@example.com", "password": "long-enough-pw"}, nil)
		for _, m := range messages() {
			if m.Role == "card" {
				if code := other.do("POST", "/api/cards/"+m.ID, map[string]any{"decision": "approve"}, nil); code != 404 {
					t.Fatalf("other account got %d", code)
				}
				return
			}
		}
	})
}
