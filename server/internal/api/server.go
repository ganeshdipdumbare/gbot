// Package api is gbot's HTTP surface: JSON endpoints, the live event
// stream, and the embedded web app.
package api

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"slices"
	"strings"
	"sync"
	"time"

	"gbot/internal/auth"
	"gbot/internal/computer"
	"gbot/internal/crypto"
	"gbot/internal/models"
	"gbot/internal/routines"
	"gbot/internal/runs"
	"gbot/internal/store"
	"gbot/internal/web"
)

type Server struct {
	Store   *store.Store
	Runtime *runs.Runtime
	Hub     *runs.Hub
	Models  *models.Catalog
	Log     *slog.Logger
	// Optional services; their endpoints answer 503 when missing.
	Computer *computer.Manager
	Routines *routines.Service
	Sealer   *crypto.Sealer
	Voice    VoiceConfig
	// SecureCookies is set when served over HTTPS.
	SecureCookies bool
	// LoopbackOnly is set when the server listens only on this machine. It
	// then answers only to local names, the PublicURL host and ExtraHosts,
	// so a web page that points its own name at 127.0.0.1 (DNS rebinding)
	// can't use it.
	LoopbackOnly bool
	// ExtraHosts are further names this server answers to (GBOT_HOSTS).
	ExtraHosts []string
	// PublicURL is the address people open in a browser, when this server
	// sits behind a proxy. OAuth callbacks must use it, not the internal
	// host the proxy connects to.
	PublicURL string
	// AllowSignup lets new accounts register; the first account can
	// always register so a fresh install is usable.
	AllowSignup bool
	// SetupToken, when set, must be presented to create the first account,
	// so a fresh install reached by a stranger cannot be claimed by them.
	SetupToken string
	// TrustProxy says a reverse proxy in front of this server sets
	// X-Forwarded-For. Only then are client addresses read from it.
	TrustProxy bool

	authLimit *auth.Limiter
	// loginLimit counts sign-in attempts per account, so guesses spread
	// over many addresses still slow down for one account.
	loginLimit *auth.Limiter
	msgLimit   *auth.Limiter
	voiceLimit *auth.Limiter
	hookLimit  *auth.Limiter
	probeLimit *auth.Limiter

	// mcpWake coalesces integration changes onto one background worker,
	// so adding or removing a server never waits on a slow one.
	mcpOnce sync.Once
	mcpWake chan struct{}

	// oauthPending holds sign-ins that have not come back yet, keyed by
	// state. Only the callback touches it concurrently.
	oauthMu      sync.Mutex
	oauthPending map[string]*mcpPending

	// slackWait holds where each in-flight Slack reply belongs, and
	// slackSubs which spaces already have a relay watching for them.
	slackMu   sync.Mutex
	slackWait map[string]slackTarget
	slackSubs map[string]bool
}

const sessionCookie = "gbot_session"
const sessionTTL = 30 * 24 * time.Hour

func (s *Server) Handler() http.Handler {
	s.startMCPSync()
	s.authLimit = auth.NewLimiter(10, time.Minute)
	s.loginLimit = auth.NewLimiter(20, 15*time.Minute)
	s.msgLimit = auth.NewLimiter(60, time.Minute)
	// Speech calls cost the operator money when a hosted service is set.
	s.voiceLimit = auth.NewLimiter(30, time.Minute)
	s.hookLimit = auth.NewLimiter(120, time.Minute)
	// Provider discovery makes outbound requests on the server's behalf.
	s.probeLimit = auth.NewLimiter(20, time.Minute)
	mux := http.NewServeMux()
	// Allow the desktop shell (Tauri custom-protocol origin) to discover us.
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Write([]byte("ok"))
	})

	mux.HandleFunc("GET /api/setup", s.setup)
	mux.HandleFunc("POST /api/auth/signup", s.signup)
	mux.HandleFunc("POST /api/auth/login", s.login)
	mux.HandleFunc("POST /api/auth/logout", s.logout)

	mux.Handle("GET /api/me", s.authed(s.me))
	mux.Handle("PATCH /api/me", s.authed(s.updateMe))
	mux.Handle("GET /api/models", s.authed(s.listModels))
	mux.Handle("GET /api/bots", s.authed(s.listBots))
	mux.Handle("POST /api/bots", s.authed(s.createBot))
	mux.Handle("GET /api/bots/{id}", s.authed(s.getBot))
	mux.Handle("PATCH /api/bots/{id}", s.authed(s.updateBot))
	mux.Handle("DELETE /api/bots/{id}", s.authed(s.archiveBot))
	mux.Handle("GET /api/blobs/{id}", s.authed(s.blob))
	mux.Handle("GET /api/bots/{id}/messages", s.authed(s.listMessages))
	mux.Handle("POST /api/bots/{id}/messages", s.authed(s.sendMessage))
	mux.Handle("DELETE /api/bots/{id}/messages", s.authed(s.clearChat))
	mux.Handle("POST /api/bots/{id}/stop", s.authed(s.stopBot))
	mux.Handle("GET /api/events", s.authed(s.events))
	mux.Handle("GET /api/events/poll", s.authed(s.pollEvents))
	mux.HandleFunc("POST /slack/events", s.slackEvents)
	mux.Handle("GET /api/slack", s.authed(s.listSlack))
	mux.Handle("POST /api/slack", s.authed(s.connectSlack))
	mux.Handle("DELETE /api/slack/{id}", s.authed(s.disconnectSlack))
	mux.Handle("PUT /api/slack/{id}/links", s.authed(s.linkSlack))
	mux.Handle("DELETE /api/slack/{id}/links/{channel}", s.authed(s.unlinkSlack))
	mux.Handle("GET /api/bots/{id}/sharing", s.authed(s.sharing))
	mux.Handle("PUT /api/bots/{id}/sharing", s.authed(s.setVisibility))
	mux.Handle("POST /api/bots/{id}/sharing/members", s.authed(s.addBotMember))
	mux.Handle("DELETE /api/bots/{id}/sharing/members/{uid}", s.authed(s.removeBotMember))
	mux.Handle("GET /api/spaces", s.authed(s.listSpaces))
	mux.Handle("POST /api/spaces", s.authed(s.createSpace))
	mux.Handle("PUT /api/me/space", s.authed(s.selectSpace))
	mux.Handle("GET /api/spaces/{id}/members", s.authed(s.spaceMembers))
	mux.Handle("POST /api/spaces/{id}/members", s.authed(s.addSpaceMember))
	mux.Handle("DELETE /api/spaces/{id}/members/{uid}", s.authed(s.removeSpaceMember))
	s.routesV2(mux)
	mux.Handle("/api/", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		writeErr(w, http.StatusNotFound, "not found")
	}))
	// The phone's BB sign-in ends here: behind the BB login, but not the
	// web app. The app closes the WebView when this URL loads.
	mux.HandleFunc("GET /bb-ready", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write([]byte(`<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>BB connected</title><body style="margin:0;min-height:100vh;display:grid;place-items:center;background:#FFF9F3;color:#2b2b2b;font:16px system-ui,sans-serif"><div style="text-align:center"><div style="width:44px;height:44px;border-radius:22px;background:#8B5CF6;margin:0 auto 14px"></div><strong>BB connected</strong><p style="color:#777;font-size:14px">Return to gbot.</p></div>`))
	})
	mux.Handle("/", web.Handler())
	return s.secure(mux)
}

// secure sets strict browser protections and blocks cross-site writes:
// every non-GET API call needs the X-GBot header (which a cross-site
// form can't send without a CORS preflight we never grant) and, when the
// browser sends one, a same-origin Origin.
func (s *Server) secure(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("Content-Security-Policy", "default-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; style-src 'self' 'unsafe-inline'; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'; object-src 'none'")
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Referrer-Policy", "same-origin")
		h.Set("Permissions-Policy", "camera=(), geolocation=(), microphone=(self)")
		h.Set("Cross-Origin-Opener-Policy", "same-origin")
		if s.SecureCookies {
			h.Set("Strict-Transport-Security", "max-age=63072000; includeSubDomains")
		}
		if !s.hostAllowed(r.Host) {
			writeErr(w, http.StatusMisdirectedRequest, "this server doesn't answer to that name; set GBOT_PUBLIC_URL or GBOT_HOSTS")
			return
		}
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			// Signed machine callers (Slack events, webhook hooks) check their
			// own signatures instead of the browser header.
			machine := r.URL.Path == "/slack/events" || strings.HasPrefix(r.URL.Path, "/hooks/")
			if !machine && (r.Header.Get("X-GBot") != "1" || !sameOrigin(r)) {
				writeErr(w, http.StatusForbidden, "cross-site request blocked")
				return
			}
			limit := int64(1 << 20)
			if strings.HasPrefix(r.URL.Path, "/api/voice/") {
				limit = 25 << 20
			}
			r.Body = http.MaxBytesReader(w, r.Body, limit)
		}
		next.ServeHTTP(w, r)
	})
}

// hostAllowed applies LoopbackOnly. IP addresses always pass: rebinding
// needs a name the attacker controls. A trusted proxy picks the name.
func (s *Server) hostAllowed(hostport string) bool {
	if !s.LoopbackOnly || s.TrustProxy {
		return true
	}
	host := hostport
	if h, _, err := net.SplitHostPort(hostport); err == nil {
		host = h
	}
	host = strings.ToLower(strings.Trim(host, "[]"))
	if host == "" || net.ParseIP(host) != nil || host == "localhost" || strings.HasSuffix(host, ".localhost") {
		return true
	}
	if u, err := url.Parse(s.PublicURL); err == nil && s.PublicURL != "" && strings.EqualFold(u.Hostname(), host) {
		return true
	}
	return slices.Contains(s.ExtraHosts, host)
}

func sameOrigin(r *http.Request) bool {
	o := r.Header.Get("Origin")
	if o == "" {
		return true
	}
	o = strings.TrimPrefix(strings.TrimPrefix(o, "https://"), "http://")
	return o == r.Host
}

type ctxKey struct{}

func userFrom(r *http.Request) *store.User { return r.Context().Value(ctxKey{}).(*store.User) }

func (s *Server) authed(fn http.HandlerFunc) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c, err := r.Cookie(sessionCookie)
		if err != nil || c.Value == "" {
			writeErr(w, http.StatusUnauthorized, "sign in first")
			return
		}
		u, err := s.Store.UserBySession(r.Context(), c.Value)
		if err != nil {
			if !errors.Is(err, store.ErrNotFound) {
				s.Log.Error("session lookup", "err", err)
			}
			writeErr(w, http.StatusUnauthorized, "sign in first")
			return
		}
		fn(w, r.WithContext(context.WithValue(r.Context(), ctxKey{}, u)))
	})
}

// stillAllowed re-checks a long-lived request (an event or screen stream)
// against the current session: it ends when the person signs out, the
// session expires, or they leave the team the stream was opened in. With a
// bot, they must also still be able to open it.
func (s *Server) stillAllowed(r *http.Request, botID string) bool {
	c, err := r.Cookie(sessionCookie)
	if err != nil {
		return false
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	now, err := s.Store.UserBySession(ctx, c.Value)
	if err != nil {
		// Only a definite answer ends the stream; a passing database error
		// does not cut someone off mid-session.
		return !errors.Is(err, store.ErrNotFound)
	}
	was := userFrom(r)
	if now.ID != was.ID || now.SpaceID != was.SpaceID {
		return false
	}
	if botID != "" {
		if _, err := s.Store.GetBotFor(ctx, now.SpaceID, now.ID, botID); errors.Is(err, store.ErrNotFound) {
			return false
		}
	}
	return true
}

// authKey is the rate-limit key for sign-in and sign-up: the client's
// address, with an IPv6 address widened to its /64, since one host
// usually holds the whole /64.
func (s *Server) authKey(r *http.Request) string {
	ip := net.ParseIP(s.clientIP(r))
	if ip != nil && ip.To4() == nil {
		return ip.Mask(net.CIDRMask(64, 128)).String() + "/64"
	}
	return s.clientIP(r)
}

// clientIP names the caller for rate limits. Behind a proxy every request
// shares the proxy's address, so the limits would lock everyone out at
// once; with TrustProxy the proxy's forwarded address is used instead.
func (s *Server) clientIP(r *http.Request) string {
	if s.TrustProxy {
		// A trusted proxy appends the address it received from, so the
		// last entry is the one it can vouch for. Only X-Forwarded-For is
		// read: X-Real-IP is set by whoever sends the request unless the
		// proxy overwrites it.
		if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
			parts := strings.Split(xff, ",")
			if ip := strings.TrimSpace(parts[len(parts)-1]); ip != "" {
				return ip
			}
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}

func readJSON(w http.ResponseWriter, r *http.Request, v any) bool {
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil {
		writeErr(w, http.StatusBadRequest, "invalid request body")
		return false
	}
	return true
}
