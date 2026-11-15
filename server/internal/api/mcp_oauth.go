package api

import (
	"context"
	"encoding/json"
	"fmt"
	"html"
	"net/http"
	"net/url"
	"strings"
	"time"
	"unicode/utf8"

	"gbot/internal/crypto"
	mcpoauth "gbot/internal/mcp"
	"gbot/internal/store"
)

// An OAuth sign-in is short-lived and tied to the browser that started
// it. The pending record holds the PKCE verifier until the provider
// redirects back; nothing secret is sent to the page.
type mcpPending struct {
	user, name, serverURL, redirect, verifier string
	client                                    *mcpoauth.Client
	cfg                                       *mcpoauth.Config
	expires                                   time.Time
}

const oauthTTL = 15 * time.Minute

func (s *Server) startMCPOAuth(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Name string `json:"name"`
		URL  string `json:"url"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	body.Name = strings.TrimSpace(body.Name)
	u, err := url.Parse(strings.TrimSpace(body.URL))
	if body.Name == "" || utf8.RuneCountInString(body.Name) > 40 || err != nil || (u.Scheme != "https" && !(u.Scheme == "http" && isLocalHost(u.Hostname()))) || u.Host == "" {
		writeErr(w, 400, "give it a name and an https:// URL (http:// only for localhost)")
		return
	}
	if s.Sealer == nil {
		writeErr(w, 503, "secret storage is not configured")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()
	base := s.oauthBase(r)
	if u, err := url.Parse(base); err == nil && u.Scheme != "https" && !isLocalHost(u.Hostname()) {
		writeErr(w, 400, "set GBOT_PUBLIC_URL to this site's https address so apps can send you back")
		return
	}
	cfg, err := mcpoauth.Discover(ctx, u.String())
	if err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	redirect := base + "/api/mcp/oauth/callback"
	client, err := mcpoauth.Register(ctx, cfg, redirect)
	if err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	state, err := mcpoauth.Random(32)
	if err != nil {
		writeErr(w, 500, "could not start the sign-in")
		return
	}
	verifier, err := mcpoauth.Random(32)
	if err != nil {
		writeErr(w, 500, "could not start the sign-in")
		return
	}
	s.oauthMu.Lock()
	if s.oauthPending == nil {
		s.oauthPending = map[string]*mcpPending{}
	}
	for k, p := range s.oauthPending {
		if time.Now().After(p.expires) {
			delete(s.oauthPending, k)
		}
	}
	s.oauthPending[state] = &mcpPending{
		user: userFrom(r).ID, name: body.Name, serverURL: u.String(), redirect: redirect,
		verifier: verifier, client: client, cfg: cfg, expires: time.Now().Add(oauthTTL),
	}
	s.oauthMu.Unlock()
	writeJSON(w, 200, map[string]string{"url": cfg.AuthURL(client.ID, redirect, state, verifier)})
}

// oauthBase is the public origin the provider must send the browser back
// to. A configured public URL wins: behind a tunnel or proxy the request
// host can be an internal address that other devices can't reach.
func (s *Server) oauthBase(r *http.Request) string {
	if s.PublicURL != "" {
		return s.PublicURL
	}
	if o := r.Header.Get("Origin"); o != "" {
		if u, err := url.Parse(o); err == nil && u.Scheme != "" && u.Host != "" {
			return u.Scheme + "://" + u.Host
		}
	}
	scheme := "http"
	if r.TLS != nil || r.Header.Get("X-Forwarded-Proto") == "https" {
		scheme = "https"
	}
	return scheme + "://" + r.Host
}

func (s *Server) takePending(state string) *mcpPending {
	if state == "" {
		return nil
	}
	s.oauthMu.Lock()
	defer s.oauthMu.Unlock()
	p := s.oauthPending[state]
	delete(s.oauthPending, state)
	if p == nil || time.Now().After(p.expires) {
		return nil
	}
	return p
}

func (s *Server) mcpOAuthCallback(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	if e := q.Get("error"); e != "" {
		msg := q.Get("error_description")
		if msg == "" {
			msg = e
		}
		s.oauthPage(w, http.StatusBadRequest, false, "", msg)
		return
	}
	p := s.takePending(q.Get("state"))
	if p == nil {
		s.oauthPage(w, http.StatusBadRequest, false, "", "This sign-in expired. Start again from Settings.")
		return
	}
	code := q.Get("code")
	if code == "" {
		s.oauthPage(w, http.StatusBadRequest, false, "", "The app didn't return a code.")
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	tokens, err := mcpoauth.Exchange(ctx, p.cfg, p.client.ID, p.client.Secret, code, p.verifier, p.redirect)
	if err != nil {
		s.oauthPage(w, http.StatusBadRequest, false, "", err.Error())
		return
	}
	raw, _ := json.Marshal(tokens)
	sealed := s.Sealer.Seal(raw, crypto.Owner("mcp-oauth", p.user))
	// Reconnecting an app replaces its tokens instead of adding a
	// second copy of the same server.
	var m *store.MCPServer
	if list, lerr := s.Store.ListMCP(ctx, p.user); lerr == nil {
		for _, old := range list {
			if strings.TrimRight(old.URL, "/") == strings.TrimRight(p.serverURL, "/") {
				m, err = s.Store.SetMCPOAuth(ctx, p.user, old.ID, sealed)
				break
			}
		}
	}
	if m == nil {
		m, err = s.Store.CreateMCP(ctx, p.user, p.name, p.serverURL, nil)
		if err == nil {
			m, err = s.Store.SetMCPOAuth(ctx, p.user, m.ID, sealed)
		}
	}
	if err != nil {
		s.Log.Error("mcp oauth save", "server", p.name, "err", err)
		s.oauthPage(w, http.StatusInternalServerError, false, "", "Could not save the connection.")
		return
	}
	s.Runtime.WarmMCP(m, s.Sealer)
	s.queueMCP()
	s.oauthPage(w, http.StatusOK, true, p.name, "")
}

// oauthPage closes the popup and tells the settings page what happened.
// It is the one route with an inline-script CSP.
func (s *Server) oauthPage(w http.ResponseWriter, status int, ok bool, name, msg string) {
	title, line := "Couldn't connect", msg
	if ok {
		title, line = "Connected", "You can close this window."
	}
	payload, _ := json.Marshal(map[string]any{"type": "gbot-integration", "ok": ok, "name": name, "error": msg})
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; base-uri 'none'")
	w.WriteHeader(status)
	fmt.Fprintf(w, `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>%s</title>
<style>body{margin:0;min-height:100vh;display:grid;place-items:center;background:#111;color:#eee;font:15px/1.5 system-ui,sans-serif;text-align:center;padding:24px}strong{display:block;font-size:20px;margin-bottom:6px}a{color:#a78bfa}</style>
<div><strong>%s</strong><p>%s</p><p><a href="/">Return to gbot</a></p></div>
<script>
try{window.opener&&window.opener.postMessage(%s,location.origin)}catch(e){}
setTimeout(function(){window.close()},900);
</script>`, html.EscapeString(title), html.EscapeString(title), html.EscapeString(line), payload)
}
