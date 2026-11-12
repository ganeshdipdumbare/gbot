package codex

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func fakeJWT(exp time.Time) string {
	claims, _ := json.Marshal(map[string]any{"exp": exp.Unix()})
	return "x." + base64.RawURLEncoding.EncodeToString(claims) + ".y"
}

func writeLogin(t *testing.T, dir string, exp time.Time) {
	t.Helper()
	b, _ := json.Marshal(map[string]any{
		"auth_mode": "chatgpt",
		"tokens":    map[string]any{"access_token": fakeJWT(exp), "refresh_token": "r1", "account_id": "acct"},
	})
	if err := os.WriteFile(filepath.Join(dir, "auth.json"), b, 0o600); err != nil {
		t.Fatal(err)
	}
}

func TestAllowed(t *testing.T) {
	t.Setenv("GBOT_CODEX_USERS", "A@x.com, b@y.com")
	if !Allowed("a@x.com") || !Allowed("B@Y.com") || Allowed("c@z.com") || Allowed("") {
		t.Fatal("allow list not honoured")
	}
	t.Setenv("GBOT_CODEX_USERS", "")
	if Allowed("a@x.com") {
		t.Fatal("an empty list must allow nobody")
	}
}

func TestModelsFromCache(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("GBOT_CODEX_HOME", dir)
	writeLogin(t, dir, time.Now().Add(time.Hour))
	os.WriteFile(filepath.Join(dir, "models_cache.json"), []byte(`{"models":[
		{"slug":"b","visibility":"list","supported_in_api":true,"priority":2},
		{"slug":"hidden","visibility":"hide","supported_in_api":true,"priority":0},
		{"slug":"a","visibility":"list","supported_in_api":true,"priority":1}]}`), 0o600)
	got, err := Models()
	if err != nil || strings.Join(got, ",") != "a,b" {
		t.Fatalf("models = %v, %v", got, err)
	}
}

func TestTransportSignsForcesStreamAndCollects(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("GBOT_CODEX_HOME", dir)
	writeLogin(t, dir, time.Now().Add(time.Hour))

	var seen map[string]any
	var auth, account string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		auth, account = r.Header.Get("Authorization"), r.Header.Get("chatgpt-account-id")
		b, _ := io.ReadAll(r.Body)
		json.Unmarshal(b, &seen)
		w.Header().Set("Content-Type", "text/event-stream")
		fmt.Fprint(w, "data: {\"type\":\"response.output_text.delta\",\"delta\":\"o\"}\n\n")
		fmt.Fprint(w, "data: {\"type\":\"response.completed\",\"response\":{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}]}}\n\n")
	}))
	defer srv.Close()

	c := &http.Client{Transport: Transport{}}
	resp, err := c.Post(srv.URL+"/responses", "application/json",
		strings.NewReader(`{"model":"m","stream":false,"temperature":0.2,"input":[]}`))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if seen["stream"] != true || seen["store"] != false || seen["temperature"] != nil {
		t.Fatalf("request not adapted: %v", seen)
	}
	if !strings.HasPrefix(auth, "Bearer x.") || account != "acct" {
		t.Fatalf("not signed: %q %q", auth, account)
	}
	var out struct {
		Output []struct {
			Content []struct{ Text string } `json:"content"`
		} `json:"output"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil || out.Output[0].Content[0].Text != "ok" {
		t.Fatalf("collected body wrong: %+v %v", out, err)
	}
}

func TestNoLogin(t *testing.T) {
	t.Setenv("GBOT_CODEX_HOME", t.TempDir())
	if Available() {
		t.Fatal("no auth.json must mean unavailable")
	}
	if _, err := Models(); err == nil {
		t.Fatal("models without a login must fail")
	}
}
