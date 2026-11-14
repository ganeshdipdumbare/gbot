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

// Clearing a chat removes the messages and the model thread behind them,
// so the bot starts fresh; what it learned stays unless asked to go too.
func TestClearChat(t *testing.T) {
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
		MockScript: []any{map[string]any{"text": "Hello!"}, map[string]any{"text": "Fresh start."}}, SessionKey: []byte("test-session-key")}, st, hub, cat, log)
	if err != nil {
		t.Fatal(err)
	}
	defer rt.Close()
	srv := httptest.NewServer((&api.Server{Store: st, Runtime: rt, Hub: hub, Models: cat, Log: log, AllowSignup: true}).Handler())
	defer srv.Close()

	c := newClient(t, srv.URL)
	c.do("POST", "/api/auth/signup", map[string]string{"email": "clear@example.com", "password": "long-enough-pw"}, nil)
	var pip store.Bot
	c.do("POST", "/api/bots", map[string]any{"name": "Pip", "model": "mockscript:x"}, &pip)
	messages := func() []store.Message {
		var res struct{ Messages []store.Message }
		c.do("GET", "/api/bots/"+pip.ID+"/messages", nil, &res)
		return res.Messages
	}
	waitBubbles := func(n int) {
		deadline := time.Now().Add(10 * time.Second)
		for {
			got := 0
			for _, m := range messages() {
				if m.Role == "bot" {
					got++
				}
			}
			if got >= n {
				return
			}
			if time.Now().After(deadline) {
				t.Fatalf("timed out waiting for %d replies: %+v", n, messages())
			}
			time.Sleep(100 * time.Millisecond)
		}
	}
	thread := "bot:" + pip.ID
	sessionExists := func() bool {
		var n int
		_ = st.DB.QueryRow(ctx, `SELECT count(*) FROM agent_sessions WHERE id=$1`, thread).Scan(&n)
		return n > 0
	}
	memories := func() int {
		var n int
		_ = st.DB.QueryRow(ctx, `SELECT count(*) FROM agent_memory WHERE ns=$1`, thread).Scan(&n)
		return n
	}

	c.do("POST", "/api/bots/"+pip.ID+"/messages", map[string]string{"text": "hi"}, nil)
	waitBubbles(1)
	if !sessionExists() {
		t.Fatal("the run left no model thread to clear")
	}
	mem := store.NewMemory(st)
	if err := mem.Put(store.WithScope(ctx, thread), "likes", "short answers"); err != nil {
		t.Fatal(err)
	}

	c.do("DELETE", "/api/bots/"+pip.ID+"/messages", nil, nil)
	if m := messages(); len(m) != 0 {
		t.Fatalf("messages left after clearing: %+v", m)
	}
	if sessionExists() {
		t.Fatal("the model thread survived: the bot would still remember the old chat")
	}
	if memories() != 1 {
		t.Fatal("clearing the chat forgot what the bot learned")
	}

	// The chat works again afterwards, from a fresh thread.
	c.do("POST", "/api/bots/"+pip.ID+"/messages", map[string]string{"text": "hello again"}, nil)
	waitBubbles(1)

	c.do("DELETE", "/api/bots/"+pip.ID+"/messages?memory=1", nil, nil)
	if memories() != 0 {
		t.Fatal("memory survived ?memory=1")
	}
	if m := messages(); len(m) != 0 {
		t.Fatalf("messages left after the second clear: %+v", m)
	}
}
