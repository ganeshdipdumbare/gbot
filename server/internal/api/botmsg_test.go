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
	// Pip's run and Grok Bot's run race once Pip sends (it doesn't
	// wait), so the steps they share say the same thing.
	script := []any{
		map[string]any{"tool": "message_bot", "args": map[string]any{"to": "@grok bot", "message": "What's 6 times 7?"}},
		map[string]any{"text": "Ok, 42."},
		map[string]any{"text": "Ok, 42."},
		map[string]any{"text": "Grok Bot says 42."}, // Pip, woken by the reply
		map[string]any{"tool": "message_bot", "args": map[string]any{"to": "Grok Bot", "message": "The report is ready."}},
		map[string]any{"text": "NO_REPLY"},
		map[string]any{"text": "NO_REPLY"},
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

	// Pip sends without waiting; Grok Bot's reply wakes Pip.
	c.do("POST", "/api/bots/"+pip.ID+"/messages", map[string]string{"text": "ask Grok Bot what 6 times 7 is"}, nil)
	waitFor("Pip to hear back", func() bool { b := bubbles(pip.ID); return len(b) == 2 && b[1] == "Grok Bot says 42." })
	out, in := card(pip.ID, "What's 6 times 7?"), card(grok.ID, "What's 6 times 7?")
	if out.Content != "Messaged Grok Bot" || in.Content != "Message from Pip" {
		t.Fatalf("cards say %q and %q", out.Content, in.Content)
	}
	for _, m := range []store.Message{out, in} {
		if m.Data["reply"] != "Ok, 42." || m.Data["status"] != "replied" || m.Data["from"] != pip.ID {
			t.Fatalf("exchange card: %+v", m.Data)
		}
	}
	// Grok Bot's chat stays the person's.
	for _, m := range messages(grok.ID) {
		if m.Role == "user" || m.Role == "bot" {
			t.Fatalf("the exchange leaked into Grok Bot's chat: %+v", m)
		}
	}

	// Nothing to send back: the card says delivered and Pip isn't woken.
	c.do("POST", "/api/bots/"+pip.ID+"/messages", map[string]string{"text": "tell Grok Bot the report is ready"}, nil)
	waitFor("delivery", func() bool { return card(grok.ID, "The report is ready.").Data["status"] == "delivered" })
	time.Sleep(300 * time.Millisecond)
	// Pip's own "NO_REPLY" after sending reads as a send announcement and
	// is dropped too, so Pip has nothing new.
	if b := bubbles(pip.ID); len(b) != 2 {
		t.Fatalf("Pip shouldn't be woken by NO_REPLY; bubbles %q", b)
	}
}
