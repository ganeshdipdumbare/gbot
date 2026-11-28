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
			// A streaming reply is stored empty and filled as it writes.
			if m.Role == "bot" && strings.TrimSpace(m.Content) != "" {
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

// One queued message can be steered or cancelled through the API.
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
	// Pause is gone; steer and cancel are what a waiting message offers.
	if code := c.do("POST", "/api/queue/"+q.ID, map[string]string{"action": "pause"}, nil); code != 400 {
		t.Fatalf("pause: %d, want 400", code)
	}
	// Nothing is running to steer it into, so it stays where it is.
	if code := c.do("POST", "/api/queue/"+q.ID, map[string]string{"action": "steer"}, nil); code != 409 {
		t.Fatalf("steer with no turn running: %d, want 409", code)
	}
	if got, _ := st.QueueItem(ctx, q.ID); got.Status != "queued" {
		t.Fatalf("after a refused steer: %q", got.Status)
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

// A waiting message steered into the running turn reaches it with the
// next tool result and doesn't run again on its own. One steered while
// the turn is only writing runs next, ahead of the rest of the line.
func TestQueueSteer(t *testing.T) {
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
		// "first": a slow step that calls a tool, then the reply.
		map[string]any{"tool": "current_time", "wait_ms": 1500},
		map[string]any{"text": "first reply"},
		// "second": a slow reply with no tool to carry a steered message.
		map[string]any{"text": "second reply", "wait_ms": 1500},
		// Then "fourth", steered, goes ahead of "third".
		map[string]any{"text": "fourth reply"},
		map[string]any{"text": "third reply"},
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
	c.do("POST", "/api/auth/signup", map[string]string{"email": "steer@example.com", "password": "long-enough-pw"}, &me)
	var bot store.Bot
	c.do("POST", "/api/bots", map[string]any{"name": "Pip", "model": "mockscript:x"}, &bot)

	item := func(prompt string) *store.QueueItem {
		t.Helper()
		deadline := time.Now().Add(5 * time.Second)
		for {
			var id string
			_ = st.DB.QueryRow(ctx, `SELECT id FROM run_queue WHERE bot_id=$1 AND prompt=$2`, bot.ID, prompt).Scan(&id)
			if id != "" {
				q, err := st.QueueItem(ctx, id)
				if err != nil {
					t.Fatal(err)
				}
				return q
			}
			if time.Now().After(deadline) {
				t.Fatalf("no queue item for %q", prompt)
			}
			time.Sleep(20 * time.Millisecond)
		}
	}
	until := func(what string, ok func() bool) {
		t.Helper()
		deadline := time.Now().Add(15 * time.Second)
		for !ok() {
			if time.Now().After(deadline) {
				t.Fatalf("timed out waiting for %s", what)
			}
			time.Sleep(30 * time.Millisecond)
		}
	}
	send := func(text string) {
		t.Helper()
		if code := c.do("POST", "/api/bots/"+bot.ID+"/messages", map[string]string{"text": text}, nil); code != 201 {
			t.Fatalf("send %q: %d", text, code)
		}
	}
	steer := func(prompt string) {
		t.Helper()
		var res struct{ Items []store.QueueItem }
		if code := c.do("POST", "/api/queue/"+item(prompt).ID, map[string]string{"action": "steer"}, &res); code != 200 {
			t.Fatalf("steer %q: %d", prompt, code)
		}
		for _, q := range res.Items {
			if q.Prompt == prompt && q.Status != "steered" {
				t.Fatalf("steered item shows %q", q.Status)
			}
		}
	}
	replies := func() []string {
		var res struct{ Messages []store.Message }
		c.do("GET", "/api/bots/"+bot.ID+"/messages", nil, &res)
		var out []string
		for _, m := range res.Messages {
			if m.Role == "bot" && strings.TrimSpace(m.Content) != "" {
				out = append(out, m.Content)
			}
		}
		return out
	}

	send("first")
	until("first to start", func() bool { return item("first").Status == "running" })
	send("also check the weather")
	steer("also check the weather")
	until("the first reply", func() bool { return len(replies()) == 1 && item("first").Status == "done" })
	if got := item("also check the weather").Status; got != "done" {
		t.Fatalf("steered message: %q, want done", got)
	}
	sess, err := store.NewSessions(st).Load(ctx, "bot:"+bot.ID)
	if err != nil {
		t.Fatal(err)
	}
	found := false
	for _, m := range sess.Messages {
		if m.Role == "tool" && strings.Contains(m.Content, "the person sent this") && strings.Contains(m.Content, "also check the weather") {
			found = true
		}
	}
	if !found {
		t.Fatalf("the steered message never reached the model: %+v", sess.Messages)
	}

	send("second")
	until("second to start", func() bool { return item("second").Status == "running" })
	send("third")
	send("fourth")
	steer("fourth")
	until("every reply", func() bool { return len(replies()) == 5-1 })
	got := replies()
	want := []string{"first reply", "second reply", "fourth reply", "third reply"}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("replies %q, want %q", got, want)
		}
	}
	third, fourth := item("third"), item("fourth")
	if fourth.StartedAt == nil || third.StartedAt == nil || !fourth.StartedAt.Before(*third.StartedAt) {
		t.Fatalf("the steered message didn't go first: fourth %v, third %v", fourth.StartedAt, third.StartedAt)
	}
}

// In a group each mentioned bot has its own queue item, with its text in
// the transcript. A steered one reaches only its own bot, with that text;
// another bot's steered message runs as its own turn when this one ends.
func TestQueueSteerGroup(t *testing.T) {
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
		map[string]any{"tool": "current_time", "wait_ms": 1500},
		map[string]any{"text": "pip reply"},
		map[string]any{"text": "cardy reply"},
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
	c.do("POST", "/api/auth/signup", map[string]string{"email": "steer-group@example.com", "password": "long-enough-pw"}, &me)
	var pip, cardy store.Bot
	c.do("POST", "/api/bots", map[string]any{"name": "Pip", "model": "mockscript:x"}, &pip)
	c.do("POST", "/api/bots", map[string]any{"name": "Cardy", "model": "mockscript:x"}, &cardy)
	var g store.Group
	if code := c.do("POST", "/api/groups", map[string]any{"name": "Crew", "botIds": []string{pip.ID, cardy.ID}}, &g); code != 201 {
		t.Fatalf("create group: %d", code)
	}
	until := func(what string, ok func() bool) {
		t.Helper()
		deadline := time.Now().Add(15 * time.Second)
		for !ok() {
			if time.Now().After(deadline) {
				t.Fatalf("timed out waiting for %s", what)
			}
			time.Sleep(30 * time.Millisecond)
		}
	}
	// The nth item queued for a bot in the group.
	item := func(botID string, n int) *store.QueueItem {
		t.Helper()
		var q *store.QueueItem
		until("a queue item", func() bool {
			var ids []string
			rows, err := st.DB.Query(ctx, `SELECT id FROM run_queue WHERE group_id=$1 AND bot_id=$2 ORDER BY created_at`, g.ID, botID)
			if err != nil {
				t.Fatal(err)
			}
			for rows.Next() {
				var id string
				_ = rows.Scan(&id)
				ids = append(ids, id)
			}
			rows.Close()
			if len(ids) <= n {
				return false
			}
			q, err = st.QueueItem(ctx, ids[n])
			return err == nil
		})
		return q
	}
	send := func(text string) {
		t.Helper()
		if code := c.do("POST", "/api/groups/"+g.ID+"/messages", map[string]string{"text": text}, nil); code != 201 {
			t.Fatalf("send %q: %d", text, code)
		}
	}
	steer := func(q *store.QueueItem) {
		t.Helper()
		if code := c.do("POST", "/api/queue/"+q.ID, map[string]string{"action": "steer"}, nil); code != 200 {
			t.Fatalf("steer: %d", code)
		}
	}

	send("@Pip start")
	until("Pip to start", func() bool { return item(pip.ID, 0).Status == "running" })
	send("@Pip also check the weather")
	send("@Cardy say hi")
	steer(item(pip.ID, 1))
	steer(item(cardy.ID, 0))
	until("both turns", func() bool {
		return item(pip.ID, 0).Status == "done" && item(cardy.ID, 0).Status == "done"
	})
	if got := item(pip.ID, 1).Status; got != "done" {
		t.Fatalf("Pip's steered message: %q, want done", got)
	}
	var withSteer []string
	rows, err := st.DB.Query(ctx, `SELECT state::text FROM agent_sessions WHERE id LIKE 'grp:%' AND state::text LIKE '%the person sent this%'`)
	if err != nil {
		t.Fatal(err)
	}
	for rows.Next() {
		var s string
		_ = rows.Scan(&s)
		withSteer = append(withSteer, s)
	}
	rows.Close()
	if len(withSteer) != 1 {
		t.Fatalf("%d group turns read a steered message, want 1 (Pip's)", len(withSteer))
	}
	if !strings.Contains(withSteer[0], "also check the weather") || strings.Contains(withSteer[0], "say hi") {
		t.Fatalf("Pip read the wrong steered text: %s", withSteer[0])
	}
	pipFirst, cardyQ := item(pip.ID, 0), item(cardy.ID, 0)
	if cardyQ.StartedAt == nil || pipFirst.FinishedAt == nil || cardyQ.StartedAt.Before(*pipFirst.StartedAt) {
		t.Fatalf("Cardy's turn: started %v, Pip's %v", cardyQ.StartedAt, pipFirst.StartedAt)
	}
}
