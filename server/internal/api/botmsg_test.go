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

// A bot asks another bot without waiting. The answer lands on both
// exchange cards, and goes back to the sender in one run of its own once
// every ask from that turn is in. A tell wakes nobody.
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
	ask := func(to, msg string) map[string]any {
		return map[string]any{"tool": "message_bot", "args": map[string]any{"to": to, "message": msg}}
	}
	// Runs that start together race for the next step, so the steps they
	// share say the same thing. A round's answers only go back once all
	// of them are in, so the join's step is never raced.
	script := []any{
		ask("@grok bot", "What's 6 times 7?"),
		map[string]any{"text": "Ok, 42."},
		map[string]any{"text": "Ok, 42."},
		map[string]any{"text": "Grok Bot says 42."},
		map[string]any{"tool": "message_bot", "args": map[string]any{"to": "Grok Bot", "message": "The report is ready.", "expect": "tell"}},
		map[string]any{"text": "NO_REPLY"},
		map[string]any{"text": "NO_REPLY"},
		map[string]any{"tools": []any{ask("Grok Bot", "Name a colour."), ask("Taro", "Name a fruit.")}},
		map[string]any{"text": "Done."},
		map[string]any{"text": "Done."},
		map[string]any{"text": "Done."},
		map[string]any{"text": "Both answered."},
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
	var pip, grok, taro store.Bot
	c.do("POST", "/api/bots", map[string]any{"name": "Pip", "model": "mockscript:x"}, &pip)
	c.do("POST", "/api/bots", map[string]any{"name": "Grok Bot", "model": "mockscript:x"}, &grok)
	c.do("POST", "/api/bots", map[string]any{"name": "Taro", "model": "mockscript:x"}, &taro)

	messages := func(id string) []store.Message {
		var res struct{ Messages []store.Message }
		c.do("GET", "/api/bots/"+id+"/messages", nil, &res)
		return res.Messages
	}
	waitFor := func(what string, ok func() bool) {
		deadline := time.Now().Add(15 * time.Second)
		for !ok() {
			if time.Now().After(deadline) {
				t.Fatalf("timed out waiting for %s; Pip: %+v; Grok Bot: %+v", what, messages(pip.ID), messages(grok.ID))
			}
			time.Sleep(100 * time.Millisecond)
		}
	}
	card := func(id, message string) store.Message {
		for _, m := range messages(id) {
			if m.Data["kind"] == "botmsg" && m.Data["message"] == message {
				return m
			}
		}
		return store.Message{}
	}
	bubbles := func(id string) (out []string) {
		for _, m := range messages(id) {
			if m.Role == "bot" {
				out = append(out, m.Content)
			}
		}
		return out
	}
	settled := func(id string, n int) []string {
		waitFor("Pip's chat to settle", func() bool { return len(bubbles(id)) >= n })
		time.Sleep(400 * time.Millisecond)
		return bubbles(id)
	}

	// Pip asks without waiting. The answer lands on both cards.
	c.do("POST", "/api/bots/"+pip.ID+"/messages", map[string]string{"text": "ask Grok Bot what 6 times 7 is"}, nil)
	waitFor("the reply on both cards", func() bool {
		return card(pip.ID, "What's 6 times 7?").Data["status"] == "replied" && card(grok.ID, "What's 6 times 7?").Data["status"] == "replied"
	})
	out, in := card(pip.ID, "What's 6 times 7?"), card(grok.ID, "What's 6 times 7?")
	if out.Content != "Messaged Grok Bot" || in.Content != "Message from Pip" {
		t.Fatalf("cards say %q and %q", out.Content, in.Content)
	}
	for _, m := range []store.Message{out, in} {
		if m.Data["reply"] != "Ok, 42." || m.Data["from"] != pip.ID || m.Data["expect"] != "ask" || m.Data["round"] == nil ||
			m.Data["ask"] == nil || m.Data["root"] == nil || m.Data["depth"] != float64(1) {
			t.Fatalf("exchange card: %+v", m.Data)
		}
	}
	// Grok Bot's chat stays the person's.
	for _, m := range messages(grok.ID) {
		if m.Role == "user" || m.Role == "bot" {
			t.Fatalf("the exchange leaked into Grok Bot's chat: %+v", m)
		}
	}
	// Pip gets the answer back exactly once, in a run of its own.
	if b := settled(pip.ID, 2); len(b) != 2 || b[0] != "Ok, 42." || b[1] != "Grok Bot says 42." {
		t.Fatalf("Pip's chat after the answer: %q", b)
	}
	waitFor("the round marked joined", func() bool { return card(pip.ID, "What's 6 times 7?").Data["joined"] == true })
	if card(pip.ID, "What's 6 times 7?").Data["tookMs"] == nil {
		t.Fatal("the joined round has no duration")
	}

	// A tell: delivered, and nothing wakes Pip.
	c.do("POST", "/api/bots/"+pip.ID+"/messages", map[string]string{"text": "tell Grok Bot the report is ready"}, nil)
	waitFor("delivery", func() bool { return card(grok.ID, "The report is ready.").Data["status"] == "delivered" })
	if tell := card(pip.ID, "The report is ready."); tell.Data["expect"] != "tell" || tell.Data["round"] != nil {
		t.Fatalf("tell card: %+v", tell.Data)
	}
	// Pip's "NO_REPLY" after sending reads as a send announcement and is
	// dropped, so Pip's chat gains nothing new.
	if b := settled(pip.ID, 2); len(b) != 2 {
		t.Fatalf("Pip's chat changed after a tell; bubbles %q", b)
	}

	// Two asks in one turn come back together, in one run.
	c.do("POST", "/api/bots/"+pip.ID+"/messages", map[string]string{"text": "ask Grok Bot for a colour and Taro for a fruit"}, nil)
	waitFor("both answers", func() bool {
		return card(pip.ID, "Name a colour.").Data["status"] == "replied" && card(pip.ID, "Name a fruit.").Data["status"] == "replied"
	})
	if a, b := card(pip.ID, "Name a colour."), card(pip.ID, "Name a fruit."); a.Data["round"] != b.Data["round"] {
		t.Fatalf("one turn's asks landed in two rounds: %v and %v", a.Data["round"], b.Data["round"])
	}
	if b := settled(pip.ID, 4); len(b) != 4 || b[3] != "Both answered." {
		t.Fatalf("Pip's chat after two answers: %q", b)
	}
}
