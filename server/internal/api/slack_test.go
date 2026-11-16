package api_test

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strconv"
	"testing"
	"time"

	"gbot/internal/api"
	"gbot/internal/crypto"
	"gbot/internal/models"
	"gbot/internal/runs"
	"gbot/internal/store"
)

const signingSecret = "slack-signing-secret"

// post sends a raw Slack payload, signed unless the signature is overridden.
func postSlack(t *testing.T, base string, payload any, sig string, extra map[string]string) (int, map[string]any) {
	t.Helper()
	body, _ := json.Marshal(payload)
	ts := strconv.FormatInt(time.Now().Unix(), 10)
	if sig == "" {
		mac := hmac.New(sha256.New, []byte(signingSecret))
		mac.Write([]byte("v0:" + ts + ":"))
		mac.Write(body)
		sig = "v0=" + hex.EncodeToString(mac.Sum(nil))
	}
	req, _ := http.NewRequest("POST", base+"/slack/events", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Slack-Request-Timestamp", ts)
	req.Header.Set("X-Slack-Signature", sig)
	for k, v := range extra {
		req.Header.Set(k, v)
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	var out map[string]any
	_ = json.NewDecoder(res.Body).Decode(&out)
	return res.StatusCode, out
}

func TestSlackBridge(t *testing.T) {
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
	cat.Extra = []models.Model{{ID: "mock:test", Label: "mock", Provider: "mock", Tools: true}}
	hub := runs.NewHub()
	rt, err := runs.New(runs.Config{DatabaseURL: dsn, OllamaHost: cat.OllamaHost, MaxSteps: 6, Mock: true}, st, hub, cat, log)
	if err != nil {
		t.Fatal(err)
	}
	defer rt.Close()
	sealer, err := crypto.Load(filepath.Join(t.TempDir(), "secret.key"))
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer((&api.Server{Store: st, Runtime: rt, Hub: hub, Models: cat, Log: log, Sealer: sealer, AllowSignup: true}).Handler())
	defer srv.Close()

	alice := newClient(t, srv.URL)
	var me store.User
	if code := alice.do("POST", "/api/auth/signup", map[string]string{"email": "alice@example.com", "password": "long-enough-pw"}, &me); code != 200 {
		t.Fatalf("signup: %d", code)
	}
	var bots struct{ Bots []store.Bot }
	alice.do("GET", "/api/bots", nil, &bots)
	if len(bots.Bots) == 0 {
		t.Fatal("no starter bot")
	}
	bot := bots.Bots[0]

	var install map[string]any
	if code := alice.do("POST", "/api/slack", map[string]string{
		"teamId": "T123", "teamName": "Acme", "botToken": "xoxb-secret", "signingSecret": signingSecret,
	}, &install); code != 201 {
		t.Fatalf("connect slack: %d", code)
	}
	installID, _ := install["id"].(string)
	if installID == "" {
		t.Fatalf("no install id: %+v", install)
	}

	t.Run("secrets never come back", func(t *testing.T) {
		var listed map[string]any
		alice.do("GET", "/api/slack", nil, &listed)
		raw, _ := json.Marshal(listed)
		for _, secret := range []string{"xoxb-secret", signingSecret} {
			if bytes.Contains(raw, []byte(secret)) {
				t.Fatalf("a Slack secret was returned by the API: %s", raw)
			}
		}
	})

	t.Run("the setup handshake is echoed", func(t *testing.T) {
		code, out := postSlack(t, srv.URL, map[string]string{"type": "url_verification", "challenge": "abc123"}, "", nil)
		if code != 200 || out["challenge"] != "abc123" {
			t.Fatalf("url_verification: %d %+v", code, out)
		}
	})

	event := func() map[string]any {
		return map[string]any{
			"type": "event_callback", "team_id": "T123",
			"event": map[string]any{
				"type": "app_mention", "user": "U1", "text": "<@U9> check the build",
				"channel": "C1", "ts": "100.1",
			},
		}
	}

	t.Run("a bad signature is refused", func(t *testing.T) {
		if code, _ := postSlack(t, srv.URL, event(), "v0=deadbeef", nil); code != 401 {
			t.Fatalf("want 401 for a forged signature, got %d", code)
		}
	})

	t.Run("an unknown workspace is refused", func(t *testing.T) {
		e := event()
		e["team_id"] = "T-other"
		if code, _ := postSlack(t, srv.URL, e, "", nil); code != 401 {
			t.Fatalf("want 401 for an unknown workspace, got %d", code)
		}
	})

	t.Run("an unlinked channel is accepted but does nothing", func(t *testing.T) {
		if code, _ := postSlack(t, srv.URL, event(), "", nil); code != 200 {
			t.Fatalf("want 200, got %d", code)
		}
		var msgs struct{ Messages []store.Message }
		alice.do("GET", "/api/bots/"+bot.ID+"/messages", nil, &msgs)
		for _, m := range msgs.Messages {
			if m.Content == "check the build" {
				t.Fatal("a message arrived for a channel that is not linked to any bot")
			}
		}
	})

	if code := alice.do("PUT", "/api/slack/"+installID+"/links", map[string]string{
		"channelId": "C1", "channel": "#build", "botId": bot.ID,
	}, nil); code != 200 {
		t.Fatalf("link channel: %d", code)
	}

	t.Run("a linked channel reaches the bot", func(t *testing.T) {
		// A fresh event: the earlier one was already delivered, and Slack
		// retries of the same request are acknowledged, not handled again.
		fresh := event()
		fresh["event"].(map[string]any)["ts"] = "100.2"
		if code, _ := postSlack(t, srv.URL, fresh, "", nil); code != 200 {
			t.Fatalf("want 200, got %d", code)
		}
		if !hasMessage(t, alice, bot.ID, "check the build") {
			t.Fatal("the Slack message never reached the bot's chat")
		}
	})

	t.Run("a redelivery does not run twice", func(t *testing.T) {
		before := countMessage(t, alice, bot.ID, "check the build")
		if code, _ := postSlack(t, srv.URL, event(), "", map[string]string{"X-Slack-Retry-Num": "1"}); code != 200 {
			t.Fatalf("want 200 for a retry, got %d", code)
		}
		time.Sleep(300 * time.Millisecond)
		if after := countMessage(t, alice, bot.ID, "check the build"); after != before {
			t.Fatalf("a Slack retry created another message: %d -> %d", before, after)
		}
	})

	t.Run("a bot's own message is ignored", func(t *testing.T) {
		e := event()
		ev := e["event"].(map[string]any)
		ev["bot_id"] = "B9"
		ev["text"] = "<@U9> an echo of my own reply"
		if code, _ := postSlack(t, srv.URL, e, "", nil); code != 200 {
			t.Fatalf("want 200, got %d", code)
		}
		time.Sleep(200 * time.Millisecond)
		if hasMessage(t, alice, bot.ID, "an echo of my own reply") {
			t.Fatal("a message posted by a bot started a run, which would loop")
		}
	})
}

func countMessage(t *testing.T, c *client, botID, want string) int {
	t.Helper()
	var msgs struct{ Messages []store.Message }
	c.do("GET", "/api/bots/"+botID+"/messages", nil, &msgs)
	n := 0
	for _, m := range msgs.Messages {
		if m.Content == want {
			n++
		}
	}
	return n
}

func hasMessage(t *testing.T, c *client, botID, want string) bool {
	t.Helper()
	for i := 0; i < 40; i++ {
		if countMessage(t, c, botID, want) > 0 {
			return true
		}
		time.Sleep(50 * time.Millisecond)
	}
	return false
}
