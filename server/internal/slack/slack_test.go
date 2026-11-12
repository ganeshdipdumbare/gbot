package slack_test

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"

	"gbot/internal/slack"
)

func sign(secret, body []byte, ts int64) string {
	mac := hmac.New(sha256.New, secret)
	mac.Write([]byte("v0:" + strconv.FormatInt(ts, 10) + ":"))
	mac.Write(body)
	return "v0=" + hex.EncodeToString(mac.Sum(nil))
}

func TestVerify(t *testing.T) {
	secret := []byte("s3cr3t")
	body := []byte(`{"type":"event_callback"}`)
	now := time.Unix(1_700_000_000, 0)
	ts := now.Unix()
	good := sign(secret, body, ts)

	if !slack.Verify(secret, body, strconv.FormatInt(ts, 10), good, now) {
		t.Fatal("a correctly signed request was refused")
	}

	cases := []struct {
		name string
		sig  string
		ts   int64
		body []byte
	}{
		{"wrong signature", sign([]byte("other"), body, ts), ts, body},
		{"tampered body", good, ts, []byte(`{"type":"evil"}`)},
		{"empty signature", "", ts, body},
		{"replay, six minutes old", sign(secret, body, now.Add(-6*time.Minute).Unix()), now.Add(-6 * time.Minute).Unix(), body},
		{"far future", sign(secret, body, now.Add(6*time.Minute).Unix()), now.Add(6 * time.Minute).Unix(), body},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if slack.Verify(secret, c.body, strconv.FormatInt(c.ts, 10), c.sig, now) {
				t.Fatal("accepted a request it should have refused")
			}
		})
	}

	t.Run("no secret", func(t *testing.T) {
		if slack.Verify(nil, body, strconv.FormatInt(ts, 10), good, now) {
			t.Fatal("accepted a request with no signing secret configured")
		}
	})
}

func TestActionable(t *testing.T) {
	cases := []struct {
		name string
		e    slack.Event
		want bool
	}{
		{"a mention", slack.Event{Type: "app_mention", User: "U1", Text: "<@U9> ship it"}, true},
		{"a plain message", slack.Event{Type: "message", User: "U1", Text: "status?"}, true},
		{"from a bot", slack.Event{Type: "message", BotID: "B1", User: "U1", Text: "hi"}, false},
		{"an edit", slack.Event{Type: "message", Subtype: "message_changed", User: "U1", Text: "hi"}, false},
		{"a join", slack.Event{Type: "message", Subtype: "channel_join", User: "U1", Text: "joined"}, false},
		{"only a mention, no words", slack.Event{Type: "app_mention", User: "U1", Text: "<@U9>"}, false},
		{"no user", slack.Event{Type: "message", Text: "hi"}, false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := c.e.Actionable(); got != c.want {
				t.Fatalf("Actionable() = %v, want %v", got, c.want)
			}
		})
	}
}

func TestPromptAndThread(t *testing.T) {
	e := slack.Event{Type: "app_mention", User: "U1", Text: "<@U9> find the <@U7> docs", TS: "111.1"}
	if got := e.Prompt(); got != "find the docs" {
		t.Fatalf("Prompt() = %q", got)
	}
	if got := e.Thread(); got != "111.1" {
		t.Fatalf("Thread() on a new message = %q, want the message ts", got)
	}
	e.ThreadTS = "100.0"
	if got := e.Thread(); got != "100.0" {
		t.Fatalf("Thread() in a thread = %q, want the thread ts", got)
	}
}

func TestPostReportsSlackLevelFailure(t *testing.T) {
	var gotAuth, gotBody string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth = r.Header.Get("Authorization")
		b := make([]byte, r.ContentLength)
		r.Body.Read(b)
		gotBody = string(b)
		json.NewEncoder(w).Encode(map[string]any{"ok": true})
	}))
	defer srv.Close()

	c := &slack.Client{Token: "xoxb-1", BaseURL: srv.URL}
	if err := c.Post(context.Background(), "C1", "100.0", "done"); err != nil {
		t.Fatalf("Post: %v", err)
	}
	if gotAuth != "Bearer xoxb-1" {
		t.Fatalf("auth header = %q", gotAuth)
	}
	if !json.Valid([]byte(gotBody)) {
		t.Fatalf("body was not JSON: %q", gotBody)
	}

	// Slack answers 200 with ok:false; that is still a failure.
	bad := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]any{"ok": false, "error": "channel_not_found"})
	}))
	defer bad.Close()
	c.BaseURL = bad.URL
	err := c.Post(context.Background(), "C1", "", "hi")
	if err == nil {
		t.Fatal("a 200 with ok:false was treated as success")
	}
}
