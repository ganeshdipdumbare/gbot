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

// Bots that keep talking keep leaving cards. Every message makes a pair,
// however long the chain runs and however many times the person asks —
// and once a request has spent its messages the bot is refused in words
// rather than losing the tool, so it can say so instead of carrying on
// as though it were still talking to its teammates.
func TestBotMessagesKeepMakingCards(t *testing.T) {
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
	// One person turn: Pip asks, Pip's continuation and Grok Bot's run
	// race for the next step (so it says the same thing), then the join
	// run answers the person.
	var script []any
	for i := 0; i < 12; i++ {
		script = append(script,
			map[string]any{"tool": "message_bot", "args": map[string]any{"to": "Grok Bot", "message": "What's new?", "expect": "ask"}},
			map[string]any{"text": "Something."},
			map[string]any{"text": "Something."},
			map[string]any{"text": "Grok Bot said something."},
		)
	}
	rt, err := runs.New(runs.Config{DatabaseURL: dsn, OllamaHost: cat.OllamaHost, MaxSteps: 6, MockScript: script, SessionKey: []byte("test-session-key")}, st, hub, cat, log)
	if err != nil {
		t.Fatal(err)
	}
	defer rt.Close()
	srv := httptest.NewServer((&api.Server{Store: st, Runtime: rt, Hub: hub, Models: cat, Log: log, AllowSignup: true}).Handler())
	defer srv.Close()

	c := newClient(t, srv.URL)
	c.do("POST", "/api/auth/signup", map[string]string{"email": "chain@example.com", "password": "long-enough-pw"}, nil)
	var pip, grok store.Bot
	c.do("POST", "/api/bots", map[string]any{"name": "Pip", "model": "mockscript:x"}, &pip)
	c.do("POST", "/api/bots", map[string]any{"name": "Grok Bot", "model": "mockscript:x"}, &grok)

	cards := func(id string) (n int) {
		var res struct{ Messages []store.Message }
		c.do("GET", "/api/bots/"+id+"/messages", nil, &res)
		for _, m := range res.Messages {
			if m.Data["kind"] == "botmsg" {
				n++
			}
		}
		return n
	}
	// Eight separate requests from the person, each one message.
	for turn := 1; turn <= 8; turn++ {
		c.do("POST", "/api/bots/"+pip.ID+"/messages", map[string]string{"text": "ask Grok Bot what's new"}, nil)
		deadline := time.Now().Add(20 * time.Second)
		for cards(pip.ID) < turn && time.Now().Before(deadline) {
			time.Sleep(100 * time.Millisecond)
		}
		time.Sleep(400 * time.Millisecond)
		if got := cards(pip.ID); got != turn {
			t.Fatalf("after person turn %d Pip has %d cards, want %d", turn, got, turn)
		}
		if got := cards(grok.ID); got != turn {
			t.Fatalf("after person turn %d Grok Bot has %d cards, want %d", turn, got, turn)
		}
	}
}

// A request that keeps messaging stops at its budget, and stops there
// only: the cards up to it are all written, and the run ends cleanly.
func TestOneRequestStopsAtItsBudget(t *testing.T) {
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
	// Pip asks again on every turn it gets, long past the budget.
	var script []any
	for i := 0; i < 40; i++ {
		script = append(script,
			map[string]any{"tool": "message_bot", "args": map[string]any{"to": "Grok Bot", "message": "Again?", "expect": "ask"}},
			map[string]any{"text": "Something."},
			map[string]any{"text": "Something."},
		)
	}
	rt, err := runs.New(runs.Config{DatabaseURL: dsn, OllamaHost: cat.OllamaHost, MaxSteps: 6, MockScript: script, SessionKey: []byte("test-session-key")}, st, hub, cat, log)
	if err != nil {
		t.Fatal(err)
	}
	defer rt.Close()
	srv := httptest.NewServer((&api.Server{Store: st, Runtime: rt, Hub: hub, Models: cat, Log: log, AllowSignup: true}).Handler())
	defer srv.Close()

	c := newClient(t, srv.URL)
	c.do("POST", "/api/auth/signup", map[string]string{"email": "budget@example.com", "password": "long-enough-pw"}, nil)
	var pip, grok store.Bot
	c.do("POST", "/api/bots", map[string]any{"name": "Pip", "model": "mockscript:x"}, &pip)
	c.do("POST", "/api/bots", map[string]any{"name": "Grok Bot", "model": "mockscript:x"}, &grok)

	cards := func(id string) (n int) {
		var res struct{ Messages []store.Message }
		c.do("GET", "/api/bots/"+id+"/messages", nil, &res)
		for _, m := range res.Messages {
			if m.Data["kind"] == "botmsg" {
				n++
			}
		}
		return n
	}
	c.do("POST", "/api/bots/"+pip.ID+"/messages", map[string]string{"text": "keep asking Grok Bot"}, nil)
	deadline := time.Now().Add(40 * time.Second)
	for cards(pip.ID) < runs.MaxAsksPerRequest && time.Now().Before(deadline) {
		time.Sleep(150 * time.Millisecond)
	}
	time.Sleep(1500 * time.Millisecond) // nothing more may land
	if got := cards(pip.ID); got != runs.MaxAsksPerRequest {
		t.Fatalf("Pip has %d cards, want the budget %d", got, runs.MaxAsksPerRequest)
	}
	if got := cards(grok.ID); got != runs.MaxAsksPerRequest {
		t.Fatalf("Grok Bot has %d cards, want the budget %d", got, runs.MaxAsksPerRequest)
	}
	// The bot still has the tool, so running out is an answer it reads,
	// not an unknown tool: its chat ends in a reply, with no error.
	var res struct{ Messages []store.Message }
	c.do("GET", "/api/bots/"+pip.ID+"/messages", nil, &res)
	replies := 0
	for _, m := range res.Messages {
		if m.Role == "event" && m.Data["kind"] == "error" {
			t.Fatalf("Pip's chat shows an error: %q", m.Content)
		}
		if m.Role == "bot" {
			replies++
		}
	}
	if replies == 0 {
		t.Fatal("Pip never answered the person")
	}
}
