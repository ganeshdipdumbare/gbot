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
	"strings"
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
	// AllowSignup lets new accounts register; the first account can
	// always register so a fresh install is usable.
	AllowSignup bool

	authLimit *auth.Limiter
	msgLimit  *auth.Limiter
	hookLimit *auth.Limiter
}

const sessionCookie = "gbot_session"
const sessionTTL = 30 * 24 * time.Hour

func (s *Server) Handler() http.Handler {
	s.authLimit = auth.NewLimiter(10, time.Minute)
	s.msgLimit = auth.NewLimiter(60, time.Minute)
	s.hookLimit = auth.NewLimiter(120, time.Minute)
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("ok")) })

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
	mux.Handle("GET /api/bots/{id}/messages", s.authed(s.listMessages))
	mux.Handle("POST /api/bots/{id}/messages", s.authed(s.sendMessage))
	mux.Handle("POST /api/bots/{id}/stop", s.authed(s.stopBot))
	mux.Handle("GET /api/events", s.authed(s.events))
	mux.Handle("GET /api/events/poll", s.authed(s.pollEvents))
	s.routesV2(mux)
	mux.Handle("/api/", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		writeErr(w, http.StatusNotFound, "not found")
	}))
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
		if strings.HasPrefix(r.URL.Path, "/api/") && r.Method != http.MethodGet && r.Method != http.MethodHead {
			if r.Header.Get("X-GBot") != "1" || !sameOrigin(r) {
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

func clientIP(r *http.Request) string {
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
