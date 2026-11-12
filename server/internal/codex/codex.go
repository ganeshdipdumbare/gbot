// Package codex lets gbot use OpenAI models through the ChatGPT login that
// the Codex CLI saved on this machine. Nothing is copied: the login stays
// in Codex's own auth.json, is read on every call, and refreshed tokens are
// written back so Codex keeps working too.
package codex

import (
	"bufio"
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// BaseURL is the Responses endpoint Codex itself talks to.
const BaseURL = "https://chatgpt.com/backend-api/codex"

const (
	tokenURL = "https://auth.openai.com/oauth/token"
	clientID = "app_EMoamEEZ73f0CkXaXp7hrann"
)

// Home is the Codex data directory: GBOT_CODEX_HOME, then CODEX_HOME,
// then ~/.codex.
func Home() string {
	for _, k := range []string{"GBOT_CODEX_HOME", "CODEX_HOME"} {
		if v := strings.TrimSpace(os.Getenv(k)); v != "" {
			return v
		}
	}
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".codex")
}

func authPath() string { return filepath.Join(Home(), "auth.json") }

// Allowed reports whether an account may use this machine's Codex login.
// The operator lists the emails in GBOT_CODEX_USERS; nobody else can,
// because the login is the machine owner's ChatGPT subscription.
func Allowed(email string) bool {
	email = strings.ToLower(strings.TrimSpace(email))
	if email == "" {
		return false
	}
	for _, e := range strings.Split(os.Getenv("GBOT_CODEX_USERS"), ",") {
		if strings.ToLower(strings.TrimSpace(e)) == email {
			return true
		}
	}
	return false
}

// Available reports whether Codex is signed in with ChatGPT here.
func Available() bool {
	a, err := readAuth()
	return err == nil && a.access() != ""
}

type authFile map[string]any

func (a authFile) tokens() map[string]any {
	t, _ := a["tokens"].(map[string]any)
	return t
}

func (a authFile) str(k string) string {
	s, _ := a.tokens()[k].(string)
	return s
}

func (a authFile) access() string  { return a.str("access_token") }
func (a authFile) account() string { return a.str("account_id") }

func readAuth() (authFile, error) {
	b, err := os.ReadFile(authPath())
	if err != nil {
		return nil, errors.New("Codex isn't signed in on this server (run `codex login`)")
	}
	var a authFile
	if err := json.Unmarshal(b, &a); err != nil || a.tokens() == nil {
		return nil, errors.New("Codex isn't signed in with ChatGPT on this server")
	}
	return a, nil
}

// expiry reads a JWT's exp without verifying it; the server verifies.
func expiry(jwt string) time.Time {
	parts := strings.Split(jwt, ".")
	if len(parts) < 2 {
		return time.Time{}
	}
	raw, err := base64.RawURLEncoding.DecodeString(strings.TrimRight(parts[1], "="))
	if err != nil {
		return time.Time{}
	}
	var c struct {
		Exp int64 `json:"exp"`
	}
	if json.Unmarshal(raw, &c) != nil || c.Exp == 0 {
		return time.Time{}
	}
	return time.Unix(c.Exp, 0)
}

var refreshMu sync.Mutex

// Token returns a valid access token and the ChatGPT account id,
// refreshing (and saving) the login when it is about to expire.
func Token(ctx context.Context, client *http.Client) (string, string, error) {
	a, err := readAuth()
	if err != nil {
		return "", "", err
	}
	if exp := expiry(a.access()); a.access() != "" && (exp.IsZero() || time.Until(exp) > 5*time.Minute) {
		return a.access(), a.account(), nil
	}
	refreshMu.Lock()
	defer refreshMu.Unlock()
	// Codex may have refreshed it meanwhile.
	if a, err = readAuth(); err != nil {
		return "", "", err
	}
	if time.Until(expiry(a.access())) > 5*time.Minute {
		return a.access(), a.account(), nil
	}
	rt := a.str("refresh_token")
	if rt == "" {
		return "", "", errors.New("the Codex login expired; run `codex login` again")
	}
	body, _ := json.Marshal(map[string]string{
		"client_id": clientID, "grant_type": "refresh_token", "refresh_token": rt, "scope": "openid profile email",
	})
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost, tokenURL, bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	resp, err := client.Do(req)
	if err != nil {
		return "", "", fmt.Errorf("refreshing the Codex login: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		return "", "", fmt.Errorf("the Codex login expired (%s); run `codex login` again", resp.Status)
	}
	var out struct {
		Access  string `json:"access_token"`
		ID      string `json:"id_token"`
		Refresh string `json:"refresh_token"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil || out.Access == "" {
		return "", "", errors.New("refreshing the Codex login returned no token")
	}
	t := a.tokens()
	t["access_token"] = out.Access
	if out.ID != "" {
		t["id_token"] = out.ID
	}
	if out.Refresh != "" {
		t["refresh_token"] = out.Refresh
	}
	a["last_refresh"] = time.Now().UTC().Format(time.RFC3339Nano)
	if err := writeAuth(a); err != nil {
		return "", "", fmt.Errorf("saving the refreshed Codex login: %w", err)
	}
	return out.Access, a.account(), nil
}

func writeAuth(a authFile) error {
	b, err := json.MarshalIndent(a, "", "  ")
	if err != nil {
		return err
	}
	tmp, err := os.CreateTemp(Home(), ".auth-*.json")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	if err := tmp.Chmod(0o600); err != nil {
		tmp.Close()
		return err
	}
	if _, err := tmp.Write(b); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmp.Name(), authPath())
}

// Models lists the models this ChatGPT plan can use, from the catalog
// Codex caches, most preferred first.
func Models() ([]string, error) {
	if !Available() {
		_, err := readAuth()
		if err == nil {
			err = errors.New("Codex isn't signed in with ChatGPT on this server")
		}
		return nil, err
	}
	b, err := os.ReadFile(filepath.Join(Home(), "models_cache.json"))
	if err != nil {
		return nil, errors.New("open Codex once so it can fetch your model list")
	}
	var c struct {
		Models []struct {
			Slug       string `json:"slug"`
			Visibility string `json:"visibility"`
			InAPI      bool   `json:"supported_in_api"`
			Priority   int    `json:"priority"`
		} `json:"models"`
	}
	if err := json.Unmarshal(b, &c); err != nil {
		return nil, errors.New("couldn't read Codex's model list")
	}
	sort.SliceStable(c.Models, func(i, j int) bool { return c.Models[i].Priority < c.Models[j].Priority })
	var out []string
	for _, m := range c.Models {
		if m.Slug != "" && m.Visibility == "list" && m.InAPI {
			out = append(out, m.Slug)
		}
	}
	if len(out) == 0 {
		return nil, errors.New("Codex lists no models for this account")
	}
	return out, nil
}

// Transport adapts Responses API calls to the Codex endpoint: it signs
// them with the ChatGPT login, always streams and never stores, and turns
// the stream back into one JSON body for callers that didn't ask to
// stream.
type Transport struct {
	Base http.RoundTripper
}

// Client is an HTTP client for the Codex endpoint.
func Client(timeout time.Duration) *http.Client {
	return &http.Client{Transport: Transport{}, Timeout: timeout}
}

func (t Transport) base() http.RoundTripper {
	if t.Base != nil {
		return t.Base
	}
	return http.DefaultTransport
}

func (t Transport) RoundTrip(r *http.Request) (*http.Response, error) {
	access, account, err := Token(r.Context(), &http.Client{Transport: t.base(), Timeout: 30 * time.Second})
	if err != nil {
		return nil, err
	}
	wantStream := true
	var body []byte
	if r.Body != nil {
		raw, err := io.ReadAll(r.Body)
		r.Body.Close()
		if err != nil {
			return nil, err
		}
		var m map[string]any
		if json.Unmarshal(raw, &m) == nil {
			wantStream, _ = m["stream"].(bool)
			m["stream"] = true
			m["store"] = false
			delete(m, "temperature")
			raw, _ = json.Marshal(m)
		}
		body = raw
	}
	req := r.Clone(r.Context())
	req.Body = io.NopCloser(bytes.NewReader(body))
	req.ContentLength = int64(len(body))
	req.Header.Set("Authorization", "Bearer "+access)
	if account != "" {
		req.Header.Set("chatgpt-account-id", account)
	}
	req.Header.Set("OpenAI-Beta", "responses=experimental")
	req.Header.Set("originator", "codex_cli_rs")
	req.Header.Set("Accept", "text/event-stream")
	resp, err := t.base().RoundTrip(req)
	if err != nil || wantStream || resp.StatusCode/100 != 2 {
		return resp, err
	}
	defer resp.Body.Close()
	final, err := collect(resp.Body)
	if err != nil {
		return nil, err
	}
	resp.Body = io.NopCloser(bytes.NewReader(final))
	resp.ContentLength = int64(len(final))
	resp.Header.Set("Content-Type", "application/json")
	resp.Header.Del("Content-Length")
	return resp, nil
}

// collect reads a Responses stream to its final response object.
func collect(r io.Reader) ([]byte, error) {
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 0, 64*1024), 8<<20)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if !strings.HasPrefix(line, "data:") {
			continue
		}
		var ev struct {
			Type     string          `json:"type"`
			Response json.RawMessage `json:"response"`
			Error    *struct {
				Message string `json:"message"`
			} `json:"error"`
		}
		if json.Unmarshal([]byte(strings.TrimSpace(strings.TrimPrefix(line, "data:"))), &ev) != nil {
			continue
		}
		switch ev.Type {
		case "response.completed", "response.incomplete":
			return ev.Response, nil
		case "response.failed", "error":
			msg := "the response failed"
			if ev.Error != nil && ev.Error.Message != "" {
				msg = ev.Error.Message
			}
			return nil, errors.New("codex: " + msg)
		}
	}
	if err := sc.Err(); err != nil {
		return nil, err
	}
	return nil, errors.New("codex: the stream ended without a response")
}
