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

// A message a previous process recorded runs after a restart, in order.
func TestQueueSurvivesRestart(t *testing.T) {
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
	script := []any{
		map[string]any{"text": "resumed one"},
		map[string]any{"text": "resumed two"},
	}
	hub := runs.NewHub()
	rt, err := runs.New(runs.Config{DatabaseURL: dsn, OllamaHost: cat.OllamaHost, MaxSteps: 6, MockScript: script}, st, hub, cat, log)
	if err != nil {
		t.Fatal(err)
	}
	defer rt.Close()
	srv := httptest.NewServer((&api.Server{Store: st, Runtime: rt, Hub: hub, Models: cat, Log: log, AllowSignup: true}).Handler())
	defer srv.Close()

	c := newClient(t, srv.URL)
	var me store.User
	if code := c.do("POST", "/api/auth/signup", map[string]string{"email": "queue@example.com", "password": "long-enough-pw"}, &me); code != 200 {
		t.Fatalf("signup: %d", code)
	}
	var bot store.Bot
	if code := c.do("POST", "/api/bots", map[string]any{"name": "Pip", "model": "mockscript:x"}, &bot); code != 201 {
		t.Fatalf("create bot: %d", code)
	}

	// Two messages the previous process wrote down but never ran.
	for _, text := range []string{"one", "two"} {
		if err := st.Enqueue(ctx, &store.QueueItem{
			SpaceID: me.SpaceID, BotID: bot.ID, ChatUser: me.ID, Prompt: text, Trigger: "chat",
		}); err != nil {
			t.Fatal(err)
		}
	}
	rt.Recover(ctx)

	deadline := time.Now().Add(15 * time.Second)
	var replies []string
	for {
		var res struct{ Messages []store.Message }
		c.do("GET", "/api/bots/"+bot.ID+"/messages", nil, &res)
		replies = replies[:0]
		for _, m := range res.Messages {
			if m.Role == "bot" {
				replies = append(replies, m.Content)
			}
		}
		if len(replies) >= 2 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("timed out; replies %q", replies)
		}
		time.Sleep(50 * time.Millisecond)
	}
	if replies[0] != "resumed one" || replies[1] != "resumed two" {
		t.Fatalf("queued messages ran out of order: %q", replies)
	}
	// The reply is published just before the run records its queue row as
	// done, so wait for the last update.
	var q struct{ Items []store.QueueItem }
	deadline = time.Now().Add(5 * time.Second)
	for {
		c.do("GET", "/api/bots/"+bot.ID+"/queue", nil, &q)
		if len(q.Items) == 0 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("finished queue still has %d items", len(q.Items))
		}
		time.Sleep(50 * time.Millisecond)
	}

	// A turn the crash interrupted is marked failed, not replayed twice.
	q3 := &store.QueueItem{SpaceID: me.SpaceID, BotID: bot.ID, ChatUser: me.ID, Prompt: "lost", Trigger: "chat"}
	if err := st.Enqueue(ctx, q3); err != nil {
		t.Fatal(err)
	}
	if err := st.SetQueueStatus(ctx, q3.ID, "running", ""); err != nil {
		t.Fatal(err)
	}
	rt.Recover(ctx)
	got, err := st.QueueItem(ctx, q3.ID)
	if err != nil {
		t.Fatal(err)
	}
	if got.Status != "error" || got.Error == "" {
		t.Fatalf("interrupted turn: %+v", got)
	}
}

// One queued message can be paused, resumed and cancelled through the API.
func TestQueueItemActions(t *testing.T) {
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
	rt, err := runs.New(runs.Config{DatabaseURL: dsn, OllamaHost: cat.OllamaHost, MaxSteps: 6, MockScript: []any{map[string]any{"text": "ok"}}}, st, hub, cat, log)
	if err != nil {
		t.Fatal(err)
	}
	defer rt.Close()
	srv := httptest.NewServer((&api.Server{Store: st, Runtime: rt, Hub: hub, Models: cat, Log: log, AllowSignup: true}).Handler())
	defer srv.Close()

	c := newClient(t, srv.URL)
	var me store.User
	c.do("POST", "/api/auth/signup", map[string]string{"email": "queue2@example.com", "password": "long-enough-pw"}, &me)
	var bot store.Bot
	c.do("POST", "/api/bots", map[string]any{"name": "Pip", "model": "mockscript:x"}, &bot)

	// Record it but keep it out of the lane, as if it were waiting.
	q := &store.QueueItem{SpaceID: me.SpaceID, BotID: bot.ID, ChatUser: me.ID, Prompt: "later", Trigger: "chat"}
	if err := st.Enqueue(ctx, q); err != nil {
		t.Fatal(err)
	}
	if code := c.do("POST", "/api/queue/"+q.ID, map[string]string{"action": "pause"}, nil); code != 200 {
		t.Fatalf("pause: %d", code)
	}
	if got, _ := st.QueueItem(ctx, q.ID); got.Status != "paused" {
		t.Fatalf("after pause: %q", got.Status)
	}
	if code := c.do("POST", "/api/queue/"+q.ID, map[string]string{"action": "cancel"}, nil); code != 200 {
		t.Fatalf("cancel: %d", code)
	}
	if got, _ := st.QueueItem(ctx, q.ID); got.Status != "cancelled" {
		t.Fatalf("after cancel: %q", got.Status)
	}

	// Clearing drops what waits, not the turn that is running now.
	running := &store.QueueItem{SpaceID: me.SpaceID, BotID: bot.ID, ChatUser: me.ID, Prompt: "now", Trigger: "chat"}
	waiting := &store.QueueItem{SpaceID: me.SpaceID, BotID: bot.ID, ChatUser: me.ID, Prompt: "later", Trigger: "chat"}
	for _, item := range []*store.QueueItem{running, waiting} {
		if err := st.Enqueue(ctx, item); err != nil {
			t.Fatal(err)
		}
	}
	if err := st.SetQueueStatus(ctx, running.ID, "running", ""); err != nil {
		t.Fatal(err)
	}
	if err := rt.ClearQueue(ctx, me.SpaceID, bot.ID, me.ID, ""); err != nil {
		t.Fatal(err)
	}
	if got, _ := st.QueueItem(ctx, running.ID); got.Status != "running" {
		t.Fatalf("clear stopped the running turn: %q", got.Status)
	}
	if got, _ := st.QueueItem(ctx, waiting.ID); got.Status != "cancelled" {
		t.Fatalf("clear left a waiting message: %q", got.Status)
	}
}
