// Package slack bridges a Slack workspace to a bot: it proves inbound
// requests really came from Slack, reads the events worth acting on, and
// posts replies back into the thread they came from.
package slack

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// maxSkew is how far an inbound timestamp may be from now. Slack's own
// guidance is five minutes; beyond it a captured request is a replay.
const maxSkew = 5 * time.Minute

// Verify reports whether body really came from Slack, signed with this
// install's signing secret.
//
// The comparison is constant time, and a request that is too old is
// refused even when the signature is good, so a captured one cannot be
// replayed later.
func Verify(secret, body []byte, timestamp, signature string, now time.Time) bool {
	if len(secret) == 0 || timestamp == "" || signature == "" {
		return false
	}
	ts, err := strconv.ParseInt(timestamp, 10, 64)
	if err != nil {
		return false
	}
	if d := now.Sub(time.Unix(ts, 0)); d > maxSkew || d < -maxSkew {
		return false
	}
	mac := hmac.New(sha256.New, secret)
	mac.Write([]byte("v0:"))
	mac.Write([]byte(timestamp))
	mac.Write([]byte(":"))
	mac.Write(body)
	want := "v0=" + hex.EncodeToString(mac.Sum(nil))
	return hmac.Equal([]byte(want), []byte(signature))
}

// Envelope is the part of an inbound payload gbot reads.
type Envelope struct {
	Type      string `json:"type"`
	Challenge string `json:"challenge"`
	TeamID    string `json:"team_id"`
	Event     Event  `json:"event"`
}

type Event struct {
	Type     string `json:"type"`
	Subtype  string `json:"subtype"`
	User     string `json:"user"`
	BotID    string `json:"bot_id"`
	Text     string `json:"text"`
	Channel  string `json:"channel"`
	TS       string `json:"ts"`
	ThreadTS string `json:"thread_ts"`
}

// Thread is where a reply belongs: the thread the message started, or the
// message itself when it started no thread.
func (e Event) Thread() string {
	if e.ThreadTS != "" {
		return e.ThreadTS
	}
	return e.TS
}

var (
	mention = regexp.MustCompile(`<@[A-Z0-9]+>`)
	spaces  = regexp.MustCompile(`[ \t]+`)
)

// Prompt is the message text with Slack's user mentions removed, so the
// bot reads a sentence rather than markup. Removing a mention leaves a
// gap, so runs of spaces collapse back to one.
func (e Event) Prompt() string {
	out := mention.ReplaceAllString(e.Text, " ")
	return strings.TrimSpace(spaces.ReplaceAllString(out, " "))
}

// Actionable reports whether this event should start a run.
//
// Anything a bot posted is ignored, including gbot's own replies;
// otherwise a reply would mention the bot again and the two would talk to
// each other forever. Edits, deletions and joins are not requests.
func (e Event) Actionable() bool {
	if e.BotID != "" || e.User == "" || e.Subtype != "" {
		return false
	}
	if e.Type != "app_mention" && e.Type != "message" {
		return false
	}
	return e.Prompt() != ""
}

// Client posts to one workspace.
type Client struct {
	Token string
	HTTP  *http.Client
	// BaseURL is Slack's API root; tests point it elsewhere.
	BaseURL string
}

func (c *Client) base() string {
	if c.BaseURL != "" {
		return c.BaseURL
	}
	return "https://slack.com/api"
}

func (c *Client) httpClient() *http.Client {
	if c.HTTP != nil {
		return c.HTTP
	}
	return &http.Client{Timeout: 15 * time.Second}
}

// Post sends text into a channel, in a thread when one is given.
func (c *Client) Post(ctx context.Context, channel, thread, text string) error {
	payload := map[string]string{"channel": channel, "text": text}
	if thread != "" {
		payload["thread_ts"] = thread
	}
	raw, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.base()+"/chat.postMessage", bytes.NewReader(raw))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json; charset=utf-8")
	req.Header.Set("Authorization", "Bearer "+c.Token)
	resp, err := c.httpClient().Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	// Slack answers 200 with ok:false for real failures, so the status
	// alone does not say whether the message landed.
	var out struct {
		OK    bool   `json:"ok"`
		Error string `json:"error"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return fmt.Errorf("slack: bad response: %w", err)
	}
	if !out.OK {
		return fmt.Errorf("slack: %s", out.Error)
	}
	return nil
}
