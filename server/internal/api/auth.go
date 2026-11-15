package api

import (
	"crypto/subtle"
	"errors"
	"net"
	"net/http"
	"net/mail"
	"strings"

	"github.com/jackc/pgx/v5/pgconn"

	"gbot/internal/auth"
	"gbot/internal/models"
	"gbot/internal/store"
)

// setup tells the client whether this is a fresh install.
func (s *Server) setup(w http.ResponseWriter, r *http.Request) {
	n, err := s.Store.CountUsers(r.Context())
	if err != nil {
		writeErr(w, 500, "database unavailable")
		return
	}
	writeJSON(w, 200, map[string]any{
		"fresh":      n == 0,
		"signup":     n == 0 || s.AllowSignup,
		"setupToken": n == 0 && s.SetupToken != "",
	})
}

// firstAccountAllowed decides who may create the first account on a fresh
// server. With GBOT_SETUP_TOKEN set, only someone with the token. Without
// it, only a direct connection from this machine, never one that came
// through a proxy, so a stranger who reaches a new server first can't
// claim it.
func (s *Server) firstAccountAllowed(r *http.Request, token string) bool {
	if s.SetupToken != "" {
		return subtle.ConstantTimeCompare([]byte(token), []byte(s.SetupToken)) == 1
	}
	if s.TrustProxy {
		return false
	}
	// A proxy usually passes the public host name through, so the Host must
	// also be a local name.
	host := r.Host
	if h, _, err := net.SplitHostPort(r.Host); err == nil {
		host = h
	}
	if ip := net.ParseIP(host); host != "localhost" && (ip == nil || !ip.IsLoopback()) {
		return false
	}
	for _, h := range []string{"X-Forwarded-For", "X-Real-IP", "Forwarded"} {
		if r.Header.Get(h) != "" {
			return false
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return false
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

type credentials struct {
	Email      string `json:"email"`
	Password   string `json:"password"`
	Name       string `json:"name,omitempty"`
	SetupToken string `json:"setupToken,omitempty"`
}

func (s *Server) signup(w http.ResponseWriter, r *http.Request) {
	if !s.authLimit.Allow(s.clientIP(r)) {
		writeErr(w, http.StatusTooManyRequests, "too many attempts, try again in a minute")
		return
	}
	var c credentials
	if !readJSON(w, r, &c) {
		return
	}
	n, err := s.Store.CountUsers(r.Context())
	if err != nil {
		writeErr(w, http.StatusServiceUnavailable, "database unavailable")
		return
	}
	if n > 0 && !s.AllowSignup {
		writeErr(w, http.StatusForbidden, "sign-ups are closed on this server")
		return
	}
	if n == 0 && !s.firstAccountAllowed(r, c.SetupToken) {
		writeErr(w, http.StatusForbidden, "this server is new: create the first account from the machine it runs on, or enter the setup token from GBOT_SETUP_TOKEN")
		return
	}
	c.Email = strings.ToLower(strings.TrimSpace(c.Email))
	c.Name = strings.TrimSpace(c.Name)
	if _, err := mail.ParseAddress(c.Email); err != nil || len(c.Email) > 254 {
		writeErr(w, 400, "enter a valid email")
		return
	}
	if len(c.Password) < 10 || len(c.Password) > 256 {
		writeErr(w, 400, "use a password of at least 10 characters")
		return
	}
	if c.Name == "" {
		c.Name = strings.Split(c.Email, "@")[0]
	}
	hash, err := auth.HashPassword(c.Password)
	if err != nil {
		writeErr(w, 500, "could not create account")
		return
	}
	first := store.NewBot{
		Name:  "Mochi",
		Title: "your first teammate",
		Look:  store.Look{"seed": auth.NewToken()[:10]},
	}
	u, err := s.Store.CreateUser(r.Context(), c.Email, c.Name, hash, s.Models.Pick(r.Context()), first, n == 0)
	if errors.Is(err, store.ErrNotFirst) {
		writeErr(w, http.StatusForbidden, "this server already has accounts")
		return
	}
	if err != nil {
		var pg *pgconn.PgError
		if errors.As(err, &pg) && pg.Code == "23505" {
			// The same answer whether or not the email is registered, so
			// sign-up can't be used to find out who has an account.
			writeErr(w, http.StatusBadRequest, "couldn't create an account with those details. Try signing in instead.")
			return
		}
		s.Log.Error("signup", "err", err)
		writeErr(w, 500, "could not create account")
		return
	}
	s.startSession(w, r, u)
}

func (s *Server) login(w http.ResponseWriter, r *http.Request) {
	if !s.authLimit.Allow(s.clientIP(r)) {
		writeErr(w, http.StatusTooManyRequests, "too many attempts, try again in a minute")
		return
	}
	var c credentials
	if !readJSON(w, r, &c) {
		return
	}
	u, err := s.Store.UserByEmail(r.Context(), strings.ToLower(strings.TrimSpace(c.Email)))
	if err != nil {
		auth.CheckDummy(c.Password)
		writeErr(w, 401, "wrong email or password")
		return
	}
	if ok, _ := auth.CheckPassword(c.Password, u.PasswordHash()); !ok {
		writeErr(w, 401, "wrong email or password")
		return
	}
	s.startSession(w, r, u)
}

func (s *Server) startSession(w http.ResponseWriter, r *http.Request, u *store.User) {
	tok := auth.NewToken()
	if err := s.Store.CreateSession(r.Context(), tok, u.ID, sessionTTL); err != nil {
		writeErr(w, 500, "could not sign in")
		return
	}
	http.SetCookie(w, &http.Cookie{Name: sessionCookie, Value: tok, Path: "/", HttpOnly: true,
		Secure: s.SecureCookies, SameSite: http.SameSiteLaxMode, MaxAge: int(sessionTTL.Seconds())})
	writeJSON(w, 200, u)
}

func (s *Server) logout(w http.ResponseWriter, r *http.Request) {
	if c, err := r.Cookie(sessionCookie); err == nil {
		_ = s.Store.DeleteSession(r.Context(), c.Value)
	}
	http.SetCookie(w, &http.Cookie{Name: sessionCookie, Value: "", Path: "/", HttpOnly: true,
		Secure: s.SecureCookies, SameSite: http.SameSiteLaxMode, MaxAge: -1})
	writeJSON(w, 200, map[string]bool{"ok": true})
}

func (s *Server) me(w http.ResponseWriter, r *http.Request) { writeJSON(w, 200, userFrom(r)) }

func (s *Server) updateMe(w http.ResponseWriter, r *http.Request) {
	var body struct {
		DefaultModel *string `json:"defaultModel"`
		ApprovalMode *string `json:"approvalMode"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	u := userFrom(r)
	if m := body.DefaultModel; m != nil {
		if *m != "" && s.Models.PickFor(r.Context(), s.userProviders(r.Context(), u.ID), *m) != *m {
			writeErr(w, 400, "that model isn't available")
			return
		}
		if err := s.Store.SetDefaultModel(r.Context(), u.ID, *m); err != nil {
			writeErr(w, 500, "could not save")
			return
		}
		u.DefaultModel = *m
	}
	if m := body.ApprovalMode; m != nil {
		if *m != "ask" && *m != "auto" && *m != "never" {
			writeErr(w, 400, "approval mode is ask, auto or never")
			return
		}
		if err := s.Store.SetApprovalMode(r.Context(), u.ID, *m); err != nil {
			writeErr(w, 500, "could not save")
			return
		}
		u.ApprovalMode = *m
	}
	writeJSON(w, 200, u)
}

func (s *Server) listModels(w http.ResponseWriter, r *http.Request) {
	// The catalog refreshes on its own TTL. Clearing it here on every request
	// let any signed-in client make the server probe every provider each time.
	list := s.Models.ListFor(r.Context(), s.userProviders(r.Context(), userFrom(r).ID))
	if list == nil {
		list = []models.Model{}
	}
	writeJSON(w, 200, map[string]any{"models": models.MarkBest(list), "ollamaHost": s.Models.OllamaHost})
}
